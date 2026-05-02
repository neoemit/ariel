package com.thomaslamendola.ariel

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
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
    private val reconnectDelayBaseMs = 5_000L
    private val reconnectDelayMaxMs = 60_000L
    private val reconnectHealthCheckDelayMs = 45_000L
    private val nearbyStatusAlreadyAdvertising = 8001
    private val nearbyStatusAlreadyDiscovering = 8002

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
    private var isAdvertisingStartInFlight = false
    private var isDiscoveryStartInFlight = false
    private var reconnectAttempt = 0
    private var isInUrgentMode = false

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
                cancelReconnect()
                connectionsClient.sendPayload(endpointId, Payload.fromBytes("PAIR:$myName".toByteArray()))
                notifyPeerCount()
            } else {
                val message = result.status.statusMessage ?: "unknown"
                Log.e(tag, "Connection failed to $endpointId: $message (code ${result.status.statusCode})")
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

            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    Log.d(tag, "requestConnection to $peerName succeeded")
                }
                .addOnFailureListener { error ->
                    pendingConnectionEndpointIds.remove(endpointId)
                    endpointToName.remove(endpointId)
                    Log.e(tag, "requestConnection to $peerName FAILED: ${error.message}")
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

    fun startPairing() {
        isRunning = true
        Log.d(tag, "startPairing() - myName=$myName trustedFriends=$trustedFriends")
        refreshConnectivityState("start_pairing")
        notifyPeerCount()
    }

    fun stopPairing() {
        isRunning = false
        isInUrgentMode = false
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
            isInUrgentMode = false
            cancelReconnect()
            stopNearbyRadios()
            pendingConnectionEndpointIds.clear()
            _peers.value = emptySet()
            notifyPeerCount()
            return
        }

        Log.d(tag, "refreshConnectivityState($reason): starting low-power advertising")
        startAdvertising()
        // Discovery is on-demand only; started by enterUrgentMode() when panic is triggered
    }

    private fun restartAdvertisingAndDiscovery(reason: String) {
        if (!isRunning || trustedFriends.isEmpty()) return

        Log.d(tag, "Restarting advertising/discovery ($reason)")
        stopNearbyRadios()
        startAdvertising()
        if (isInUrgentMode) {
            startDiscovery()
        }
        notifyPeerCount()
    }

    private fun scheduleReconnect(reason: String) {
        if (!isRunning || trustedFriends.isEmpty()) return

        if (!isInUrgentMode) {
            // Low-power mode: only ensure advertising is alive, no reconnect loop
            if (!isAdvertising && !isAdvertisingStartInFlight) {
                startAdvertising()
            }
            return
        }

        // Urgent mode: full reconnect loop to find and connect to all buddies
        if (_peers.value.isNotEmpty()) {
            cancelReconnect()
            return
        }
        if (reconnectRunnable != null) return

        val delayMs = nextReconnectDelayMs()
        Log.d(tag, "Scheduling reconnect in ${delayMs}ms ($reason)")
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (!isRunning || trustedFriends.isEmpty() || !isInUrgentMode) return@Runnable
            if (_peers.value.isNotEmpty()) {
                cancelReconnect()
                return@Runnable
            }

            val radiosActive = isAdvertising && isDiscovering
            val radiosStarting = isAdvertisingStartInFlight || isDiscoveryStartInFlight
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
            if (radiosActive || radiosStarting) {
                Log.d(tag, "Reconnect health check ($reason): radios active=$radiosActive starting=$radiosStarting")
            } else {
                Log.d(tag, "Reconnect tick ($reason): restarting radios")
                restartAdvertisingAndDiscovery("reconnect_$reason")
            }
            scheduleReconnect("loop")
        }
        mainHandler.postDelayed(reconnectRunnable!!, delayMs)
    }

    private fun nextReconnectDelayMs(): Long {
        // In urgent mode both radios should be active; use health-check delay if they are
        val radiosActive = (isAdvertising || isAdvertisingStartInFlight) &&
            (isDiscovering || isDiscoveryStartInFlight)
        if (radiosActive) return reconnectHealthCheckDelayMs

        val exponent = reconnectAttempt.coerceAtMost(4)
        val factor = 1L shl exponent
        return (reconnectDelayBaseMs * factor).coerceAtMost(reconnectDelayMaxMs)
    }

    private fun cancelReconnect(resetBackoff: Boolean = true) {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        if (resetBackoff) {
            reconnectAttempt = 0
        }
    }

    fun enterUrgentMode(durationMs: Long = 120_000L) {
        if (!isRunning || trustedFriends.isEmpty()) return

        Log.d(tag, "Entering urgent mode for ${durationMs}ms")
        isInUrgentMode = true
        // Restart both radios for a clean slate; discovery will find buddies actively
        stopNearbyRadios()
        startAdvertising()
        startDiscovery()
        scheduleReconnect("urgent_start")

        urgentTimeout?.let { mainHandler.removeCallbacks(it) }
        urgentTimeout = Runnable {
            Log.d(tag, "Urgent mode expired, reverting to advertise-only")
            isInUrgentMode = false
            stopDiscoveryOnly()
            cancelReconnect()
            urgentTimeout = null
        }
        mainHandler.postDelayed(urgentTimeout!!, durationMs.coerceAtLeast(5_000L))
    }

    private fun stopDiscoveryOnly() {
        if (isDiscovering) {
            connectionsClient.stopDiscovery()
        } else {
            runCatching { connectionsClient.stopDiscovery() }
        }
        isDiscovering = false
        isDiscoveryStartInFlight = false
    }

    private fun startAdvertising() {
        if (isAdvertising || isAdvertisingStartInFlight) return

        isAdvertisingStartInFlight = true
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(myName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                isAdvertisingStartInFlight = false
                isAdvertising = true
                Log.d(tag, "Advertising started as '$myName'")
            }
            .addOnFailureListener { error ->
                isAdvertisingStartInFlight = false
                if (isAlreadyAdvertisingError(error)) {
                    isAdvertising = true
                    Log.d(tag, "Advertising already active; suppressing duplicate start")
                } else {
                    isAdvertising = false
                    Log.e(tag, "Advertising FAILED: ${error.message}", error)
                    scheduleReconnect("advertising_failed")
                }
            }
    }

    private fun startDiscovery() {
        if (isDiscovering || isDiscoveryStartInFlight) return

        isDiscoveryStartInFlight = true
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                isDiscoveryStartInFlight = false
                isDiscovering = true
                Log.d(tag, "Discovery started")
            }
            .addOnFailureListener { error ->
                isDiscoveryStartInFlight = false
                if (isAlreadyDiscoveringError(error)) {
                    isDiscovering = true
                    Log.d(tag, "Discovery already active; suppressing duplicate start")
                } else {
                    isDiscovering = false
                    Log.e(tag, "Discovery FAILED: ${error.message}", error)
                    scheduleReconnect("discovery_failed")
                }
            }
    }

    private fun isAlreadyAdvertisingError(error: Exception): Boolean {
        val statusCode = (error as? ApiException)?.statusCode
        val message = error.message.orEmpty().lowercase()
        return statusCode == nearbyStatusAlreadyAdvertising ||
            message.contains("already advertising") ||
            message.contains("8001")
    }

    private fun isAlreadyDiscoveringError(error: Exception): Boolean {
        val statusCode = (error as? ApiException)?.statusCode
        val message = error.message.orEmpty().lowercase()
        return statusCode == nearbyStatusAlreadyDiscovering ||
            message.contains("already discovering") ||
            message.contains("8002")
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
        isAdvertisingStartInFlight = false
        isDiscoveryStartInFlight = false
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

    fun replaceTrustedState(friends: Set<String>) {
        val sanitizedFriends = friends
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(myName, ignoreCase = true) }
            .toSet()

        trustedFriends.clear()
        trustedFriends.addAll(sanitizedFriends)

        val endpointIdsToDrop = endpointToName
            .filterValues { mappedName -> mappedName !in sanitizedFriends }
            .keys
            .toList()
        endpointIdsToDrop.forEach { endpointId ->
            val mappedName = endpointToName.remove(endpointId)
            if (mappedName != null) {
                nameToEndpoint.remove(mappedName)
            }
            pendingConnectionEndpointIds.remove(endpointId)
            _peers.value -= endpointId
            runCatching { connectionsClient.disconnectFromEndpoint(endpointId) }
        }
        nameToEndpoint.entries.removeAll { (mappedName, _) -> mappedName !in sanitizedFriends }

        if (isRunning) {
            refreshConnectivityState("replace_trusted_state")
        }
        notifyPeerCount()
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
