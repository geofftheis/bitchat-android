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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Patch 57: Total connection cap — reject inbound if total would exceed this. */
    var maxTotalConnections: Int = Int.MAX_VALUE
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    // Patch 95: Per-address one-shot waiters completed when the GATT server
    // callback emits STATE_DISCONNECTED. Mirrors the client-side mechanism so
    // callers that cancel a server-side connection can wait for the BLE stack
    // to finish the teardown before proceeding (e.g. before advertising again,
    // or before closing the whole GATT server during shutdown).
    private val disconnectWaiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
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

    // Patch 86: Signals when onServiceAdded fires with GATT_SUCCESS.
    private var serviceReady = CompletableDeferred<Boolean>()

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
     * Patch 95: Cancel the server-side connection to [device] and suspend until
     * the GATT server callback emits STATE_DISCONNECTED for this device, or
     * [timeoutMs] elapses. Returns true if the callback fired, false on timeout.
     * Gives the BLE controller time to emit LL_TERMINATE_IND before the caller
     * proceeds with further radio operations (advertising restart, close, etc.).
     */
    suspend fun disconnectDeviceAndAwait(device: BluetoothDevice, timeoutMs: Long = 500): Boolean {
        val address = device.address
        val deferred = CompletableDeferred<Unit>()
        disconnectWaiters.put(address, deferred)?.complete(Unit)
        return try {
            try {
                gattServer?.cancelConnection(device)
            } catch (e: Exception) {
                Log.w(TAG, "Patch 95: server cancelConnection threw for $address: ${e.message}")
                deferred.complete(Unit)
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() } != null
        } finally {
            disconnectWaiters.remove(address, deferred)
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
        serviceReady = CompletableDeferred() // Patch 86: Reset for this start cycle

        // Patch 86: Only set up the GATT server here. Advertising is deferred
        // until BluetoothConnectionManager calls beginAdvertising() after
        // stale ACL eviction is complete.
        connectionScope.launch {
            setupGattServer()
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

        // Patch 86: Cancel any pending awaitServiceReady() callers
        if (serviceReady.isActive) {
            serviceReady.cancel()
        }

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
     * Patch 86: Suspend until onServiceAdded fires, with timeout.
     * Returns true if the GATT service was added successfully within the deadline.
     */
    suspend fun awaitServiceReady(timeoutMs: Long = 2000): Boolean {
        return try {
            withTimeoutOrNull(timeoutMs) { serviceReady.await() } ?: run {
                Log.w(TAG, "Patch 86: awaitServiceReady timed out after ${timeoutMs}ms")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Patch 86: awaitServiceReady failed: ${e.message}")
            false
        }
    }

    /**
     * Patch 86: Start advertising. Called by BluetoothConnectionManager after
     * stale ACL eviction is complete, so the host discovers this device on
     * a clean BLE radio.
     */
    fun beginAdvertising() {
        // Use restartJob to prevent orphaned callbacks — same pattern as restartAdvertising().
        restartJob?.cancel()
        restartJob = connectionScope.launch {
            startAdvertising()
        }
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
                
                // Audit: how many devices does the BLE stack think are connected?
                val stackDevices = try {
                    bluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
                } catch (_: Exception) { emptyList() }
                val stackCount = stackDevices.size
                val stackMACs = stackDevices.joinToString(", ") { it.address }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address} (BLE stack reports $stackCount devices: $stackMACs)")

                        // Patch 58: When maxServerConnections=0 (pre-lobby joining player),
                        // immediately reject inbound ACL connections. Without this, the ACL
                        // connection is tracked in connectedDevices and consumes the
                        // maxTotalConnections budget before the subscription check in
                        // onDescriptorWriteRequest can reject it. This blocks the single
                        // pre-lobby outbound slot meant for the host connection.
                        if (maxServerConnections <= 0) {
                            Log.i(TAG, "Patch 58: Rejecting inbound ACL from ${device.address} (maxServerConnections=0, pre-lobby)")
                            try { gattServer?.cancelConnection(device) } catch (_: Exception) { }
                            return
                        }

                        // Patch 62: Don't add to connectedDevices here. Only track devices
                        // that complete the GATT subscription handshake (onDescriptorWriteRequest).
                        // This prevents phantom connections (non-game BLE devices, background
                        // scans, etc.) from consuming server connection slots.
                        Log.i(TAG, "Server: ACL connected from ${device.address} — awaiting subscription")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address} (BLE stack reports $stackCount devices: $stackMACs)")
                        connectionTracker.cleanupDeviceConnection(device.address)
                        // Notify delegate about device disconnection so higher layers can update direct flags
                        delegate?.onDeviceDisconnected(device)
                        // Patch 95: Signal any disconnectDeviceAndAwait() waiter.
                        disconnectWaiters.remove(device.address)?.complete(Unit)
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
                    serviceReady.complete(true) // Patch 86
                } else {
                    Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                    serviceReady.complete(false) // Patch 86: unblock waiters on failure
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
                    // Patch 57/62: Total connection count uses subscribed + client (game
                    // participants only), not raw connectedDevices which could include
                    // phantom BLE connections from non-game devices.
                    val currentServerCount = connectionTracker.getSubscribedDevices().size
                    val clientCount = connectionTracker.getConnectedDevices().values.count { it.isClient }
                    val totalGameConnections = currentServerCount + clientCount
                    if (currentServerCount >= maxServerConnections || totalGameConnections >= maxTotalConnections) {
                        Log.d(TAG, "Server: Ignoring subscription from ${device.address} (server: $currentServerCount/$maxServerConnections, total: $totalGameConnections/$maxTotalConnections)")
                    } else if (connectionTracker.getConnectedDevices().containsKey(device.address)) {
                        // Already tracked from a previous subscription write (e.g., device
                        // subscribed to both notifications and indications). Skip duplicate.
                        Log.d(TAG, "Server: Ignoring duplicate subscription from ${device.address}")
                    } else {
                        // Patch 62: Track the device in connectedDevices now that it has
                        // subscribed. This is the point where we know it's a real game
                        // participant, not a phantom BLE connection.
                        val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                            device = device,
                            rssi = rssi,
                            isClient = false
                        )
                        connectionTracker.addDeviceConnection(device.address, deviceConn)
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
        advertiseCallback = null
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
