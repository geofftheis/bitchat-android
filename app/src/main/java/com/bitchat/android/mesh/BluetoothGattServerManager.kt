package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val myPeerID: String,
    private val serviceUuid: UUID = com.bitchat.android.util.AppConstants.Mesh.Gatt.SERVICE_UUID
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
    }

    /** Max inbound subscriptions to accept. 0 = reject all (pre-lobby joining player). */
    var maxServerConnections: Int = Int.MAX_VALUE
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    
    // State management
    private var isActive = false
    // Patch 31: Track the restartAdvertising coroutine to prevent orphaned callbacks.
    // If restartAdvertising() is called multiple times rapidly, each launch creates a
    // new AdvertiseCallback but only the last is stored in advertiseCallback. The earlier
    // callbacks become orphaned — their advertisements continue broadcasting on the BLE
    // radio but can never be stopped. Cancelling the previous Job before launching a new
    // one ensures only one restart is in-flight at a time.
    private var restartJob: Job? = null

    // Optional game metadata byte for Half-Wit advertisement (Patch 26)
    // Bit 7: locked flag, Bits 0-3: player count
    var gameMetadataByte: Byte? = null

    // Patch 42 indication ACK callback removed — using fire-and-forget notifications.

    // Patch 36: Callback invoked when advertising fails after all retry attempts.
    // Allows the consuming app to detect slot exhaustion and notify the user.
    var onAdvertisingFailed: ((Int) -> Unit)? = null

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    fun start(): Boolean {
        if (isActive) {
            Log.d(TAG, "GATT server already active; start is a no-op")
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            setupGattServer()
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    fun stop() {
        // Patch 31: Cancel any pending restartAdvertising coroutine FIRST to prevent
        // it from re-starting advertising after we stop. Without this, an in-flight
        // restart could create an orphaned advertisement that persists on the BLE radio.
        restartJob?.cancel()
        restartJob = null
        if (!isActive) {
            // Idempotent stop
            stopAdvertising()
            // Ensure server is closed if present
            gattServer?.close()
            gattServer = null
            Log.i(TAG, "GATT server stopped (already inactive)")
            return
        }

        isActive = false

        // Stop advertising and close GATT server synchronously so they aren't
        // skipped when connectionScope is cancelled immediately after stop().
        stopAdvertising()

        // Try to cancel any active connections explicitly before closing
        try {
            val servers = connectionTracker.getConnectedDevices().values.filter { !it.isClient }
            servers.forEach { d ->
                try { gattServer?.cancelConnection(d.device) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        gattServer?.close()
        gattServer = null

        Log.i(TAG, "GATT server stopped")
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring connection state change after shutdown")
                    return
                }
                
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")

                        // Get best available RSSI (scan RSSI for server connections)
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE

                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)

                        connectionScope.launch {
                            delay(1000)
                            if (isActive) { // Check if still active
                                delegate?.onDeviceConnected(device)
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        connectionTracker.cleanupDeviceConnection(device.address)
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device)
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring service added callback after shutdown")
                    return
                }
                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Server: Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring characteristic write after shutdown")
                    return
                }
                
                if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                    Log.i(TAG, "Server: Received packet from ${device.address}, size: ${value.size} bytes")
                    val packet = BitchatPacket.fromBinaryData(value)
                    if (packet != null) {
                        val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                        Log.d(TAG, "Server: Parsed packet type ${packet.type} from $peerID")
                        delegate?.onPacketReceived(packet, peerID, device)
                    } else {
                        Log.w(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        Log.w(TAG, "Server: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                // Guard against callbacks after service shutdown
                if (!isActive) {
                    Log.d(TAG, "Server: Ignoring descriptor write after shutdown")
                    return
                }
                
                // Patch 40: Accept both indication and notification subscription requests.
                // We prefer indications (confirmed) but accept notifications for compatibility.
                if (BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.contentEquals(value) ||
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    // Patch 50: Check server limit before accepting subscription.
                    // Silently ignore instead of accepting then evicting, which would
                    // kill the shared ACL link and destroy our outbound client connection.
                    val currentServerCount = connectionTracker.getSubscribedDevices().size
                    if (currentServerCount >= maxServerConnections) {
                        Log.d(TAG, "Server: Ignoring subscription from ${device.address} (at limit: $maxServerConnections)")
                    } else {
                        connectionTracker.addSubscribedDevice(device)

                        Log.d(TAG, "Server: Connection setup complete for ${device.address}")
                        connectionScope.launch {
                            delay(100)
                            if (isActive) {
                                delegate?.onDeviceConnected(device)
                            }
                        }
                    }
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            // Patch 42 onNotificationSent removed — no longer tracking ACKs.
        }

        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }
        
        // Small delay to ensure cleanup is complete
        Thread.sleep(100)
        
        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        
        // Create characteristic with indication support (Patch 40: confirmed delivery)
        characteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        gattServer?.addService(service)
        
        Log.i(TAG, "GATT server setup complete")
    }
    
    /**
     * Start advertising
     */
    /**
     * Patch 36: Start advertising with retry logic. Retries up to 10 times at 100ms
     * intervals when all BLE advertising slots are occupied (error code 4:
     * ADVERTISE_FAILED_TOO_MANY_ADVERTISERS). If all attempts fail, invokes the
     * onAdvertisingFailed callback so the app can notify the user.
     */
    @Suppress("DEPRECATION")
    private suspend fun startAdvertising() {
        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            return
        }
        if (!isActive) {
            Log.d(TAG, "Not starting advertising: manager not active")
            return
        }
        if (bleAdvertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            return
        }

        val settings = powerManager.getAdvertiseSettings()

        val dataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
        // Patch 26/53a: Include game metadata + peerID prefix as manufacturer data.
        // Format: [meta, p0, p1, p2, p3] — 1 byte metadata + first 4 bytes of peerID.
        // This matches the iOS local name format ("H" + 2-hex meta + 8-hex prefix)
        // so cross-platform host identification works in the reserved-slot logic.
        gameMetadataByte?.let { meta ->
            val prefixBytes = try {
                myPeerID.chunked(2).map { it.toInt(16).toByte() }.take(4).toByteArray()
            } catch (_: Exception) { ByteArray(0) }
            dataBuilder.addManufacturerData(0xFFFF, byteArrayOf(meta) + prefixBytes)
        }
        val data = dataBuilder.build()

        // Add stable identity (first 8 bytes of peerID) to Scan Response
        // This allows scanners to deduplicate devices even if MAC address rotates
        val peerIDBytes = try {
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(serviceUuid), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val maxAttempts = 10
        var lastErrorCode = -1

        for (attempt in 1..maxAttempts) {
            // Patch 31: Stop any existing advertisement before starting a new one.
            stopAdvertising()

            val result = try {
                suspendCancellableCoroutine<Int> { cont ->
                    val callback = object : AdvertiseCallback() {
                        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                            val mode = try {
                                powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                            } catch (_: Exception) { "unknown" }
                            Log.i(TAG, "Advertising started on attempt $attempt/$maxAttempts (power mode: $mode) with stable ID: ${peerIDBytes.joinToString("") { "%02x".format(it) }}")
                            cont.resume(0)
                        }

                        override fun onStartFailure(errorCode: Int) {
                            Log.w(TAG, "Advertising failed on attempt $attempt/$maxAttempts (error=$errorCode)")
                            cont.resume(errorCode)
                        }
                    }
                    advertiseCallback = callback
                    bleAdvertiser.startAdvertising(settings, data, scanResponse, callback)
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException starting advertising (missing permission?): ${se.message}")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting advertising: ${e.message}")
                return
            }

            if (result == 0) return // Success

            lastErrorCode = result

            // Only retry for TOO_MANY_ADVERTISERS (error 4); other errors are not transient
            if (result != AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                Log.e(TAG, "Advertising failed with non-retryable error: $result")
                break
            }

            if (attempt < maxAttempts) {
                delay(100)
            }
        }

        Log.e(TAG, "Advertising failed after $maxAttempts attempts (last error=$lastErrorCode)")
        onAdvertisingFailed?.invoke(lastErrorCode)
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        if (!permissionManager.hasBluetoothPermissions() || bleAdvertiser == null) return
        try {
            advertiseCallback?.let { cb -> bleAdvertiser.stopAdvertising(cb) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }
    
    /**
     * Patch 48: Stop BLE advertising without tearing down the GATT server or
     * connections.  Cancels any pending restartJob to prevent it from
     * re-starting advertising after this call.
     */
    fun stopBleAdvertising() {
        restartJob?.cancel()
        restartJob = null
        stopAdvertising()
        Log.i(TAG, "BLE advertising stopped (GATT server still active)")
    }

    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        val enabled = true // Debug settings removed (Patch 16)
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        // Patch 31: Cancel any previous restart coroutine before launching a new one.
        // Without this, rapid calls (e.g., multiple updateGameMetadata() in succession)
        // each launch a coroutine that creates a new AdvertiseCallback. Only the last
        // callback is stored in advertiseCallback, so earlier ones become orphaned —
        // their BLE advertisements continue broadcasting but can never be stopped.
        restartJob?.cancel()
        restartJob = connectionScope.launch {
            stopAdvertising()
            delay(100)
            startAdvertising()
        }
    }

    /**
     * Patch 26: Update game metadata and restart advertising to broadcast the new value.
     * Pass null to clear metadata from the advertisement.
     */
    fun updateGameMetadata(metadataByte: Byte?) {
        this.gameMetadataByte = metadataByte
        restartAdvertising()
    }
}
