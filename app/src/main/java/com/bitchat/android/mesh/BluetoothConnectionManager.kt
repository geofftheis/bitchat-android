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

    init {
        // Patch 42 ACK wiring removed — reverted to fire-and-forget in both directions.
    }

    // Service state
    private var isActive = false
    
    // Delegate for callbacks
    var delegate: BluetoothConnectionManagerDelegate? = null

    // Public property for address-peer mapping
    val addressPeerMap get() = connectionTracker.addressPeerMap

    /** Patch 40: Host mode — disables scanning and outbound client connections. */
    var hostMode: Boolean = false

    /** Patch 40: Maximum outbound client connections. Default 10 (existing behavior).
     *  Also propagated to clientManager for pre-connection limit checks. */
    var maxClientConnections: Int = 10
        set(value) {
            field = value
            clientManager.maxClientConnections = value
        }

    /** Patch 40: Maximum inbound server connections. Default 10 (existing behavior).
     *  Also propagated to clientManager so its overall limit calculation stays consistent. */
    var maxServerConnections: Int = 10
        set(value) {
            field = value
            clientManager.maxServerConnections = value
            serverManager.maxServerConnections = value
        }

    /** Patch 50: Maximum total connections (client + server combined).
     *  When set to a value less than maxClientConnections + maxServerConnections,
     *  this acts as a tighter overall cap. Used to limit to 1 total connection
     *  during the join flow before lobby entry. */
    var maxTotalConnections: Int = Int.MAX_VALUE
        set(value) {
            field = value
            clientManager.maxTotalConnections = value
            serverManager.maxTotalConnections = value
        }

    /** Patch 41: Reserved peer prefix — one client slot reserved for this peer. */
    var reservedPeerPrefix: String = ""
        set(value) {
            field = value
            clientManager.reservedPeerPrefix = value
        }

    /** Patch 54: Host peer prefix for relay filtering. */
    var hostPeerPrefix: String = ""
        set(value) {
            field = value
            packetBroadcaster.hostPeerPrefix = value
        }

    /** Patch 39: Switch to mesh-maintenance scan mode (BALANCED/AGGRESSIVE). */
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

    /** Patch 88b: Callback fired after phantom ACL poll completes and advertising begins. */
    var onAdvertisingReady: (() -> Unit)? = null

    /** Patch 88b: Skip phantom ACL poll when host is Android. */
    var hostIsAndroid: Boolean = false

    /** Patch 90: Mark a peer as recently kicked so the scanner skips it. */
    fun addKickedPeer(peerID: String) = clientManager.addKickedPeer(peerID)

    init {
        powerManager.delegate = this
        // Debug settings observers removed (ui/ deleted in Patch 16).
        // Server and client are always enabled; connection limits use PowerManager defaults.
    }
    
    /**
     * Public entry point to re-evaluate connection limits.
     * Called by the app layer after connection limits change (e.g., host mode enabled)
     * to evict stale connections that exceed the new limits.
     */
    fun enforceConnectionLimits() {
        enforceStrictLimits()
    }

    /**
     * Centralized connection limit enforcement
     */
    private fun enforceStrictLimits() {
        if (!isActive) return

        try {
            // Patch 40: Use role-aware connection limits instead of PowerManager defaults.
            // Host: 0 client, up to maxServerConnections server.
            // Player: up to maxClientConnections client, up to maxServerConnections server.
            val maxClient = maxClientConnections
            val maxServer = maxServerConnections
            // Patch 50: maxTotalConnections caps the combined limit when set
            val maxOverall = minOf(maxClient + maxServer, maxTotalConnections)

            // Get list of connections to evict to satisfy all constraints
            // Patch 57: Pass hostPeerPrefix so the host connection is protected from eviction
            val toEvict = connectionTracker.getConnectionsToEvict(maxOverall, maxServer, maxClient, hostPeerPrefix)

            if (toEvict.isNotEmpty()) {
                val protectedCount = connectionTracker.getConnectedDevices().values.count { conn ->
                    hostPeerPrefix.isNotEmpty() && connectionTracker.addressPeerMap[conn.device.address]?.startsWith(hostPeerPrefix) == true
                }
                Log.i(TAG, "Enforcing limits (max: $maxOverall, s: $maxServer, c: $maxClient, hostMode: $hostMode) - evicting ${toEvict.size} connections (${protectedCount} host-protected)")

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
                Log.d(TAG, "GATT Server started (setup in progress)")

                // Patch 86: Wait for the GATT service to be fully registered
                // (onServiceAdded callback) before proceeding.
                val serviceOk = serverManager.awaitServiceReady(timeoutMs = 2000)
                if (!serviceOk) {
                    Log.e(TAG, "Patch 86: GATT service setup failed or timed out, continuing anyway")
                }

                // Patch 88: Single-pass stale connection check (best-effort).
                // maxServerConnections=10 ensures the real host connection can
                // subscribe alongside any phantom ACLs. iOS-side Patches 87/87b
                // reject phantom didConnect callbacks independently. No blocking
                // poll needed — just log and proceed.
                val staleDevices = try {
                    bluetoothManager.getConnectedDevices(
                        android.bluetooth.BluetoothProfile.GATT_SERVER
                    )
                } catch (_: Exception) { emptyList() }
                if (staleDevices.isEmpty()) {
                    Log.d(TAG, "Patch 88: No stale connections — ready to advertise")
                } else {
                    Log.i(TAG, "Patch 88: ${staleDevices.size} stale connection(s) at startup: " +
                            staleDevices.joinToString { it.address } +
                            " (proceeding — high maxServerConnections)")
                }

                // Patch 88: Start advertising now that the radio is clean.
                serverManager.beginAdvertising()
                onAdvertisingReady?.invoke() // Patch 88b

                delay(100) // Brief settle time
                enforceStrictLimits()

                // Patch 73: Skip client manager when no outbound connections allowed.
                // In star topology: host has maxClient=9 (starts client), player has maxClient=0 (skips).
                if (maxClientConnections <= 0) {
                    Log.i(TAG, "Patch 73: maxClientConnections=0, skipping GATT Client (server-only)")
                } else {
                    if (!clientManager.start()) {
                        Log.e(TAG, "Failed to start client manager")
                        this@BluetoothConnectionManager.isActive = false
                        return@launch
                    }
                    Log.d(TAG, "GATT Client started")
                }

                Log.i(TAG, "Bluetooth services started successfully (hostMode=$hostMode)")
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

        // Patch 64: Stop server FIRST so server-side GATT registrations release
        // their hold on ACL links. Then stop clients — with no server registration
        // keeping ACLs alive, the client disconnect+close+poll can fully tear them
        // down. Previously client stopped first, but the server-side registration
        // kept ACLs alive through the client's 2-second poll, leaving zombie links
        // that blocked rejoining (Status 133).
        Log.d(TAG, "Stopping server then client for clean ACL teardown...")
        serverManager.stop()
        clientManager.stop()
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
    

    /** Patch 48: Stop BLE advertising only (keeps GATT server and connections alive). */
    fun stopBleAdvertising() { serverManager.stopBleAdvertising() }

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

    /** Patch 59/61: Disconnect a specific peer by peer ID. Reverse-lookups the BLE address
     *  from addressPeerMap, then disconnects both GATT client and server connections.
     *  Patch 61 added server-side disconnect to ensure the host tears down inbound connections
     *  from departed peers, preventing stale ACL links that block reconnection. */
    /** Look up the BLE MAC address for a peer ID. Returns null if not in addressPeerMap. */
    fun getMacForPeer(peerId: String): String? {
        return addressPeerMap.entries.find { it.value == peerId }?.key
    }

    /** Disconnect a device by MAC address directly, bypassing addressPeerMap lookup.
     *  Used when the MAC was captured before a LEAVE packet could clear the mapping. */
    fun disconnectByAddress(address: String) {
        val conn = connectionTracker.getConnectedDevices()[address]
        if (conn != null) {
            if (conn.isClient) {
                try { conn.gatt?.disconnect() } catch (_: Exception) { }
                try { conn.gatt?.close() } catch (_: Exception) { }
                // Patch 90: Poll until BLE controller confirms client ACL released.
                // Same pattern as Patch 89 in disconnectPeer().
                try {
                    val device = bluetoothManager.adapter.getRemoteDevice(address)
                    val deadline = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < deadline) {
                        val state = bluetoothManager.getConnectionState(device, android.bluetooth.BluetoothProfile.GATT)
                        if (state != android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            Log.i(TAG, "Patch 90: Client ACL released for $address")
                            break
                        }
                        Thread.sleep(50)
                    }
                } catch (_: Exception) { }
            } else {
                serverManager.disconnectDevice(conn.device)
            }
            Log.i(TAG, "Patch 65b: Disconnected device at $address (client=${conn.isClient})")
        } else {
            // Connection already cleaned up by LEAVE handler, but try server cancel anyway
            try {
                val device = bluetoothManager.adapter.getRemoteDevice(address)
                serverManager.disconnectDevice(device)
                Log.i(TAG, "Patch 65b: Force-cancelled server connection at $address (no tracker entry)")
            } catch (e: Exception) {
                Log.w(TAG, "Patch 65b: Failed to cancel connection at $address: ${e.message}")
            }
        }
        // Patch 76: Always cancel the server-side connection too. When a client
        // connection shares an ACL link with a phantom server connection (e.g. from
        // Patch 58 rejection), disconnecting only the client side leaves the server
        // phantom in the BLE stack, poisoning future outbound connections.
        if (conn?.isClient == true) {
            try {
                val device = bluetoothManager.adapter.getRemoteDevice(address)
                serverManager.disconnectDevice(device)
            } catch (_: Exception) { }
        }
        // Always clean up tracker + subscribedDevices regardless of which branch ran.
        // The LEAVE handler may have already removed from connectedDevices, but
        // subscribedDevices might still contain the device — and that's what drives
        // GATT notification delivery that keeps the ACL alive.
        connectionTracker.cleanupDeviceConnection(address)

        // Audit + evict phantoms
        try {
            val stackDevices = bluetoothManager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER)
            val stackMACs = stackDevices.joinToString(", ") { it.address }
            val trackerCount = connectionTracker.getConnectedDevices().size
            val subscribedCount = connectionTracker.getSubscribedDevices().size
            Log.i(TAG, "Patch 68: Post-disconnect audit — BLE stack: ${stackDevices.size} devices ($stackMACs), tracker: $trackerCount, subscribed: $subscribedCount")

            // Patch 76: If the BLE stack still reports devices that our tracker doesn't
            // know about, they are phantoms left over from a shared ACL link (e.g. Patch 58
            // cancelConnection on the server side while the client side kept the ACL alive).
            // These phantoms poison the BLE stack and cause all subsequent outbound GATT
            // connections to fail with status 133. Evict them now.
            val trackedAddresses = connectionTracker.getConnectedDevices().keys
            val phantomDevices = stackDevices.filter { it.address !in trackedAddresses }
            if (phantomDevices.isNotEmpty()) {
                Log.i(TAG, "Patch 76: Evicting ${phantomDevices.size} phantom devices from BLE stack")
                for (device in phantomDevices) {
                    Log.d(TAG, "Patch 76: Evicting phantom device ${device.address}")
                    serverManager.disconnectDevice(device)
                }
            }
        } catch (_: Exception) { }
    }

    fun disconnectPeer(peerId: String) {
        val address = addressPeerMap.entries.find { it.value == peerId }?.key ?: run {
            Log.i(TAG, "Patch 59/61: No addressPeerMap entry for departed peer ${peerId.take(8)}")
            return
        }
        val conn = connectionTracker.getConnectedDevices()[address]
        if (conn != null) {
            if (conn.isClient) {
                // Client-side (outbound): disconnect + close GATT client
                try { conn.gatt?.disconnect() } catch (_: Exception) { }
                try { conn.gatt?.close() } catch (_: Exception) { }
                // Patch 89: Poll until the BLE controller confirms the client ACL
                // is released. Without this, the stale ACL persists on Tensor G4
                // and all subsequent connectGatt() calls to the returning player
                // fail with status 133. Same pattern as Patch 58b in clientManager.stop().
                try {
                    val device = bluetoothManager.adapter.getRemoteDevice(address)
                    val deadline = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < deadline) {
                        val state = bluetoothManager.getConnectionState(device, android.bluetooth.BluetoothProfile.GATT)
                        if (state != android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            Log.i(TAG, "Patch 89: Client ACL released for $address")
                            break
                        }
                        Thread.sleep(50)
                    }
                } catch (_: Exception) { }
            } else {
                // Server-side (inbound): cancel connection via GATT server
                serverManager.disconnectDevice(conn.device)
            }
            // Patch 76: Also cancel the server side if this was a client connection
            if (conn.isClient) {
                try {
                    val device = bluetoothManager.adapter.getRemoteDevice(address)
                    serverManager.disconnectDevice(device)
                } catch (_: Exception) { }
            }
            connectionTracker.cleanupDeviceConnection(address)
            Log.i(TAG, "Patch 59/61: Disconnected departed peer ${peerId.take(8)} at $address (client=${conn.isClient})")
        } else {
            Log.i(TAG, "Patch 59/61: No active connection for departed peer ${peerId.take(8)} at $address")
        }
        // Audit: log what the BLE stack thinks is still connected after our disconnect
        try {
            val stackDevices = bluetoothManager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT_SERVER)
            val stackMACs = stackDevices.joinToString(", ") { it.address }
            val trackerCount = connectionTracker.getConnectedDevices().size
            Log.i(TAG, "Patch 59/61: Post-disconnect audit — BLE stack: ${stackDevices.size} devices ($stackMACs), tracker: $trackerCount devices")

            // Patch 76: Evict phantom devices (see disconnectByAddress for full explanation)
            val trackedAddresses = connectionTracker.getConnectedDevices().keys
            val phantomDevices = stackDevices.filter { it.address !in trackedAddresses }
            if (phantomDevices.isNotEmpty()) {
                Log.i(TAG, "Patch 76: Evicting ${phantomDevices.size} phantom devices from BLE stack")
                for (device in phantomDevices) {
                    Log.d(TAG, "Patch 76: Evicting phantom device ${device.address}")
                    serverManager.disconnectDevice(device)
                }
            }
        } catch (_: Exception) { }
    }

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
