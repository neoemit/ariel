package com.thomaslamendola.ariel

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NearbyManager(private val context: Context, val myName: String) {
    private val tag = "NearbyManager"
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.thomaslamendola.ariel.PANIC_SERVICE"
    private val reconnectDelayMs = 5_000L

    private val connectionsClient = Nearby.getConnectionsClient(context)

    private val _peers = MutableStateFlow<Set<String>>(emptySet())
    val peers = _peers.asStateFlow()

    private val trustedFriends = mutableSetOf<String>()
    private val nameToEndpoint = mutableMapOf<String, String>()
    private val endpointToName = mutableMapOf<String, String>()
    private val pendingConnectionEndpointIds = mutableSetOf<String>()

    var onPanicReceived: ((senderName: String, escalationType: String, eventId: String) -> Unit)? = null
    var onAcknowledgeReceived: ((acknowledgerName: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var urgentTimeout: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var isRunning = false
    private var isAdvertising = false
    private var isDiscovering = false

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val message = payload.asBytes()?.decodeToString() ?: return
            Log.d(tag, "Payload from $endpointId: $message")

            when {
                message.startsWith("PANIC:") -> {
                    parsePanicPayload(message)?.let { (sender, escalationType, eventId) ->
                        onPanicReceived?.invoke(sender, escalationType, eventId)
                    }
                }

                message.startsWith("ACK:") -> onAcknowledgeReceived?.invoke(message.removePrefix("ACK:"))

                message.startsWith("PAIR:") -> {
                    val name = message.removePrefix("PAIR:").trim()
                    if (name.isBlank()) return
                    if (name.equals(myName, ignoreCase = true)) {
                        Log.w(tag, "Ignoring self PAIR payload from $endpointId")
                        return
                    }
                    if (!trustedFriends.contains(name)) {
                        Log.w(tag, "Ignoring PAIR from untrusted $name")
                        return
                    }
                    nameToEndpoint[name] = endpointId
                    endpointToName[endpointId] = name
                    Log.d(tag, "PAIR received: $name -> $endpointId")
                    toast("Paired with $name")
                    notifyPeerCount()
                }

                message == "PING" -> {
                    connectionsClient.sendPayload(endpointId, Payload.fromBytes("PONG".toByteArray()))
                }

                message == "PONG" -> {
                    Log.d(tag, "PONG from $endpointId - link alive")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val requesterName = info.endpointName.trim()
            Log.d(tag, "Connection initiated by $requesterName ($endpointId)")
            pendingConnectionEndpointIds.remove(endpointId)

            if (requesterName.equals(myName, ignoreCase = true)) {
                Log.w(tag, "Rejecting connection from self endpoint")
                connectionsClient.rejectConnection(endpointId)
                return
            }
            if (!trustedFriends.contains(requesterName)) {
                Log.w(tag, "Rejecting connection from untrusted peer: $requesterName")
                connectionsClient.rejectConnection(endpointId)
                return
            }

            endpointToName[endpointId] = requesterName
            nameToEndpoint[requesterName] = endpointId
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnectionEndpointIds.remove(endpointId)

            if (result.status.isSuccess) {
                _peers.value += endpointId
                endpointToName[endpointId]?.let { peerName ->
                    nameToEndpoint[peerName] = endpointId
                }
                notifyStatus("Connected!")
                toast("Connected to buddy!")
                cancelReconnect()

                connectionsClient.sendPayload(endpointId, Payload.fromBytes("PAIR:$myName".toByteArray()))
                notifyPeerCount()
            } else {
                val message = result.status.statusMessage ?: "unknown"
                Log.e(tag, "Connection failed to $endpointId: $message (code ${result.status.statusCode})")
                notifyStatus("Connection failed: $message")
                toast("Connection failed: $message")
                scheduleReconnect("connection_result_failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            _peers.value -= endpointId
            pendingConnectionEndpointIds.remove(endpointId)

            val disconnectedName = endpointToName.remove(endpointId)
            if (disconnectedName != null) {
                nameToEndpoint.remove(disconnectedName)
            } else {
                nameToEndpoint.entries.removeAll { it.value == endpointId }
            }

            notifyPeerCount()
            Log.d(tag, "Disconnected from $endpointId ($disconnectedName)")
            notifyStatus("Disconnected from $disconnectedName")
            toast("Buddy disconnected. Reconnecting...")
            scheduleReconnect("disconnected")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName.trim()
            Log.d(tag, "Discovered: $peerName ($endpointId), trustedFriends=$trustedFriends")

            if (peerName.isBlank() || peerName.equals(myName, ignoreCase = true)) return
            if (!trustedFriends.contains(peerName)) {
                Log.d(tag, "Ignoring $peerName - not in trusted list")
                return
            }
            if (_peers.value.contains(endpointId)) return
            if (pendingConnectionEndpointIds.contains(endpointId)) return
            if (nameToEndpoint[peerName]?.let { _peers.value.contains(it) } == true) return

            endpointToName[endpointId] = peerName
            if (!pendingConnectionEndpointIds.add(endpointId)) return

            notifyStatus("Connecting to $peerName...")
            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    Log.d(tag, "requestConnection to $peerName succeeded")
                }
                .addOnFailureListener { error ->
                    pendingConnectionEndpointIds.remove(endpointId)
                    endpointToName.remove(endpointId)
                    Log.e(tag, "requestConnection to $peerName FAILED: ${error.message}")
                    notifyStatus("Connection failed: ${error.message}")
                    scheduleReconnect("request_connection_failed")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(tag, "Endpoint lost: $endpointId")
            pendingConnectionEndpointIds.remove(endpointId)
            if (!_peers.value.contains(endpointId)) {
                endpointToName.remove(endpointId)
            }
            scheduleReconnect("endpoint_lost")
        }
    }

    private fun notifyStatus(status: String) {
        val intent = Intent("com.thomaslamendola.ariel.STATUS_UPDATE").apply {
            putExtra("STATUS", status)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    fun startPairing() {
        isRunning = true
        Log.d(tag, "startPairing() - myName=$myName trustedFriends=$trustedFriends")
        refreshConnectivityState("start_pairing")
        notifyPeerCount()
    }

    fun stopPairing() {
        isRunning = false
        cancelReconnect()
        urgentTimeout?.let { mainHandler.removeCallbacks(it) }
        urgentTimeout = null

        stopNearbyRadios()
        connectionsClient.stopAllEndpoints()
        pendingConnectionEndpointIds.clear()
        _peers.value = emptySet()
        notifyPeerCount()
    }

    private fun refreshConnectivityState(reason: String) {
        if (!isRunning) return

        if (trustedFriends.isEmpty()) {
            Log.d(tag, "refreshConnectivityState($reason): no trusted friends, stopping radios")
            cancelReconnect()
            stopNearbyRadios()
            pendingConnectionEndpointIds.clear()
            _peers.value = emptySet()
            notifyPeerCount()
            return
        }

        Log.d(tag, "refreshConnectivityState($reason): ensuring continuous nearby connectivity")
        startAdvertising()
        startDiscovery()
        if (_peers.value.isEmpty()) {
            scheduleReconnect("refresh_$reason")
        } else {
            cancelReconnect()
        }
    }

    private fun restartAdvertisingAndDiscovery(reason: String) {
        if (!isRunning || trustedFriends.isEmpty()) return

        Log.d(tag, "Restarting advertising/discovery ($reason)")
        stopNearbyRadios()
        startAdvertising()
        startDiscovery()
        notifyPeerCount()
    }

    private fun scheduleReconnect(reason: String) {
        if (!isRunning || trustedFriends.isEmpty()) return
        if (_peers.value.isNotEmpty()) {
            cancelReconnect()
            return
        }

        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (!isRunning || trustedFriends.isEmpty()) return@Runnable
            if (_peers.value.isNotEmpty()) {
                cancelReconnect()
                return@Runnable
            }
            Log.d(tag, "Reconnect tick ($reason)")
            restartAdvertisingAndDiscovery("reconnect_$reason")
            scheduleReconnect("loop")
        }
        mainHandler.postDelayed(reconnectRunnable!!, reconnectDelayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    fun enterUrgentMode(durationMs: Long = 120_000L) {
        if (!isRunning || trustedFriends.isEmpty()) return

        Log.d(tag, "Entering urgent mode for ${durationMs}ms")
        restartAdvertisingAndDiscovery("urgent_start")
        scheduleReconnect("urgent_start")

        urgentTimeout?.let { mainHandler.removeCallbacks(it) }
        urgentTimeout = Runnable {
            if (!isRunning || trustedFriends.isEmpty()) return@Runnable
            Log.d(tag, "Urgent mode reinforcement tick")
            restartAdvertisingAndDiscovery("urgent_reinforce")
            scheduleReconnect("urgent_reinforce")
        }
        mainHandler.postDelayed(urgentTimeout!!, durationMs.coerceAtLeast(5_000L))
    }

    private fun startAdvertising() {
        if (isAdvertising) return

        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(myName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                isAdvertising = true
                Log.d(tag, "Advertising started as '$myName'")
            }
            .addOnFailureListener { error ->
                isAdvertising = false
                Log.e(tag, "Advertising FAILED: ${error.message}", error)
                toast("Advertising failed: ${error.message}")
                scheduleReconnect("advertising_failed")
            }
    }

    private fun startDiscovery() {
        if (isDiscovering) return

        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                isDiscovering = true
                Log.d(tag, "Discovery started")
            }
            .addOnFailureListener { error ->
                isDiscovering = false
                Log.e(tag, "Discovery FAILED: ${error.message}", error)
                toast("Discovery failed: ${error.message}")
                scheduleReconnect("discovery_failed")
            }
    }

    private fun stopNearbyRadios() {
        if (isAdvertising) {
            connectionsClient.stopAdvertising()
        } else {
            runCatching { connectionsClient.stopAdvertising() }
        }
        if (isDiscovering) {
            connectionsClient.stopDiscovery()
        } else {
            runCatching { connectionsClient.stopDiscovery() }
        }
        isAdvertising = false
        isDiscovering = false
    }

    fun addFriend(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank() || normalized.equals(myName, ignoreCase = true)) return

        trustedFriends.add(normalized)
        Log.d(tag, "addFriend($normalized) - trustedFriends=$trustedFriends")
        if (isRunning) {
            refreshConnectivityState("add_friend")
        }
    }

    fun removeFriend(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return

        trustedFriends.remove(normalized)
        val endpointId = nameToEndpoint.remove(normalized)
        if (endpointId != null) {
            endpointToName.remove(endpointId)
            pendingConnectionEndpointIds.remove(endpointId)
            _peers.value -= endpointId
        }

        if (isRunning) {
            refreshConnectivityState("remove_friend")
        }
        notifyPeerCount()
    }

    fun clearFriends() {
        trustedFriends.clear()
        nameToEndpoint.clear()
        endpointToName.clear()
        pendingConnectionEndpointIds.clear()
        connectionsClient.stopAllEndpoints()
        _peers.value = emptySet()
        notifyPeerCount()

        if (isRunning) {
            refreshConnectivityState("clear_friends")
        }
    }

    fun broadcastPanic(escalationType: String, eventId: String) {
        val normalizedType = normalizeEscalationType(escalationType)
        Log.d(tag, "BROADCASTING PANIC from $myName type=$normalizedType event=$eventId to ${_peers.value.size} peers")
        val payload = Payload.fromBytes("PANIC:$myName:$normalizedType:$eventId".toByteArray())
        _peers.value.forEach { endpointId -> connectionsClient.sendPayload(endpointId, payload) }
    }

    fun sendAcknowledge(targetName: String) {
        val endpointId = nameToEndpoint[targetName]
        if (endpointId != null) {
            Log.d(tag, "Sending ACK to $targetName ($endpointId)")
            val payload = Payload.fromBytes("ACK:$myName".toByteArray())
            connectionsClient.sendPayload(endpointId, payload)
        } else {
            Log.w(tag, "Skipping Nearby ACK for $targetName: no direct endpoint mapping. Relay ACK path will handle remote delivery when configured.")
        }
    }

    fun triggerPeerCountRefresh() {
        notifyPeerCount()
        if (isRunning && trustedFriends.isNotEmpty() && _peers.value.isEmpty()) {
            scheduleReconnect("trigger_peer_refresh")
        }
    }

    private fun notifyPeerCount() {
        nameToEndpoint.entries.removeAll { (_, endpointId) -> !_peers.value.contains(endpointId) }
        val connectedPeerIds = ArrayList(nameToEndpoint.keys)
        val count = _peers.value.size

        Log.d(tag, "Notifying peer count change: count=$count peers=$connectedPeerIds pending=${pendingConnectionEndpointIds.size}")
        val intent = Intent("com.thomaslamendola.ariel.PEER_COUNT_CHANGED").apply {
            putExtra("COUNT", count)
            putStringArrayListExtra("PEER_IDS", connectedPeerIds)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun normalizeEscalationType(type: String): String {
        return when (type.uppercase()) {
            PanicViewModel.ESCALATION_GENERIC -> PanicViewModel.ESCALATION_GENERIC
            PanicViewModel.ESCALATION_MEDICAL -> PanicViewModel.ESCALATION_MEDICAL
            PanicViewModel.ESCALATION_ARMED -> PanicViewModel.ESCALATION_ARMED
            else -> PanicViewModel.ESCALATION_GENERIC
        }
    }

    private fun parsePanicPayload(message: String): Triple<String, String, String>? {
        val parts = message.split(":", limit = 4)
        if (parts.size < 2) return null
        val sender = parts[1]
        if (sender.isBlank()) return null
        val escalationType = if (parts.size >= 3) normalizeEscalationType(parts[2]) else PanicViewModel.ESCALATION_GENERIC
        val eventId = if (parts.size >= 4 && parts[3].isNotBlank()) {
            parts[3]
        } else {
            "legacy_${sender}_${System.currentTimeMillis()}"
        }
        return Triple(sender, escalationType, eventId)
    }
}
