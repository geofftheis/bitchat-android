
package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.util.Log
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.actor

/**
 * Handles packet broadcasting to connected devices using actor pattern for serialization
 * 
 * In Bluetooth Low Energy (BLE):
 *
 * Peripheral (server):
 * Advertises.
 * Accepts connections.
 * Hosts a GATT server.
 * Remote devices read/write/subscribe to characteristics.
 *
 *  Central (client):
 * Scans.
 * Initiates connections.
 * Hosts a GATT client.
 * Reads/writes to the peripheral’s characteristics.
 */
class BluetoothPacketBroadcaster(
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val fragmentManager: FragmentManager?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothPacketBroadcaster"
        private const val CLEANUP_DELAY = com.bitchat.android.util.AppConstants.Mesh.BROADCAST_CLEANUP_DELAY_MS
    }

    // Optional nickname resolver injected by higher layer (peerID -> nickname?)
    private var nicknameResolver: ((String) -> String?)? = null

    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
    }
    
    /**
     * Debug logging helper - can be easily removed/disabled for production
     */
    /**
     * Debug logging helper — DebugSettingsManager removed in Patch 16.
     * Kept as a no-op so call sites don't need changes.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun logPacketRelay(
        typeName: String,
        senderPeerID: String,
        senderNick: String?,
        incomingPeer: String?,
        incomingAddr: String?,
        toPeer: String?,
        toDeviceAddress: String,
        ttl: UByte,
        packetVersion: UByte = 1u,
        routeInfo: String? = null
    ) {
        // Debug logging removed (ui/ deleted in Patch 16)
    }
    
    // Data class to hold broadcast request information
    private data class BroadcastRequest(
        val routed: RoutedPacket,
        val gattServer: BluetoothGattServer?,
        val characteristic: BluetoothGattCharacteristic?,
        val completion: CompletableDeferred<Unit>? = null  // Patch 42: optional sync signal
    )
    
    // Actor scope for the broadcaster
    private val broadcasterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transferJobs = ConcurrentHashMap<String, Job>()

    // Patch 42: Per-device indication acknowledgment tracking.
    // When we send a BLE indication (confirm=true), the receiver must acknowledge it
    // before we can send the next one. This map holds a deferred per device address
    // that is completed when onNotificationSent fires from the GATT server callback.
    private val pendingIndicationAcks = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    // Patch 42: Per-device GATT write acknowledgment tracking (client→server path).
    // Same principle as indications: only one outstanding writeCharacteristic per connection.
    private val pendingWriteAcks = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    
    // SERIALIZATION: Actor to serialize all broadcast operations
    @OptIn(kotlinx.coroutines.ObsoleteCoroutinesApi::class)
    private val broadcasterActor = broadcasterScope.actor<BroadcastRequest>(
        capacity = Channel.UNLIMITED
    ) {
        Log.d(TAG, "🎭 Created packet broadcaster actor")
        try {
            for (request in channel) {
                broadcastSinglePacketInternal(request.routed, request.gattServer, request.characteristic)
                request.completion?.complete(Unit)  // Patch 42: signal sync callers
            }
        } finally {
            Log.d(TAG, "🎭 Packet broadcaster actor terminated")
        }
    }
    
    /**
     * Patch 42: Called by BluetoothGattServerManager when onNotificationSent fires.
     * Completes the pending deferred for the given device, unblocking the sender.
     */
    fun onIndicationAcknowledged(deviceAddress: String, status: Int) {
        pendingIndicationAcks.remove(deviceAddress)?.complete(status)
    }

    /**
     * Patch 42: Called by BluetoothGattClientManager when onCharacteristicWrite fires.
     * Completes the pending deferred for the given device, unblocking the sender.
     */
    fun onWriteAcknowledged(deviceAddress: String, status: Int) {
        pendingWriteAcks.remove(deviceAddress)?.complete(status)
    }

    /**
     * Patch 42: Synchronous broadcast that waits for the actor to finish processing
     * (including indication ack waiting) before returning. Used by the fragment loop
     * to ensure each fragment is fully delivered before sending the next.
     */
    suspend fun broadcastSinglePacketSync(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val completion = CompletableDeferred<Unit>()
        try {
            broadcasterActor.send(BroadcastRequest(routed, gattServer, characteristic, completion))
            completion.await()
        } catch (e: Exception) {
            Log.w(TAG, "Patch 42: Failed to send sync broadcast to actor: ${e.message}")
            broadcastSinglePacketInternal(routed, gattServer, characteristic)
        }
    }

    fun broadcastPacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val packet = routed.packet
        val isFile = packet.type == MessageType.FILE_TRANSFER.value
        if (isFile) {
            Log.d(TAG, "📤 Broadcasting FILE_TRANSFER: ${packet.payload.size} bytes")
        }
        // Prefer caller-provided transferId (e.g., for encrypted media), else derive for FILE_TRANSFER
        val transferId = routed.transferId ?: (if (isFile) sha256Hex(packet.payload) else null)
        // Check if we need to fragment
        if (fragmentManager != null) {
            val fragments = try {
                fragmentManager.createFragments(packet)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Fragment creation failed: ${e.message}", e)
                if (isFile) {
                    Log.e(TAG, "❌ File fragmentation failed for ${packet.payload.size} byte file")
                }
                return
            }
            if (fragments.size > 1) {
                if (isFile) {
                    Log.d(TAG, "🔀 File needs ${fragments.size} fragments")
                }
                Log.d(TAG, "Fragmenting packet into ${fragments.size} fragments")
                if (transferId != null) {
                    TransferProgressManager.start(transferId, fragments.size)
                }
                val job = connectionScope.launch {
                    var sent = 0
                    fragments.forEach { fragment ->
                        if (!isActive) return@launch
                        // If cancelled, stop sending remaining fragments
                        if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch
                        // Revert Patch 42: With notifications (fire-and-forget), a small
                        // delay between fragments is sufficient. The original 20ms was fine
                        // now that fragment sizes are correct (Patches 40,47,49).
                        broadcastSinglePacket(RoutedPacket(fragment, transferId = transferId), gattServer, characteristic)
                        delay(20)
                        if (transferId != null) {
                            sent += 1
                            TransferProgressManager.progress(transferId, sent, fragments.size)
                            if (sent == fragments.size) TransferProgressManager.complete(transferId, fragments.size)
                        }
                    }
                }
                if (transferId != null) {
                    transferJobs[transferId] = job
                    job.invokeOnCompletion { transferJobs.remove(transferId) }
                }
                return
            }
        }
        
        // Send single packet if no fragmentation needed
        if (transferId != null) {
            TransferProgressManager.start(transferId, 1)
        }
        broadcastSinglePacket(routed, gattServer, characteristic)
        if (transferId != null) {
            TransferProgressManager.progress(transferId, 1, 1)
            TransferProgressManager.complete(transferId, 1)
        }
    }

    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId) ?: return false
        job.cancel()
        return true
    }

    /**
     * Send a packet to a specific peer only, without broadcasting.
     * Returns true if a direct path was found and used.
     */
    fun sendPacketToPeer(
        routed: RoutedPacket,
        targetPeerID: String,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val packet = routed.packet
        val data = packet.toBinaryData() ?: return false
        if (data.size > 512) {
            Log.w(TAG, "Packet too large for direct send (${data.size} bytes), falling back to fragmented broadcast")
            broadcastPacket(routed, gattServer, characteristic)
            return true
        }
        val isFile = packet.type == MessageType.FILE_TRANSFER.value
        if (isFile) {
            Log.d(TAG, "📤 Broadcasting FILE_TRANSFER: ${packet.payload.size} bytes")
        }
        // Prefer caller-provided transferId (e.g., for encrypted media), else derive for FILE_TRANSFER
        val transferId = routed.transferId ?: (if (isFile) sha256Hex(packet.payload) else null)
        if (transferId != null) {
            TransferProgressManager.start(transferId, 1)
        }
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        // Prefer server-side subscriptions
        val serverTarget = connectionTracker.getSubscribedDevices()
            .firstOrNull { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (serverTarget != null) {
            if (notifyDevice(serverTarget, data, gattServer, characteristic)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, serverTarget.address, packet.ttl, packet.version, routeInfo)
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, 1, 1)
                    TransferProgressManager.complete(transferId, 1)
                }
                return true
            }
        }

        // Then client connections
        val clientTarget = connectionTracker.getConnectedDevices().values
            .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
        if (clientTarget != null) {
            if (writeToDeviceConn(clientTarget, data)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, clientTarget.device.address, packet.ttl, packet.version, routeInfo)
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, 1, 1)
                    TransferProgressManager.complete(transferId, 1)
                }
                return true
            }
        }

        return false
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }

    
    /**
     * Public entry point for broadcasting - submits request to actor for serialization
     */
    fun broadcastSinglePacket(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        // Submit broadcast request to actor for serialized processing
        broadcasterScope.launch {
            try {
                broadcasterActor.send(BroadcastRequest(routed, gattServer, characteristic))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send broadcast request to actor: ${e.message}")
                // Fallback to direct processing if actor fails
                broadcastSinglePacketInternal(routed, gattServer, characteristic)
            }
        }
    }

    /**
     * Targeted send to a specific peer (by peerID) if directly connected.
     * Returns true if sent to at least one matching connection.
     */
    fun sendToPeer(
        targetPeerID: String,
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        val packet = routed.packet
        val data = packet.toBinaryData() ?: return false
        if (data.size > 512) {
            Log.w(TAG, "Packet too large for direct send (${data.size} bytes), falling back to fragmented broadcast")
            broadcastPacket(routed, gattServer, characteristic)
            return true
        }
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }

        // Try server-side connections first
        val targetDevice = connectionTracker.getSubscribedDevices()
            .firstOrNull { connectionTracker.addressPeerMap[it.address] == targetPeerID }
        if (targetDevice != null) {
            if (notifyDevice(targetDevice, data, gattServer, characteristic)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, targetDevice.address, packet.ttl)
                return true
            }
        }

        // Try client-side connections next
        val targetConn = connectionTracker.getConnectedDevices().values
            .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == targetPeerID }
        if (targetConn != null) {
            if (writeToDeviceConn(targetConn, data)) {
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, targetPeerID, targetConn.device.address, packet.ttl)
                return true
            }
        }
        return false
    }
    
    /**
     * Internal broadcast implementation - runs in serialized actor context
     */
    private suspend fun broadcastSinglePacketInternal(
        routed: RoutedPacket,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        val packet = routed.packet
        val data = packet.toBinaryData() ?: return
        val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
        val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
        val incomingAddr = routed.relayAddress
        val incomingPeer = incomingAddr?.let { connectionTracker.addressPeerMap[it] }
        val senderNick = senderPeerID.let { pid -> nicknameResolver?.invoke(pid) }
        val route = packet.route
        val routeInfo = if (!route.isNullOrEmpty()) "routed: ${route.size} hops" else null

        // Source Routing for Originating Packets
        // If we are the sender and a source route is defined, we must send ONLY to the first hop.
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()
            Log.d(TAG, "Source Routing: Packet has explicit route, attempting to send to first hop: $firstHop")

            var sent = false

            // Try to find first hop in server connections (subscribedDevices)
            val serverTarget = connectionTracker.getSubscribedDevices()
                .firstOrNull { connectionTracker.addressPeerMap[it.address] == firstHop }
            
            if (serverTarget != null) {
                Log.d(TAG, "Source Routing: sending directly to first hop (server conn) $firstHop: ${serverTarget.address}")
                if (notifyDevice(serverTarget, data, gattServer, characteristic)) {
                    val toPeer = connectionTracker.addressPeerMap[serverTarget.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, serverTarget.address, packet.ttl, packet.version, routeInfo)
                    sent = true
                }
            }

            // Try to find first hop in client connections if not sent yet
            if (!sent) {
                val clientTarget = connectionTracker.getConnectedDevices().values
                    .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == firstHop }

                if (clientTarget != null) {
                    Log.d(TAG, "Source Routing: sending directly to first hop (client conn) $firstHop: ${clientTarget.device.address}")
                    if (writeToDeviceConn(clientTarget, data)) {
                        val toPeer = connectionTracker.addressPeerMap[clientTarget.device.address]
                        logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, clientTarget.device.address, packet.ttl, packet.version, routeInfo)
                        sent = true
                    }
                }
            }

            if (sent) return
            
            Log.w(TAG, "Source Routing: First hop $firstHop not connected. Falling back to standard broadcast logic.")
        }
        
        if (packet.recipientID != SpecialRecipients.BROADCAST) {
            val recipientID = packet.recipientID?.toHexString() ?: ""

            // Try to find the recipient in server connections (subscribedDevices)
            val targetDevice = connectionTracker.getSubscribedDevices()
                .firstOrNull { connectionTracker.addressPeerMap[it.address] == recipientID }
            
            // If found, send directly
            if (targetDevice != null) {
                Log.d(TAG, "Send packet type ${packet.type} directly to target device for recipient $recipientID: ${targetDevice.address}")
                if (notifyDevice(targetDevice, data, gattServer, characteristic)) {
                    val toPeer = connectionTracker.addressPeerMap[targetDevice.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, targetDevice.address, packet.ttl, packet.version, routeInfo)
                    return  // Sent, no need to continue
                }
            }

            // Try to find the recipient in client connections (connectedDevices)
            val targetDeviceConn = connectionTracker.getConnectedDevices().values
                .firstOrNull { connectionTracker.addressPeerMap[it.device.address] == recipientID }

            // If found, send directly
            if (targetDeviceConn != null) {
                Log.d(TAG, "Send packet type ${packet.type} directly to target client connection for recipient $recipientID: ${targetDeviceConn.device.address}")
                if (writeToDeviceConn(targetDeviceConn, data)) {
                    val toPeer = connectionTracker.addressPeerMap[targetDeviceConn.device.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, targetDeviceConn.device.address, packet.ttl, packet.version, routeInfo)
                    return  // Sent, no need to continue
                }
            }
        }

        // Else, continue with broadcasting to all devices
        val subscribedDevices = connectionTracker.getSubscribedDevices()
        val connectedDevices = connectionTracker.getConnectedDevices()
        
        Log.i(TAG, "Broadcasting packet v${packet.version} type ${packet.type} to ${subscribedDevices.size} server + ${connectedDevices.size} client connections")

        val senderID = packet.senderID.toHexString()
        
        // Send to server connections (devices connected to our GATT server)
        subscribedDevices.forEach { device ->
            if (device.address == routed.relayAddress) {
                Log.d(TAG, "Skipping broadcast to client back to relayer: ${device.address}")
                return@forEach
            }
            if (connectionTracker.addressPeerMap[device.address] == senderID) {
                Log.d(TAG, "Skipping broadcast to client back to sender: ${device.address}")
                return@forEach
            }
            val sent = notifyDevice(device, data, gattServer, characteristic)
            if (sent) {
                val toPeer = connectionTracker.addressPeerMap[device.address]
                logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, device.address, packet.ttl, packet.version, routeInfo)
            }
        }

        // Send to client connections (GATT servers we are connected to)
        connectedDevices.values.forEach { deviceConn ->
            if (deviceConn.isClient && deviceConn.gatt != null && deviceConn.characteristic != null) {
                if (deviceConn.device.address == routed.relayAddress) {
                    Log.d(TAG, "Skipping broadcast to server back to relayer: ${deviceConn.device.address}")
                    return@forEach
                }
                if (connectionTracker.addressPeerMap[deviceConn.device.address] == senderID) {
                    Log.d(TAG, "Skipping roadcast to server back to sender: ${deviceConn.device.address}")
                    return@forEach
                }
                val sent = writeToDeviceConn(deviceConn, data)
                if (sent) {
                    val toPeer = connectionTracker.addressPeerMap[deviceConn.device.address]
                    logPacketRelay(typeName, senderPeerID, senderNick, incomingPeer, incomingAddr, toPeer, deviceConn.device.address, packet.ttl, packet.version, routeInfo)
                }
            }
        }
    }
    
    /**
     * Send data to a single device (server->client) - fire-and-forget.
     * Used by sendToPeer and other non-fragment paths where ack waiting is not needed.
     */
    private fun notifyDevice(
        device: BluetoothDevice,
        data: ByteArray,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        return try {
            characteristic?.let { char ->
                char.value = data
                // Revert Patch 40b: Use fire-and-forget notifications (false) instead of
                // confirmed indications (true). The original packet drop issues were caused
                // by fragment size bugs (Patches 40,47,49) and connection tracking races
                // (Patches 44,45,46), not BLE notification unreliability. Indications
                // serialized all sends and caused 4-8x throughput reduction.
                val result = gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
                result
            } ?: false
        } catch (e: Exception) {
            // Log the error but do NOT remove the subscribed device or address mapping.
            // A single failed notification (e.g. oversized gossip packet) does not mean
            // the BLE connection is dead. Removing the subscription permanently kills the
            // server→client notification path with no way to recover, and removing the
            // addressPeerMap entry also cripples the client write path's peer attribution.
            // If the connection is truly gone, periodic cleanup will handle it.
            // See BITCHAT_PATCHES.md Patch 4.
            Log.w(TAG, "Error sending to server connection ${device.address}: ${e.message}")
            false
        }
    }

    /**
     * Patch 42: Send indication to device and wait for BLE-level acknowledgment.
     *
     * With BLE indications (confirm=true, Patch 40b), only one indication can be
     * outstanding per connection. If we send another before the previous is acked,
     * notifyCharacteristicChanged returns false and the data is silently dropped.
     * This method waits for the onNotificationSent callback (via pendingIndicationAcks)
     * before returning, ensuring reliable sequential delivery.
     */
    private suspend fun notifyDeviceAndAwaitAck(
        device: BluetoothDevice,
        data: ByteArray,
        gattServer: BluetoothGattServer?,
        characteristic: BluetoothGattCharacteristic?
    ): Boolean {
        repeat(4) { attempt ->
            val ackDeferred = CompletableDeferred<Int>()
            pendingIndicationAcks[device.address] = ackDeferred

            val sent = notifyDevice(device, data, gattServer, characteristic)
            if (sent) {
                // Wait for onNotificationSent callback (up to 500ms)
                val status = withTimeoutOrNull(500) { ackDeferred.await() }
                pendingIndicationAcks.remove(device.address)
                if (status != null) {
                    return true
                }
                Log.w(TAG, "Patch 42: Indication ack timeout for ${device.address} (attempt ${attempt + 1}/4)")
            } else {
                pendingIndicationAcks.remove(device.address)
                if (attempt < 3) {
                    // BLE stack busy (previous indication still pending) - wait and retry
                    Log.d(TAG, "Patch 42: BLE busy for ${device.address}, retrying (attempt ${attempt + 1}/4)")
                    delay(50)
                }
            }
        }
        Log.w(TAG, "Patch 42: Failed to deliver indication to ${device.address} after 4 attempts")
        return false
    }

    /**
     * Send data to a single device (client->server) - fire-and-forget.
     * Used by sendToPeer and other non-fragment paths where ack waiting is not needed.
     */
    private fun writeToDeviceConn(
        deviceConn: BluetoothConnectionTracker.DeviceConnection,
        data: ByteArray
    ): Boolean {
        return try {
            deviceConn.characteristic?.let { char ->
                char.value = data
                val result = deviceConn.gatt?.writeCharacteristic(char) ?: false
                result
            } ?: false
        } catch (e: Exception) {
            // Log the error but do NOT clean up the device connection.
            // Same rationale as notifyDevice() — see BITCHAT_PATCHES.md Patch 4.
            Log.w(TAG, "Error sending to client connection ${deviceConn.device.address}: ${e.message}")
            false
        }
    }

    /**
     * Patch 42: Send GATT write to device and wait for onCharacteristicWrite callback.
     *
     * Same principle as notifyDeviceAndAwaitAck but for the client→server path.
     * Android's BLE stack only allows one outstanding writeCharacteristic per connection.
     * Calling it again before onCharacteristicWrite fires returns false and silently drops
     * the data.
     */
    private suspend fun writeToDeviceConnAndAwaitAck(
        deviceConn: BluetoothConnectionTracker.DeviceConnection,
        data: ByteArray
    ): Boolean {
        val address = deviceConn.device.address
        repeat(4) { attempt ->
            val ackDeferred = CompletableDeferred<Int>()
            pendingWriteAcks[address] = ackDeferred

            val sent = writeToDeviceConn(deviceConn, data)
            if (sent) {
                val status = withTimeoutOrNull(500) { ackDeferred.await() }
                pendingWriteAcks.remove(address)
                if (status != null) {
                    return true
                }
                Log.w(TAG, "Patch 42: Write ack timeout for $address (attempt ${attempt + 1}/4)")
            } else {
                pendingWriteAcks.remove(address)
                if (attempt < 3) {
                    Log.d(TAG, "Patch 42: GATT write busy for $address, retrying (attempt ${attempt + 1}/4)")
                    delay(50)
                }
            }
        }
        Log.w(TAG, "Patch 42: Failed to deliver GATT write to $address after 4 attempts")
        return false
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Broadcaster Debug Info ===")
            appendLine("Broadcaster Scope Active: ${broadcasterScope.isActive}")
            appendLine("Actor Channel Closed: ${broadcasterActor.isClosedForSend}")
            appendLine("Connection Scope Active: ${connectionScope.isActive}")
        }
    }
    
    /**
     * Shutdown the broadcaster actor gracefully
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down BluetoothPacketBroadcaster actor")
        
        // Close the actor gracefully
        broadcasterActor.close()
        
        // Cancel the broadcaster scope
        broadcasterScope.cancel()
        
        Log.d(TAG, "BluetoothPacketBroadcaster shutdown complete")
    }
} 
