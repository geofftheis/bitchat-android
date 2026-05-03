package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*
import java.util.concurrent.ConcurrentHashMap
// DebugSettingsManager and DebugScanResult removed (ui/ deleted in Patch 16).
// All references below replaced with inline defaults.

/**
 * Manages GATT client operations, scanning, and client-side connections
 */
class BluetoothGattClientManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val serviceUuid: UUID = com.bitchat.android.util.AppConstants.Mesh.Gatt.SERVICE_UUID
) {
    
    companion object {
        private const val TAG = "BluetoothGattClientManager"
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    /**
     * Public: Connect to a device by MAC address (for debug UI)
     */
    fun connectToAddress(deviceAddress: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        return if (device != null) {
            val rssi = connectionTracker.getBestRSSI(deviceAddress) ?: -50
            connectToDevice(device, rssi)
            true
        } else {
            Log.w(TAG, "connectToAddress: No device for $deviceAddress")
            false
        }
    }

    // Scan management
    private var scanCallback: ScanCallback? = null
    
    // Scan rate limiting to prevent "scanning too frequently" errors
    private var lastScanStartTime = 0L
    private var lastScanStopTime = 0L
    private var isCurrentlyScanning = false
    // Half-Wit Patch 37: Reduced from 5000ms to 2000ms. The 5s limit was overly
    // conservative and wasted scan budget headroom during cancel-and-rejoin cycles,
    // causing Android devices to stall on "Connecting..." for 10-20 seconds.
    private val scanRateLimit = 2000L // Minimum 2 seconds between scan start attempts
    
    // RSSI monitoring state
    private var rssiMonitoringJob: Job? = null
    
    // State management
    private var isActive = false

    // Patch 51: Track GATT objects from connectGatt() so they can be closed if the
    // connection never completes. Without this, the BluetoothGatt is a local variable
    // that goes out of scope, but the underlying GATT client registration in the BT
    // stack persists forever, leaking conn_ids.
    private val pendingGattClients = ConcurrentHashMap<String, BluetoothGatt>()

    // Patch 95: Per-address one-shot waiters completed when the GATT client callback
    // emits STATE_DISCONNECTED. Callers that initiate a disconnect use this to hold
    // gatt.close() until the BLE stack has actually emitted the disconnect callback,
    // giving the controller time to transmit LL_TERMINATE_IND. Calling close() too
    // early causes the peer's BLE controller to hold a phantom ACL, which poisons
    // subsequent connectGatt() calls on Tensor-class controllers with status 133.
    private val disconnectWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // Patch 96: Per-address one-shot waiters completed when the GATT client callback
    // emits onDescriptorWrite for the CCCD. Used by unsubscribeAndAwait() to write
    // DISABLE_NOTIFICATION_VALUE before disconnecting, so the peripheral's GATT
    // server knows to tear down its server-side subscription state. Without this,
    // the peer's controller keeps the ACL slot pinned after the host disconnects,
    // causing server-side phantom connections on rejoin.
    private val descriptorWriteWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Patch 39: Mesh-maintenance mode uses BALANCED/AGGRESSIVE scan settings instead of aggressive LOW_LATENCY.
    // Host devices start in this mode from transport init; joiners switch to it after joining.
    var meshMaintenanceMode = false

    // Patch 40: Configurable connection limits set by the app layer.
    var maxClientConnections: Int = 10
    var maxServerConnections: Int = 10
    // Patch 50: Overall cap on total connections (client + server combined).
    var maxTotalConnections: Int = Int.MAX_VALUE

    // Patch 41: Reserved slot — if set, one client connection slot is reserved for
    // a peer whose peerID starts with this prefix. Non-matching peers can only fill
    // (maxClientConnections - 1) slots until the reserved peer is connected.
    var reservedPeerPrefix: String = ""

    // Patch 90: Recently kicked peer IDs — skip scan results for these peers
    // for 60 seconds to avoid wasting connectGatt() attempts while the stale
    // ACL is still alive (which causes status 133 errors).
    private val recentlyKickedPeers = mutableMapOf<String, Long>()

    // Peer IDs seen during this game session. Persists through disconnects so that
    // a reconnecting player whose Samsung rotated its BLE address is recognised and
    // bypasses the RSSI filter and per-device cooldown (same role as iOS's
    // knownPeerPeripherals check in didDiscoverPeripheral).
    private val knownPlayerPeerIds = ConcurrentHashMap.newKeySet<String>()

    fun addKickedPeer(peerID: String) {
        recentlyKickedPeers[peerID] = System.currentTimeMillis()
    }

    /**
     * Patch 96: Unsubscribe from the characteristic's CCCD and suspend until the
     * onDescriptorWrite callback confirms. Should be called BEFORE gatt.disconnect()
     * so the peripheral's GATT server tears down its subscription state cleanly.
     * Returns true if the write was acknowledged, false on timeout/failure.
     *
     * Without this, the peer's server side keeps the host listed as a subscriber
     * even after the ACL goes away, which on Tensor controllers pins the link
     * slot and blocks subsequent inbound connections from a new host.
     */
    suspend fun unsubscribeAndAwait(gatt: BluetoothGatt, timeoutMs: Long = 300): Boolean {
        val address = gatt.device.address
        val conn = connectionTracker.getDeviceConnection(address) ?: return false
        val characteristic = conn.characteristic ?: return false
        val descriptor = characteristic.getDescriptor(com.bitchat.android.util.AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
            ?: return false

        val deferred = CompletableDeferred<Boolean>()
        descriptorWriteWaiters.put(address, deferred)?.complete(false)
        return try {
            // Turn off local notification routing first so stray incoming PDUs aren't
            // delivered after we tear down. Then ask the peer to stop sending via CCCD.
            try { gatt.setCharacteristicNotification(characteristic, false) } catch (_: Exception) { }
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            val queued = try { gatt.writeDescriptor(descriptor) } catch (_: Exception) { false }
            if (!queued) {
                Log.w(TAG, "Patch 96: writeDescriptor(DISABLE) returned false for $address")
                return false
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            descriptorWriteWaiters.remove(address, deferred)
        }
    }

    /**
     * Patch 95: Issue gatt.disconnect() and suspend until the GATT client callback
     * emits STATE_DISCONNECTED for this device, or [timeoutMs] elapses. Returns
     * true if the callback fired, false on timeout.
     *
     * After this returns, callers are safe to invoke gatt.close(): the controller
     * has had a chance to transmit LL_TERMINATE_IND over the air. Calling close()
     * before the callback drops the termination PDU and leaves a phantom ACL on
     * the peer, which on Tensor G2/G4 causes subsequent connectGatt() to that
     * peer to fail with status 133 for 15-25 seconds.
     */
    suspend fun disconnectAndAwait(gatt: BluetoothGatt, timeoutMs: Long = 500): Boolean {
        val address = gatt.device.address
        val deferred = CompletableDeferred<Unit>()
        // Replace any previous waiter (shouldn't happen in practice — one caller per peer).
        disconnectWaiters.put(address, deferred)?.complete(Unit)
        return try {
            try {
                gatt.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Patch 95: gatt.disconnect() threw for $address: ${e.message}")
                deferred.complete(Unit) // no callback coming
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() } != null
        } finally {
            // Remove only if this is still the registered waiter (don't wipe a
            // replacement from a concurrent caller).
            disconnectWaiters.remove(address, deferred)
        }
    }

    // Patch 42: Callback invoked when a GATT write to a remote server completes.
    // Patch 42 write ACK callback removed — using WRITE_TYPE_NO_RESPONSE (fire-and-forget).

    /**
     * Start client manager
     */
    fun start(): Boolean {
        // Debug gattClientEnabled check removed (ui/ deleted in Patch 16); always enabled.

        if (isActive) {
            Log.d(TAG, "GATT client already active; start is a no-op")
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
        
        if (bleScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return false
        }
        
        isActive = true
        
        connectionScope.launch {
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Using power-aware duty cycling")
            } else {
                startScanning()
            }
            
            // Patch 106: RSSI monitoring disabled. Half-Wit uses a star topology
            // with no relaying — connection-quality-based routing decisions
            // bitchat needs RSSI for don't apply. Each periodic readRemoteRssi()
            // is an unnecessary GATT operation that adds radio chatter (and
            // contends with disconnect/CCCD-write traffic during teardown).
            // The function is preserved for upstream alignment; just not called.
            // startRSSIMonitoring()
        }
        
        return true
    }
    
    /**
     * Stop client manager
     */
    fun stop() {
        if (!isActive) {
            // Idempotent stop
            stopScanning()
            stopRSSIMonitoring()
            Log.i(TAG, "GATT client manager stopped (already inactive)")
            return
        }

        isActive = false
        knownPlayerPeerIds.clear()

        // Stop synchronously so cleanup isn't skipped if connectionScope
        // is cancelled before this coroutine executes.
        // Patch 58b: disconnect AND close established GATT clients.
        // disconnect() alone is a request the BLE stack may not honor — the ACL
        // link can persist for 30+ seconds, blocking new connectGatt() calls to the
        // same device (status 133). close() releases the GATT client resources so
        // the stack can tear down the ACL. Pending (incomplete) clients already get
        // disconnect+close below (Patch 51); this extends the same pattern to
        // established connections.
        val devicesToCheck = mutableListOf<BluetoothDevice>()
        try {
            val conns = connectionTracker.getConnectedDevices().values.filter { it.isClient && it.gatt != null }
            conns.forEach { dc ->
                devicesToCheck.add(dc.device)
                try { dc.gatt?.disconnect() } catch (_: Exception) { }
                try { dc.gatt?.close() } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Patch 51: Close any GATT clients that never completed connection.
        val pendingCount = pendingGattClients.size
        if (pendingCount > 0) {
            Log.i(TAG, "Closing $pendingCount pending GATT clients that never completed")
            pendingGattClients.values.forEach { gatt ->
                try { gatt.disconnect() } catch (_: Exception) { }
                try { gatt.close() } catch (_: Exception) { }
            }
            pendingGattClients.clear()
        }

        // Patch 58b: Poll until all GATT client devices are actually disconnected
        // at the BLE controller level (or timeout). disconnect()+close() return
        // immediately but the ACL link can persist — the BLE controller may still
        // consider the device connected and refuse new connectGatt() calls to the
        // same physical device (status 133). Polling getConnectionState() ensures
        // the ACL is torn down before the next transport session starts.
        if (devicesToCheck.isNotEmpty()) {
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                val allDisconnected = devicesToCheck.all { device ->
                    try {
                        bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) != BluetoothProfile.STATE_CONNECTED
                    } catch (_: Exception) { true }
                }
                if (allDisconnected) {
                    Log.i(TAG, "Patch 58b: All GATT clients confirmed disconnected")
                    break
                }
                Thread.sleep(50)
            }
        }

        stopScanning()
        stopRSSIMonitoring()
        Log.i(TAG, "GATT client manager stopped")
    }
    
    /**
     * Handle scan state changes from power manager
     */
    fun onScanStateChanged(shouldScan: Boolean) {
        if (shouldScan) {
            startScanning()
        } else {
            stopScanning()
        }
    }
    
    /**
     * Start periodic RSSI monitoring for all client connections
     */
    private fun startRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = connectionScope.launch {
            while (isActive) {
                try {
                    // Request RSSI from all client connections
                    val connectedDevices = connectionTracker.getConnectedDevices()
                    connectedDevices.values.filter { it.isClient && it.gatt != null }.forEach { deviceConn ->
                        try {
                            Log.d(TAG, "Requesting RSSI from ${deviceConn.device.address}")
                            deviceConn.gatt?.readRemoteRssi()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to request RSSI from ${deviceConn.device.address}: ${e.message}")
                        }
                    }
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in RSSI monitoring: ${e.message}")
                    delay(AppConstants.Mesh.RSSI_UPDATE_INTERVAL_MS)
                }
            }
        }
    }
    
    /**
     * Stop RSSI monitoring
     */
    private fun stopRSSIMonitoring() {
        rssiMonitoringJob?.cancel()
        rssiMonitoringJob = null
    }
    
    /**
     * Start scanning with rate limiting
     */
    @Suppress("DEPRECATION")
    private fun startScanning() {
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null || !isActive) return
        
        // Rate limit scan starts to prevent "scanning too frequently" errors
        val currentTime = System.currentTimeMillis()
        if (isCurrentlyScanning) {
            Log.d(TAG, "Scan already in progress, skipping start request")
            return
        }
        
        val timeSinceLastStart = currentTime - lastScanStartTime
        if (timeSinceLastStart < scanRateLimit) {
            val remainingWait = scanRateLimit - timeSinceLastStart
            Log.w(TAG, "Scan rate limited: need to wait ${remainingWait}ms before starting scan")
            
            // Schedule delayed scan start
            connectionScope.launch {
                delay(remainingWait)
                if (isActive && !isCurrentlyScanning) {
                    startScanning()
                }
            }
            return
        }
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        
        val scanFilters = listOf(scanFilter) 
        
        Log.d(TAG, "Starting BLE scan with target service UUID: ${serviceUuid}")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Log.d(TAG, "Scan result received: ${result.device.address}")
                handleScanResult(result)
            }
            
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "Batch scan results received: ${results.size} devices")
                results.forEach { result ->
                    handleScanResult(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isCurrentlyScanning = false
                lastScanStopTime = System.currentTimeMillis()
                
                when (errorCode) {
                    1 -> Log.e(TAG, "SCAN_FAILED_ALREADY_STARTED")
                    2 -> Log.e(TAG, "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED") 
                    3 -> Log.e(TAG, "SCAN_FAILED_INTERNAL_ERROR")
                    4 -> Log.e(TAG, "SCAN_FAILED_FEATURE_UNSUPPORTED")
                    5 -> Log.e(TAG, "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
                    6 -> {
                        Log.e(TAG, "SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
                        // Half-Wit Patch 38: Reduced retry from 10s to 2s. The 10s penalty
                        // was too harsh — Android's rate limiter window rolls continuously,
                        // so scan quota frees up well before 10s. The long wait caused
                        // Android devices joining iOS-hosted games to stall on "Connecting..."
                        // because the Android couldn't scan and iOS inbound discovery is slow.
                        Log.w(TAG, "Scan failed due to rate limiting - will retry after 2s")
                        connectionScope.launch {
                            delay(2000) // Wait 2 seconds before retrying
                            if (isActive) {
                                startScanning()
                            }
                        }
                    }
                    else -> Log.e(TAG, "Unknown scan failure code: $errorCode")
                }
            }
        }
        
        try {
            lastScanStartTime = currentTime
            isCurrentlyScanning = true
            
            val scanSettings = if (meshMaintenanceMode) powerManager.getMeshMaintenanceScanSettings() else powerManager.getScanSettings()
            bleScanner.startScan(scanFilters, scanSettings, scanCallback)
            Log.d(TAG, "BLE scan started successfully (meshMaintenance=$meshMaintenanceMode)")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan: ${e.message}")
            isCurrentlyScanning = false
        }
    }
    
    /**
     * Stop scanning
     */
    @Suppress("DEPRECATION")
    fun stopScanning() {
        if (!permissionManager.hasBluetoothPermissions() || bleScanner == null) return
        
        if (isCurrentlyScanning) {
            try {
                scanCallback?.let { 
                    bleScanner.stopScan(it)
                    Log.d(TAG, "BLE scan stopped successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan: ${e.message}")
            }
            
            isCurrentlyScanning = false
            lastScanStopTime = System.currentTimeMillis()
        }
    }
    
    /**
     * Handle scan result and initiate connection if appropriate
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val deviceAddress = device.address
        val scanRecord = result.scanRecord
        
        // CRITICAL: Only process devices that have our service UUID
        val hasOurService = scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true
        if (!hasOurService) {
            return
        }

        // Try to extract peerID from Service Data (if available) for stable identity
        val serviceData = scanRecord?.getServiceData(ParcelUuid(serviceUuid))
        val peerID = if (serviceData != null && serviceData.size >= 8) {
            serviceData.joinToString("") { "%02x".format(it) }
        } else {
            // Patch 43: Fallback — extract peerID prefix from iOS local name format
            // iOS hosts advertise "H" + 2-hex metadata + 8-hex peerID prefix (11 chars total)
            val deviceName = scanRecord?.deviceName
            if (deviceName != null && deviceName.length == 11 && deviceName.startsWith("H")) {
                try {
                    deviceName.substring(3, 11).lowercase()
                } catch (_: Exception) { null }
            } else {
                null
            }
        }

        if (peerID != null) {
            // Patch 90: Skip recently kicked peers to avoid status 133 errors
            // from stale ACLs. The returning player will have a new peerID.
            val kickedAt = recentlyKickedPeers[peerID]
            if (kickedAt != null) {
                if (System.currentTimeMillis() - kickedAt < 60_000) {
                    return // Skip — recently kicked, ACL may still be stale
                } else {
                    recentlyKickedPeers.remove(peerID) // Expired
                }
            }

            // Log.v(TAG, "Found peerID $peerID in scan record for $deviceAddress")
            if (connectionTracker.isPeerConnected(peerID)) {
                 Log.d(TAG, "Deduplication: Peer $peerID is already connected (ignoring $deviceAddress)")
                 return
            }
        }

        // Log.d(TAG, "Received scan result from $deviceAddress - already connected: ${connectionTracker.isDeviceConnected(deviceAddress)}")
        
        // Store RSSI from scan results for later use (especially for server connections)
        connectionTracker.updateScanRSSI(deviceAddress, rssi)

        // Recognize a previously-seen game peer reconnecting after BLE address rotation.
        // knownPlayerPeerIds persists through disconnects, so when a Samsung device rotates
        // its random address the peerID (from service data) still matches. Bypass RSSI
        // filter and cooldown for these peers — they are active game participants.
        val isKnownPlayer = peerID != null && knownPlayerPeerIds.contains(peerID)

        // Power-aware RSSI filtering
        if (rssi < powerManager.getRSSIThreshold() && !isKnownPlayer) {
            Log.d(TAG, "Skipping device $deviceAddress due to weak signal: $rssi < ${powerManager.getRSSIThreshold()}")
            return
        }

        // Check if already connected OR already attempting to connect
        if (connectionTracker.isDeviceConnected(deviceAddress)) {
            return
        }

        // Check if connection attempt is allowed
        // Patch 43: Bypass cooldown for the reserved (host) peer — join reliability is critical.
        // Also bypass for known game players reconnecting after address rotation.
        val isReservedDevice = (reservedPeerPrefix.isNotEmpty() && peerID != null && peerID.startsWith(reservedPeerPrefix)) || isKnownPlayer
        if (!isReservedDevice && !connectionTracker.isConnectionAttemptAllowed(deviceAddress)) {
            Log.d(TAG, "Connection to $deviceAddress not allowed due to recent attempts")
            return
        }
        
        // Patch 40: Check configurable client connection limit instead of PowerManager default.
        val maxClient = maxClientConnections
        // Patch 50: maxTotalConnections caps the combined limit when set
        val maxOverall = minOf(maxClient + maxServerConnections, maxTotalConnections)

        // Patch 41/53a: Reserved slot logic — reserve one client slot for the host peerID.
        // Non-host peers can only fill (maxClient - 1) slots until the reserved peer connects.
        // Patch 53a: Removed maxClient > 1 guard so reservation works with a single
        // slot (blocks non-host peers entirely pre-lobby).
        val effectiveMaxClient = if (reservedPeerPrefix.isNotEmpty()) {
            val isReservedPeer = peerID != null && peerID.startsWith(reservedPeerPrefix)
            val hostAlreadyConnected = connectionTracker.addressPeerMap.values.any { it.startsWith(reservedPeerPrefix) }
            if (isReservedPeer || hostAlreadyConnected) {
                maxClient // Full budget for the reserved peer, or if host already connected
            } else {
                maxClient - 1 // Reserve one slot for the host
            }
        } else {
            maxClient
        }

        if (!connectionTracker.canConnectAsClient(maxOverall, effectiveMaxClient)) {
            Log.d(TAG, "Client connection limit reached (overall: $maxOverall, client: $effectiveMaxClient)")
            return
        }

        // Add pending connection and start connection
        if (connectionTracker.addPendingConnection(deviceAddress)) {
            if (peerID != null) knownPlayerPeerIds.add(peerID)
            connectToDevice(device, rssi, peerID)
        }
    }
    
    /**
     * Connect to a device as GATT client
     */
    @Suppress("DEPRECATION")
    private fun connectToDevice(device: BluetoothDevice, rssi: Int, peerID: String? = null) {
        if (!permissionManager.hasBluetoothPermissions()) return

        val deviceAddress = device.address
        Log.i(TAG, "Connecting to bitchat device: $deviceAddress (peerID: $peerID)")
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "Client: Connection state change - Device: $deviceAddress, Status: $status, NewState: $newState")
                // Patch 51: GATT callback fired, so this is no longer a "lost" pending client.
                pendingGattClients.remove(deviceAddress)

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Client: Successfully connected to $deviceAddress. Requesting MTU...")
                    // Request a larger MTU. Must be done before any data transfer.
                    connectionScope.launch {
                        delay(200) // A small delay can improve reliability of MTU request.
                        gatt.requestMtu(517)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Client: Disconnected from $deviceAddress with error status $status")
                        if (status == 147) {
                            Log.e(TAG, "Client: Connection establishment failed (status 147) for $deviceAddress")
                        }
                    } else {
                        Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                    }

                    // Always clean up tracker entry regardless of error status.
                    // Previously only called on clean disconnect, leaving stale entries
                    // that counted against the connection limit.
                    connectionTracker.cleanupDeviceConnection(deviceAddress)

                    // Notify higher layers about device disconnection to update direct flags
                    delegate?.onDeviceDisconnected(gatt.device)

                    // Patch 95: If an explicit disconnectAndAwait() caller is waiting for
                    // this callback, signal it so the caller can invoke gatt.close() once
                    // LL_TERMINATE_IND has been emitted. If no waiter is registered this
                    // was a spontaneous disconnect (peer-initiated, radio error, etc.) —
                    // fall back to the legacy delayed-close path so the GATT client slot
                    // doesn't leak.
                    val waiter = disconnectWaiters.remove(deviceAddress)
                    if (waiter != null) {
                        waiter.complete(Unit)
                    } else {
                        // Close GATT with NonCancellable so scope cancellation can't prevent it.
                        // Without close(), Android leaks GATT client slots.
                        connectionScope.launch(kotlinx.coroutines.NonCancellable) {
                            delay(500) // Brief delay after disconnect before close
                            try {
                                gatt.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error closing GATT: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val deviceAddress = gatt.device.address
                Log.i(TAG, "Client: MTU changed for $deviceAddress to $mtu with status $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU successfully negotiated for $deviceAddress. Discovering services.")
                    
                    // Now that MTU is set, connection is fully ready.
                    val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                        device = gatt.device,
                        gatt = gatt,
                        rssi = rssi,
                        isClient = true,
                        peerID = peerID // Store the peerID discovered during scan
                    )
                    connectionTracker.addDeviceConnection(deviceAddress, deviceConn)
                    
                    // Start service discovery only AFTER MTU is set.
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress with status: $status. Disconnecting.")
                    //connectionTracker.removePendingConnection(deviceAddress)
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {                
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(serviceUuid)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                                val updatedConn = deviceConn.copy(characteristic = characteristic)
                                connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                                Log.d(TAG, "Client: Updated device connection with characteristic for $deviceAddress")
                            }
                            
                            // Patch 40: Subscribe to indications (confirmed delivery) instead of
                            // unconfirmed notifications. setCharacteristicNotification enables
                            // local delivery for both notifications and indications.
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(AppConstants.Mesh.Gatt.DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                // Patch 40c: Write CCCD descriptor and wait for confirmed
                                // callback before declaring connection ready. A safety timeout
                                // disconnects if the callback never fires.
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                val writeOk = gatt.writeDescriptor(descriptor)
                                if (!writeOk) {
                                    Log.w(TAG, "Client: writeDescriptor returned false for $deviceAddress, disconnecting")
                                    gatt.disconnect()
                                } else {
                                    // Safety timeout: if onDescriptorWrite never fires, disconnect
                                    connectionScope.launch {
                                        delay(3000)
                                        if (connectionTracker.getDeviceConnection(deviceAddress)?.descriptorWriteConfirmed != true) {
                                            Log.w(TAG, "Client: Descriptor write timeout for $deviceAddress, disconnecting")
                                            gatt.disconnect()
                                        }
                                    }
                                }
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $deviceAddress")
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Required characteristic not found for $deviceAddress")
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Required service not found for $deviceAddress")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed with status $status for $deviceAddress")
                    gatt.disconnect()
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                Log.i(TAG, "Client: Received packet from ${gatt.device.address}, size: ${value.size} bytes")
                val packet = BitchatPacket.fromBinaryData(value)
                if (packet != null) {
                    val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    Log.d(TAG, "Client: Parsed packet type ${packet.type} from $peerID")
                    delegate?.onPacketReceived(packet, peerID, gatt.device)
                } else {
                    Log.w(TAG, "Client: Failed to parse packet from ${gatt.device.address}, size: ${value.size} bytes")
                    Log.w(TAG, "Client: Packet data: ${value.joinToString(" ") { "%02x".format(it) }}")
                }
            }
            
            // Patch 40c: Confirm indication subscription before declaring connection ready.
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val addr = gatt.device.address
                if (descriptor.uuid == AppConstants.Mesh.Gatt.DESCRIPTOR_UUID) {
                    // Patch 96: If an unsubscribeAndAwait() caller is waiting for
                    // this descriptor write, signal it regardless of whether this
                    // was an ENABLE or DISABLE write. The caller only issues the
                    // DISABLE path, so a signalled waiter here always means the
                    // disable was acknowledged.
                    val waiter = descriptorWriteWaiters.remove(addr)
                    if (waiter != null) {
                        waiter.complete(status == BluetoothGatt.GATT_SUCCESS)
                        return
                    }

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Client: Indication subscription confirmed for $addr")
                        connectionTracker.getDeviceConnection(addr)?.let { conn ->
                            connectionTracker.updateDeviceConnection(addr, conn.copy(descriptorWriteConfirmed = true))
                        }
                        delegate?.onDeviceConnected(device)
                    } else {
                        Log.w(TAG, "Client: Descriptor write failed (status=$status) for $addr, retrying...")
                        // Retry once — if it fails again the safety timeout will disconnect
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) {
                            Log.e(TAG, "Client: Retry writeDescriptor returned false for $addr, disconnecting")
                            gatt.disconnect()
                        }
                    }
                }
            }

            // Patch 42 onCharacteristicWrite removed — using WRITE_TYPE_NO_RESPONSE.

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                val deviceAddress = gatt.device.address
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: RSSI updated for $deviceAddress: $rssi dBm")
                    
                    // Update the connection tracker with new RSSI value
                    connectionTracker.getDeviceConnection(deviceAddress)?.let { deviceConn ->
                        val updatedConn = deviceConn.copy(rssi = rssi)
                        connectionTracker.updateDeviceConnection(deviceAddress, updatedConn)
                    }
                } else {
                    Log.w(TAG, "Client: Failed to read RSSI for $deviceAddress, status: $status")
                }
            }
        }
        
        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress with autoConnect=false")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
                // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
                // connectionTracker.removePendingConnection(deviceAddress)
            } else {
                Log.d(TAG, "Client: GATT connection initiated successfully for $deviceAddress")
                // Patch 51: Track this GATT so we can close() it if the connection
                // never completes (no onConnectionStateChange callback fires).
                pendingGattClients[deviceAddress] = gatt
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client: Exception connecting to $deviceAddress: ${e.message}")
            // keep the pending connection so we can avoid too many reconnections attempts, TODO: needs testing
            // connectionTracker.removePendingConnection(deviceAddress)
        }
    }
    
    /**
     * Patch 39: Switch to mesh-maintenance scan mode and restart scanning.
     * Uses BALANCED/AGGRESSIVE settings to keep the mesh healthy while reducing radio load.
     */
    fun switchToMeshMaintenanceMode() {
        meshMaintenanceMode = true
        if (!isActive) return
        Log.i(TAG, "Switching to mesh-maintenance scan mode (BALANCED/AGGRESSIVE)")
        restartScanning()
    }

    /**
     * Restart scanning for power mode changes
     */
    fun restartScanning() {
        if (!isActive) return
        
        connectionScope.launch {
            stopScanning()
            delay(1000) // Extra delay to avoid rate limiting
            
            if (powerManager.shouldUseDutyCycle()) {
                Log.i(TAG, "Switching to duty cycle scanning mode")
                // Duty cycle will handle scanning
            } else {
                Log.i(TAG, "Switching to continuous scanning mode")
                startScanning()
            }
        }
    }
} 
