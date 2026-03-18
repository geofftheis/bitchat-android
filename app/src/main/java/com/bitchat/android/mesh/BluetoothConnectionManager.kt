package com.bitchat.android.mesh

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Power-optimized Bluetooth connection manager with comprehensive memory management
 * Integrates with PowerManager for adaptive power consumption
 * Coordinates smaller, focused components for better maintainability
 */
class BluetoothConnectionManager(
    private val context: Context,
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null,
    private val serviceUuid: java.util.UUID = com.bitchat.android.util.AppConstants.Mesh.Gatt.SERVICE_UUID
) : PowerManagerDelegate {
    
    companion object {
        private const val TAG = "BluetoothConnectionManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    // Power management
    private val powerManager = PowerManager(context.applicationContext)
    
    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Component managers
    private val permissionManager = BluetoothPermissionManager(context)
    private val connectionTracker = BluetoothConnectionTracker(connectionScope, powerManager)
    private val packetBroadcaster = BluetoothPacketBroadcaster(connectionScope, connectionTracker, fragmentManager, myPeerID)
    
    // Delegate for component managers to call back to main manager
    private val componentDelegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?) {
            Log.d(TAG, "onPacketReceived: Packet received from ${device?.address} ($peerID)")
            device?.let { bluetoothDevice ->
                // Get current RSSI for this device and update if available
                val currentRSSI = connectionTracker.getBestRSSI(bluetoothDevice.address)
                if (currentRSSI != null) {
                    delegate?.onRSSIUpdated(bluetoothDevice.address, currentRSSI)
                }
            }

            if (peerID == myPeerID) return // Ignore messages from self

            delegate?.onPacketReceived(packet, peerID, device)
        }
        
        override fun onDeviceConnected(device: BluetoothDevice) {
            // Trigger limit enforcement immediately upon any new connection
            enforceStrictLimits()
            delegate?.onDeviceConnected(device)
        }

        override fun onDeviceDisconnected(device: BluetoothDevice) {
            delegate?.onDeviceDisconnected(device)
        }
        
        override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
            delegate?.onRSSIUpdated(deviceAddress, rssi)
        }
    }
    
    private val serverManager = BluetoothGattServerManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, myPeerID, serviceUuid
    )
    private val clientManager = BluetoothGattClientManager(
        context, connectionScope, connectionTracker, permissionManager, powerManager, componentDelegate, serviceUuid
    )
    
    // Service state
    private var isActive = false
    
    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null
    
    // Public property for address-peer mapping
    val addressPeerMap get() = connectionTracker.addressPeerMap

    /** Patch 39: Switch to mesh-maintenance scan mode (BALANCED/STICKY). */
    fun switchToMeshMaintenanceMode() {
        clientManager.switchToMeshMaintenanceMode()
    }

    /** Patch 39: Stop BLE scanning (e.g. after joiner connects to game). */
    fun stopScanning() {
        clientManager.stopScanning()
    }

    /** Patch 26: Update game metadata byte in BLE advertisement. */
    fun updateGameMetadata(metadataByte: Byte?) {
        serverManager.updateGameMetadata(metadataByte)
    }

    /** Patch 36: Forward advertising failure callback to server manager. */
    var onAdvertisingFailed: ((Int) -> Unit)?
        get() = serverManager.onAdvertisingFailed
        set(value) { serverManager.onAdvertisingFailed = value }

    init {
        powerManager.delegate = this
        // Debug settings observers removed (ui/ deleted in Patch 16).
        // Server and client are always enabled; connection limits use PowerManager defaults.
    }
    
    /**
     * Centralized connection limit enforcement
     */
    private fun enforceStrictLimits() {
        if (!isActive) return

        try {
            // Use PowerManager defaults (debug overrides removed in Patch 16)
            val maxOverall = powerManager.getMaxConnections()
            val maxServer = maxOverall
            val maxClient = maxOverall

            // Get list of connections to evict to satisfy all constraints
            val toEvict = connectionTracker.getConnectionsToEvict(maxOverall, maxServer, maxClient)

            if (toEvict.isNotEmpty()) {
                Log.i(TAG, "Enforcing limits (max: $maxOverall, s: $maxServer, c: $maxClient) - evicting ${toEvict.size} connections")

                toEvict.forEach { conn ->
                    if (conn.isClient) {
                        Log.d(TAG, "Evicting client ${conn.device.address}")
                        try { conn.gatt?.disconnect() } catch (_: Exception) { }
                    } else {
                        Log.d(TAG, "Evicting server ${conn.device.address}")
                        serverManager.disconnectDevice(conn.device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enforcing limits: ${e.message}")
        }
    }
    
    /**
     * Start all Bluetooth services with power optimization
     */
    fun startServices(): Boolean {
        Log.i(TAG, "Starting power-optimized Bluetooth services...")
        
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        try {
            isActive = true
            Log.d(TAG, "ConnectionManager activated (permissions and adapter OK)")

        // set the adapter's name to our 8-character peerID for iOS privacy, TODO: Make this configurable
        // try {
        //     if (bluetoothAdapter?.name != myPeerID) {
        //         bluetoothAdapter?.name = myPeerID
        //         Log.d(TAG, "Set Bluetooth adapter name to peerID: $myPeerID for iOS compatibility.")
        //     }
        // } catch (se: SecurityException) {
        //     Log.e(TAG, "Missing BLUETOOTH_CONNECT permission to set adapter name.", se)
        // }

            // Start all component managers
            connectionScope.launch {
                // Start connection tracker first
                connectionTracker.start()
                
                // Start power manager
                powerManager.start()
                
                // Server and client always enabled (debug overrides removed in Patch 16)
                if (!serverManager.start()) {
                    Log.e(TAG, "Failed to start server manager")
                    this@BluetoothConnectionManager.isActive = false
                    return@launch
                }
                Log.d(TAG, "GATT Server started")

                if (!clientManager.start()) {
                    Log.e(TAG, "Failed to start client manager")
                    this@BluetoothConnectionManager.isActive = false
                    return@launch
                }
                Log.d(TAG, "GATT Client started")
                
                Log.i(TAG, "Bluetooth services started successfully")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth services: ${e.message}")
            isActive = false
            return false
        }
    }
    
    /**
     * Stop all Bluetooth services with proper cleanup
     */
    fun stopServices() {
        Log.i(TAG, "Stopping power-optimized Bluetooth services")

        isActive = false

        // Stop BLE advertising and GATT server/client synchronously so they
        // aren't skipped if the coroutine scope is cancelled or never executes.
        // These are lightweight Bluetooth API calls safe to run on any thread.
        Log.d(TAG, "Stopping client/server and power components...")
        clientManager.stop()
        serverManager.stop()
        powerManager.stop()
        connectionTracker.stop()

        // Cancel the coroutine scope to clean up any background jobs
        connectionScope.cancel()

        Log.i(TAG, "All Bluetooth services stopped")
    }

    /**
     * Indicates whether this instance can be safely reused for a future start.
     * Returns false if its coroutine scope has been cancelled.
     */
    fun isReusable(): Boolean {
        val active = connectionScope.isActive
        if (!active) {
            Log.d(TAG, "BluetoothConnectionManager isReusable=false (scope cancelled)")
        }
        return active
    }
    
    /**
     * Broadcast packet to connected devices with connection limit enforcement
     * Automatically fragments large packets to fit within BLE MTU limits
     */
    fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return
        
        packetBroadcaster.broadcastPacket(
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (!isActive) return false
        return packetBroadcaster.sendToPeer(
            peerID,
            routed,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }

    fun cancelTransfer(transferId: String): Boolean {
        return packetBroadcaster.cancelTransfer(transferId)
    }

    /**
     * Send a packet directly to a specific peer, without broadcasting to others.
     */
    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        if (!isActive) return false
        return packetBroadcaster.sendPacketToPeer(
            RoutedPacket(packet),
            peerID,
            serverManager.getGattServer(),
            serverManager.getCharacteristic()
        )
    }
    

    // Expose role controls for debug UI
    fun startServer() { connectionScope.launch { serverManager.start() } }
    fun stopServer() { connectionScope.launch { serverManager.stop() } }
    fun startClient() { connectionScope.launch { clientManager.start() } }
    fun stopClient() { connectionScope.launch { clientManager.stop() } }

    // Inject nickname resolver for broadcaster logs
    fun setNicknameResolver(resolver: (String) -> String?) { packetBroadcaster.setNicknameResolver(resolver) }

    // Debug snapshots for connected devices
    fun getConnectedDeviceEntries(): List<Triple<String, Boolean, Int?>> {
        return try {
            connectionTracker.getConnectedDevices().values.map { dc ->
                val rssi = if (dc.rssi != Int.MIN_VALUE) dc.rssi else null
                Triple(dc.device.address, dc.isClient, rssi)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Expose local adapter address for debug UI
    fun getLocalAdapterAddress(): String? = try { bluetoothAdapter?.address } catch (e: Exception) { null }

    fun isClientConnection(address: String): Boolean? {
        return try { connectionTracker.getConnectedDevices()[address]?.isClient } catch (e: Exception) { null }
    }

    /**
     * Public: connect/disconnect helpers for debug UI
     */
    fun connectToAddress(address: String): Boolean = clientManager.connectToAddress(address)
    fun disconnectAddress(address: String) { connectionTracker.disconnectDevice(address) }


    // Optionally disconnect all connections (server and client)
    fun disconnectAll() {
        connectionScope.launch {
            // Stop and restart to force disconnects
            clientManager.stop()
            serverManager.stop()
            delay(200)
            if (isActive) {
                // Restart managers if service is active
                serverManager.start()
                clientManager.start()
            }
        }
    }


    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectionTracker.getConnectedDeviceCount()
    
    /**
     * Get debug information including power management
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Bluetooth Connection Manager ===")
            appendLine("Bluetooth MAC Address: ${bluetoothAdapter?.address}")
            appendLine("Active: $isActive")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            appendLine("Has Permissions: ${permissionManager.hasBluetoothPermissions()}")
            appendLine("GATT Server Active: ${serverManager.getGattServer() != null}")
            appendLine()
            appendLine(powerManager.getPowerInfo())
            appendLine()
            appendLine(connectionTracker.getDebugInfo())
        }
    }
    
    // MARK: - PowerManagerDelegate Implementation
    
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")
        
        connectionScope.launch {
            // Avoid rapid scan restarts by checking if we need to change scan behavior
            val wasUsingDutyCycle = powerManager.shouldUseDutyCycle()
            
            // Update advertising with new power settings (always enabled; debug overrides removed in Patch 16)
            if (true) {
                serverManager.restartAdvertising()
            } else {
                serverManager.stop()
            }
            
            // Only restart scanning if the duty cycle behavior changed
            val nowUsingDutyCycle = powerManager.shouldUseDutyCycle()
            if (wasUsingDutyCycle != nowUsingDutyCycle) {
                Log.d(TAG, "Duty cycle behavior changed (${wasUsingDutyCycle} -> ${nowUsingDutyCycle}), restarting scan")
                if (true) { // Always enabled (debug overrides removed in Patch 16)
                    clientManager.restartScanning()
                } else {
                    clientManager.stop()
                }
            } else {
                Log.d(TAG, "Duty cycle behavior unchanged, keeping existing scan state")
            }
            
            // Enforce connection limits
            enforceStrictLimits()
        }
    }
    
    override fun onScanStateChanged(shouldScan: Boolean) {
        clientManager.onScanStateChanged(shouldScan)
    }
    
    // MARK: - Private Implementation - All moved to component managers
}

/**
 * Delegate interface for Bluetooth connection manager callbacks
 */
interface BluetoothConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, device: BluetoothDevice?)
    fun onDeviceConnected(device: BluetoothDevice)
    fun onDeviceDisconnected(device: BluetoothDevice)
    fun onRSSIUpdated(deviceAddress: String, rssi: Int)
}
