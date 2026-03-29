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
    private val reconnectDelayMs = 15_000L
    private val dutyCycleScanMs = 15_000L
    private val dutyCycleSleepMs = 45_000L

    private val connectionsClient = Nearby.getConnectionsClient(context)

    private val _peers = MutableStateFlow<Set<String>>(emptySet())
    val peers = _peers.asStateFlow()

    private val trustedFriends = mutableSetOf<String>()
    private val nameToEndpoint = mutableMapOf<String, String>()
    private val endpointToName = mutableMapOf<String, String>()

    var onPanicReceived: ((senderName: String, escalationType: String, eventId: String) -> Unit)? = null
    var onAcknowledgeReceived: ((acknowledgerName: String) -> Unit)? = null
    var onPairingReceived: ((friendName: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dutyCycleRunnable: Runnable? = null
    private var isDutyCycleActive = false
    private var urgentTimeout: Runnable? = null
    private var isRunning = false

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
                    val name = message.removePrefix("PAIR:")
                    nameToEndpoint[name] = endpointId
                    endpointToName[endpointId] = name
                    Log.d(tag, "PAIR received: $name -> $endpointId")
                    toast("Paired with $name")
                    onPairingReceived?.invoke(name)
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
            Log.d(tag, "Connection initiated by ${info.endpointName} ($endpointId)")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                _peers.value += endpointId
                Log.d(tag, "Connected to $endpointId - sending PAIR:$myName")
                notifyStatus("Connected!")
                toast("Connected to buddy!")
                stopDutyCycle()

                connectionsClient.sendPayload(endpointId, Payload.fromBytes("PAIR:$myName".toByteArray()))
                notifyPeerCount()
            } else {
                val message = result.status.statusMessage ?: "unknown"
                Log.e(tag, "Connection failed to $endpointId: $message (code ${result.status.statusCode})")
                notifyStatus("Connection failed: $message")
                toast("Connection failed: $message")
            }
        }

        override fun onDisconnected(endpointId: String) {
            _peers.value -= endpointId
            val disconnectedName = endpointToName.remove(endpointId)
            if (disconnectedName != null) {
                nameToEndpoint.remove(disconnectedName)
            } else {
                nameToEndpoint.entries.removeAll { it.value == endpointId }
            }
            notifyPeerCount()
            Log.d(tag, "Disconnected from $endpointId ($disconnectedName). Will try to reconnect.")
            notifyStatus("Disconnected from $disconnectedName")
            toast("Buddy disconnected. Reconnecting...")

            mainHandler.postDelayed({
                if (isRunning && _peers.value.isEmpty()) {
                    Log.d(tag, "Attempting reconnect - restarting advertising/discovery")
                    startDutyCycle()
                }
            }, reconnectDelayMs)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName
            Log.d(tag, "Discovered: $peerName ($endpointId), trustedFriends=$trustedFriends")
            notifyStatus("Found buddy: $peerName")

            if (_peers.value.contains(endpointId) || nameToEndpoint.containsKey(peerName)) {
                Log.d(tag, "Already connected to $peerName, skipping")
                return
            }

            val shouldConnect = trustedFriends.isEmpty() || trustedFriends.contains(peerName)
            if (shouldConnect) {
                val isInitiator = myName < peerName || trustedFriends.isEmpty()
                if (isInitiator) {
                    Log.d(tag, "Initiating connection to $peerName ($endpointId)")
                    notifyStatus("Connecting to $peerName...")
                    connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                        .addOnSuccessListener { Log.d(tag, "requestConnection to $peerName succeeded") }
                        .addOnFailureListener { error ->
                            Log.e(tag, "requestConnection to $peerName FAILED: ${error.message}")
                            notifyStatus("Connection failed: ${error.message}")
                        }
                } else {
                    Log.d(tag, "Waiting for $peerName to initiate (since $myName >= $peerName)")
                    notifyStatus("Waiting for $peerName...")
                }
            } else {
                Log.d(tag, "Ignoring $peerName - not in trusted list")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(tag, "Endpoint lost: $endpointId")
            mainHandler.postDelayed({
                if (_peers.value.isEmpty() && isRunning) startDutyCycle()
            }, reconnectDelayMs)
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
        Log.d(tag, "startPairing() - advertising as '$myName', trustedFriends=$trustedFriends")
        startDutyCycle()
    }

    fun stopPairing() {
        isRunning = false
        stopDutyCycle()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _peers.value = emptySet()
    }

    private fun restartAdvertisingAndDiscovery() {
        if (!isRunning) return
        Log.d(tag, "Restarting advertising and discovery...")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        startAdvertising()
        startDiscovery()
    }

    private fun startDutyCycle() {
        stopDutyCycle()
        isDutyCycleActive = true
        dutyCycleRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                if (_peers.value.isEmpty()) {
                    Log.d(tag, "DutyCycle: active window start")
                    restartAdvertisingAndDiscovery()
                    mainHandler.postDelayed({
                        Log.d(tag, "DutyCycle: sleep window start (stopping radios)")
                        connectionsClient.stopAdvertising()
                        connectionsClient.stopDiscovery()
                        if (isDutyCycleActive && isRunning) {
                            mainHandler.postDelayed(this, dutyCycleSleepMs)
                        }
                    }, dutyCycleScanMs)
                } else {
                    Log.d(tag, "DutyCycle: peers connected, stopping duty cycle")
                    stopDutyCycle()
                }
            }
        }
        mainHandler.post(dutyCycleRunnable!!)
    }

    private fun stopDutyCycle() {
        dutyCycleRunnable?.let { mainHandler.removeCallbacks(it) }
        dutyCycleRunnable = null
        isDutyCycleActive = false
    }

    fun enterUrgentMode(durationMs: Long = 120_000L) {
        if (!isRunning) return
        Log.d(tag, "Entering urgent mode for ${durationMs}ms")
        stopDutyCycle()
        restartAdvertisingAndDiscovery()
        urgentTimeout?.let { mainHandler.removeCallbacks(it) }
        urgentTimeout = Runnable {
            Log.d(tag, "Urgent mode timeout; peers=${_peers.value.size}")
            if (_peers.value.isEmpty()) startDutyCycle()
        }
        mainHandler.postDelayed(urgentTimeout!!, durationMs)
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(myName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { Log.d(tag, "Advertising started as '$myName'") }
            .addOnFailureListener { error ->
                Log.e(tag, "Advertising FAILED: ${error.message}", error)
                toast("Advertising failed: ${error.message}")
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Log.d(tag, "Discovery started") }
            .addOnFailureListener { error ->
                Log.e(tag, "Discovery FAILED: ${error.message}", error)
                toast("Discovery failed: ${error.message}")
            }
    }

    fun addFriend(name: String) {
        trustedFriends.add(name)
        Log.d(tag, "addFriend($name) - trustedFriends=$trustedFriends")
        if (isRunning) startDutyCycle()
    }

    fun removeFriend(name: String) {
        trustedFriends.remove(name)
        if (isRunning) startDutyCycle()
    }

    fun clearFriends() {
        trustedFriends.clear()
        nameToEndpoint.clear()
        endpointToName.clear()
        connectionsClient.stopAllEndpoints()
        _peers.value = emptySet()
        notifyPeerCount()
        if (isRunning) startDutyCycle()
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
            Log.w(tag, "Could not send ACK: no endpoint for $targetName. Broadcasting to all peers.")
            val payload = Payload.fromBytes("ACK:$myName".toByteArray())
            _peers.value.forEach { id -> connectionsClient.sendPayload(id, payload) }
        }
    }

    fun triggerPeerCountRefresh() {
        notifyPeerCount()
    }

    private fun notifyPeerCount() {
        val count = _peers.value.size
        val connectedPeerIds = ArrayList(nameToEndpoint.keys)
        Log.d(tag, "Notifying peer count change: count=$count peers=$connectedPeerIds")
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
