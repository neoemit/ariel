package com.ariel.app

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NearbyManager(private val context: Context, val myName: String) {
    private val TAG = "NearbyManager"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.ariel.app.PANIC_SERVICE"
    private val RECONNECT_DELAY_MS = 5000L    // wait 5s before reconnect attempt
    private val KEEPALIVE_INTERVAL_MS = 60000L // restart adv/disc every 60s

    private val connectionsClient = Nearby.getConnectionsClient(context)

    // Currently connected endpoint IDs
    private val _peers = MutableStateFlow<Set<String>>(emptySet())
    val peers = _peers.asStateFlow()

    // Trusted friend names (names we accept connections from)
    private val trustedFriends = mutableSetOf<String>()

    // Map endpoint name -> endpoint ID for connected peers
    private val nameToEndpoint = mutableMapOf<String, String>()

    // Callbacks
    var onPanicReceived: ((senderName: String) -> Unit)? = null
    var onAcknowledgeReceived: ((acknowledgerName: String) -> Unit)? = null
    var onPairingReceived: ((friendName: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    private var isRunning = false

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val message = payload.asBytes()?.decodeToString() ?: return
            Log.d(TAG, "Payload from $endpointId: $message")

            when {
                message.startsWith("PANIC:") -> {
                    onPanicReceived?.invoke(message.removePrefix("PANIC:"))
                }
                message.startsWith("ACK:") -> {
                    onAcknowledgeReceived?.invoke(message.removePrefix("ACK:"))
                }
                message.startsWith("PAIR:") -> {
                    val name = message.removePrefix("PAIR:")
                    nameToEndpoint[name] = endpointId
                    Log.d(TAG, "PAIR received: $name -> $endpointId")
                    toast("Paired with $name")
                    onPairingReceived?.invoke(name)
                }
                message == "PING" -> {
                    // Keep-alive ping received; send pong back
                    connectionsClient.sendPayload(
                        endpointId,
                        Payload.fromBytes("PONG".toByteArray())
                    )
                }
                message == "PONG" -> {
                    Log.d(TAG, "PONG from $endpointId — link alive")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated by ${info.endpointName} ($endpointId)")
            // Always accept
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                _peers.value += endpointId
                Log.d(TAG, "Connected to $endpointId — sending PAIR:$myName")
                notifyStatus("Connected!")
                toast("Connected to buddy!")

                // Tell the other side who we are
                connectionsClient.sendPayload(
                    endpointId,
                    Payload.fromBytes("PAIR:$myName".toByteArray())
                )
                notifyPeerCount()
            } else {
                val msg = result.status.statusMessage ?: "unknown"
                Log.e(TAG, "Connection failed to $endpointId: $msg (code ${result.status.statusCode})")
                notifyStatus("Connection failed: $msg")
                toast("Connection failed: $msg")
            }
        }

        override fun onDisconnected(endpointId: String) {
            _peers.value -= endpointId
            val disconnectedName = nameToEndpoint.entries.find { it.value == endpointId }?.key
            nameToEndpoint.entries.removeAll { it.value == endpointId }
            notifyPeerCount()
            Log.d(TAG, "Disconnected from $endpointId ($disconnectedName). Will try to reconnect.")
            notifyStatus("Disconnected from $disconnectedName")
            toast("Buddy disconnected. Reconnecting...")

            // Schedule a reconnect attempt after a short delay
            mainHandler.postDelayed({
                if (isRunning && _peers.value.isEmpty()) {
                    Log.d(TAG, "Attempting reconnect — restarting advertising/discovery")
                    restartAdvertisingAndDiscovery()
                }
            }, RECONNECT_DELAY_MS)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerName = info.endpointName
            Log.d(TAG, "Discovered: $peerName ($endpointId), trustedFriends=$trustedFriends")
            notifyStatus("Found buddy: $peerName")

            // Already connected to this peer?
            if (_peers.value.contains(endpointId) || nameToEndpoint.containsKey(peerName)) {
                Log.d(TAG, "Already connected to $peerName, skipping")
                return
            }

            // Connect if: we have no friends yet (open pairing mode), OR
            //             this peer is in our trusted list
            val shouldConnect = trustedFriends.isEmpty() || trustedFriends.contains(peerName)

            if (shouldConnect) {
                // To avoid race conditions where both devices request at the exact same time,
                // the device with the lexicographically smaller name initiates.
                // However, if we are in "Pairing Mode" (trustedFriends is empty), we should be more aggressive.
                val isInitiator = myName < peerName || trustedFriends.isEmpty()
                
                if (isInitiator) {
                    Log.d(TAG, "Initiating connection to $peerName ($endpointId)")
                    notifyStatus("Connecting to $peerName...")
                    connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                        .addOnSuccessListener {
                            Log.d(TAG, "requestConnection to $peerName succeeded")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "requestConnection to $peerName FAILED: ${e.message}")
                            notifyStatus("Connection failed: ${e.message}")
                        }
                } else {
                    Log.d(TAG, "Waiting for $peerName to initiate (since $myName >= $peerName)")
                    notifyStatus("Waiting for $peerName...")
                }
            } else {
                Log.d(TAG, "Ignoring $peerName — not in trusted list")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
        }
    }

    private fun notifyStatus(status: String) {
        val intent = Intent("com.ariel.app.STATUS_UPDATE").apply {
            putExtra("STATUS", status)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    fun startPairing() {
        isRunning = true
        Log.d(TAG, "startPairing() — advertising as '$myName', trustedFriends=$trustedFriends")
        startAdvertising()
        startDiscovery()
        startKeepAlive()
    }

    fun stopPairing() {
        isRunning = false
        stopKeepAlive()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun restartAdvertisingAndDiscovery() {
        if (!isRunning) return
        Log.d(TAG, "Restarting advertising and discovery...")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        startAdvertising()
        startDiscovery()
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                // If we have peers, send a ping to verify the link is alive
                if (_peers.value.isNotEmpty()) {
                    Log.d(TAG, "Keep-alive: pinging ${_peers.value.size} peers")
                    val pingPayload = Payload.fromBytes("PING".toByteArray())
                    _peers.value.forEach { endpointId ->
                        try {
                            connectionsClient.sendPayload(endpointId, pingPayload)
                        } catch (e: Exception) {
                            Log.e(TAG, "Keep-alive ping failed for $endpointId", e)
                        }
                    }
                } else if (trustedFriends.isNotEmpty()) {
                    // No peers but we have friends — restart discovery to find them
                    Log.d(TAG, "Keep-alive: no peers, restarting discovery")
                    restartAdvertisingAndDiscovery()
                }

                mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(keepAliveRunnable!!, KEEPALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(myName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                Log.d(TAG, "Advertising started as '$myName'")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Advertising FAILED: ${e.message}", e)
                toast("Advertising failed: ${e.message}")
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                Log.d(TAG, "Discovery started")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Discovery FAILED: ${e.message}", e)
                toast("Discovery failed: ${e.message}")
            }
    }

    fun addFriend(name: String) {
        trustedFriends.add(name)
        Log.d(TAG, "addFriend($name) — trustedFriends=$trustedFriends")
        restartAdvertisingAndDiscovery()
    }

    fun removeFriend(name: String) {
        trustedFriends.remove(name)
        restartAdvertisingAndDiscovery()
    }

    fun clearFriends() {
        trustedFriends.clear()
        nameToEndpoint.clear()
        connectionsClient.stopAllEndpoints()
        _peers.value = emptySet()
        notifyPeerCount()
        restartAdvertisingAndDiscovery()
    }

    fun broadcastPanic() {
        Log.d(TAG, "BROADCASTING PANIC from $myName to ${_peers.value.size} peers")
        val payload = Payload.fromBytes("PANIC:$myName".toByteArray())
        _peers.value.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
    }

    fun sendAcknowledge(targetName: String) {
        val endpointId = nameToEndpoint[targetName]
        if (endpointId != null) {
            Log.d(TAG, "Sending ACK to $targetName ($endpointId)")
            val payload = Payload.fromBytes("ACK:$myName".toByteArray())
            connectionsClient.sendPayload(endpointId, payload)
        } else {
            Log.w(TAG, "Could not send ACK: No endpoint for $targetName. Broadcasting to all peers.")
            val payload = Payload.fromBytes("ACK:$myName".toByteArray())
            _peers.value.forEach { id ->
                connectionsClient.sendPayload(id, payload)
            }
        }
    }

    fun triggerPeerCountRefresh() {
        notifyPeerCount()
    }

    private fun notifyPeerCount() {
        val count = _peers.value.size
        Log.d(TAG, "Notifying peer count change: $count")
        val intent = Intent("com.ariel.app.PEER_COUNT_CHANGED").apply {
            putExtra("COUNT", count)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
