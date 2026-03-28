package com.ariel.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class SirenService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private val channelId = "PanicChannel"
    private val monitorChannelId = "MonitorChannel"
    private val notificationId = 1001
    private val monitorNotificationId = 1002
    private val relayHeartbeatIntervalMs = 10 * 60_000L
    private val relayRegistrationMinIntervalMs = 10 * 60_000L

    private var nearbyManager: NearbyManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPanicSender: String? = null
    private var currentPanicEventId: String? = null
    private val handledEventIds = LinkedHashSet<String>()
    private val handledAckIds = LinkedHashSet<String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayHeartbeatJob: Job? = null
    private var cachedFcmToken: String? = null
    private var lastRelayRegistrationAtMs: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("ArielService", "onStartCommand: action=$action")

        when (action) {
            "START_MONITORING" -> {
                startMonitoring()
                nearbyManager?.triggerPeerCountRefresh()
            }

            "SYNC_PUSH_REGISTRATION" -> {
                syncPushRegistration(force = true)
            }

            "STOP_SIREN" -> {
                stopSiren()
            }

            "TRIGGER_PANIC" -> {
                startMonitoring()
                val escalationType = intent.getStringExtra("ESCALATION_TYPE") ?: PanicViewModel.ESCALATION_GENERIC
                val eventId = UUID.randomUUID().toString()
                nearbyManager?.enterUrgentMode()
                nearbyManager?.broadcastPanic(escalationType, eventId)
                sendRelayPanic(eventId, escalationType)
            }

            "ADD_FRIEND" -> {
                startMonitoring()
                val friendName = intent.getStringExtra("FRIEND_NAME")
                friendName?.let {
                    Log.d("ArielService", "ADD_FRIEND: $it")
                    nearbyManager?.addFriend(it)
                }
            }

            "REMOVE_FRIEND" -> {
                val friendName = intent.getStringExtra("FRIEND_NAME")
                friendName?.let { nearbyManager?.removeFriend(it) }
            }

            "CLEAR_POOL" -> {
                nearbyManager?.clearFriends()
            }

            "SIMULATED_PANIC" -> {
                val sender = intent.getStringExtra("SENDER_NAME") ?: "Virtual Buddy"
                Log.d("ArielService", "SIMULATED_PANIC triggered by $sender")
                startSiren()
                showPanicNotification(sender, PanicViewModel.ESCALATION_GENERIC)
            }

            "SIMULATED_ACK" -> {
                val id = intent.getStringExtra("SENDER_NAME") ?: "VirtualBuddy_01"
                Log.d("ArielService", "SIMULATED_ACK from $id")
                handleIncomingAcknowledge(id, null)
            }

            "REMOTE_PANIC_PUSH" -> {
                startMonitoring()
                val sender = intent.getStringExtra("SENDER_NAME") ?: return START_STICKY
                val escalationType = intent.getStringExtra("ESCALATION_TYPE") ?: PanicViewModel.ESCALATION_GENERIC
                val eventId = intent.getStringExtra("EVENT_ID") ?: "push_${sender}_${System.currentTimeMillis()}"
                handleIncomingPanic(sender, escalationType, eventId)
            }

            "REMOTE_ACK_PUSH" -> {
                val acknowledger = intent.getStringExtra("ACKNOWLEDGER_NAME") ?: return START_STICKY
                val eventId = intent.getStringExtra("EVENT_ID")
                handleIncomingAcknowledge(acknowledger, eventId)
            }
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        cachedFcmToken = cachedFcmToken ?: prefs.getString(PREF_FCM_TOKEN, null)

        if (nearbyManager == null) {
            val myName = getOrCreateMyName(prefs)

            nearbyManager = NearbyManager(this, myName).apply {
                onPanicReceived = { sender, escalationType, eventId ->
                    Log.d("ArielService", "PANIC RECEIVED FROM $sender type=$escalationType event=$eventId")
                    handleIncomingPanic(sender, escalationType, eventId)
                }

                onAcknowledgeReceived = { acknowledger ->
                    Log.d("ArielService", "ACK RECEIVED FROM $acknowledger")
                    handleIncomingAcknowledge(acknowledger, null)
                }

                onPairingReceived = { friendName ->
                    Log.d("ArielService", "Reciprocal pairing request from $friendName")
                    val currentFriends = prefs.getStringSet("friends", emptySet())?.toMutableSet() ?: mutableSetOf()
                    if (!currentFriends.contains(friendName)) {
                        currentFriends.add(friendName)
                        prefs.edit().putStringSet("friends", currentFriends).apply()
                        Log.d("ArielService", "Added $friendName to friends via reciprocal pairing")
                        addFriend(friendName)
                        val updateIntent = Intent("com.ariel.app.FRIENDS_UPDATED").apply {
                            setPackage(packageName)
                        }
                        sendBroadcast(updateIntent)
                    }
                }
            }

            val savedFriends = prefs.getStringSet("friends", emptySet()) ?: emptySet()
            savedFriends.forEach { nearbyManager?.addFriend(it) }
            nearbyManager?.startPairing()
        }

        showMonitorNotification()
        syncPushRegistration(force = false)
        startRelayHeartbeat()
    }

    private fun getOrCreateMyName(prefs: android.content.SharedPreferences): String {
        return prefs.getString("user_name", null) ?: run {
            val newName = "User_${(1000..9999).random()}"
            prefs.edit().putString("user_name", newName).apply()
            newName
        }
    }

    private fun syncPushRegistration(force: Boolean) {
        val backendUrl = RelayBackendClient.getBackendUrl(this) ?: return
        if (backendUrl.isBlank()) return
        if (!FirebaseBootstrap.ensureInitialized(this)) return

        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val myName = getOrCreateMyName(prefs)

        val cachedToken = cachedFcmToken ?: prefs.getString(PREF_FCM_TOKEN, null)
        if (!cachedToken.isNullOrBlank()) {
            cachedFcmToken = cachedToken
            registerTokenIfNeeded(myName = myName, token = cachedToken, force = force)
            return
        }

        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    cachedFcmToken = token
                    prefs.edit().putString(PREF_FCM_TOKEN, token).apply()
                    registerTokenIfNeeded(myName = myName, token = token, force = true)
                }
                .addOnFailureListener { error ->
                    Log.w("ArielService", "FCM token fetch failed: ${error.message}")
                }
        }.onFailure { error ->
            Log.w("ArielService", "Firebase Messaging unavailable: ${error.message}")
        }
    }

    private fun registerTokenIfNeeded(myName: String, token: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRelayRegistrationAtMs < relayRegistrationMinIntervalMs) {
            return
        }

        lastRelayRegistrationAtMs = now
        serviceScope.launch {
            RelayBackendClient.registerDevice(this@SirenService, myName, token)
        }
    }

    private fun startRelayHeartbeat() {
        if (relayHeartbeatJob?.isActive == true) return

        relayHeartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(relayHeartbeatIntervalMs)
                syncPushRegistration(force = false)
            }
        }
    }

    private fun sendRelayPanic(eventId: String, escalationType: String) {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val senderId = getOrCreateMyName(prefs)
        val friends = prefs.getStringSet("friends", emptySet())?.filter { it.isNotBlank() } ?: emptyList()
        if (friends.isEmpty()) return
        if (RelayBackendClient.getBackendUrl(this).isNullOrBlank()) return

        serviceScope.launch {
            RelayBackendClient.sendPanic(
                context = this@SirenService,
                senderId = senderId,
                eventId = eventId,
                escalationType = escalationType,
                recipientIds = friends
            )
        }
    }

    private fun sendRelayAcknowledgment(senderId: String, eventId: String?) {
        if (RelayBackendClient.getBackendUrl(this).isNullOrBlank()) return
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val acknowledgerId = getOrCreateMyName(prefs)
        val ackEventId = eventId ?: "ack_${senderId}_${System.currentTimeMillis()}"

        serviceScope.launch {
            RelayBackendClient.sendAcknowledgment(
                context = this@SirenService,
                senderId = senderId,
                acknowledgerId = acknowledgerId,
                eventId = ackEventId
            )
        }
    }

    private fun handleIncomingPanic(sender: String, escalationType: String, eventId: String) {
        if (!markEventIfNew(eventId)) {
            Log.d("ArielService", "Ignoring duplicate panic event $eventId")
            return
        }

        currentPanicSender = sender
        currentPanicEventId = eventId
        startSiren()
        showPanicNotification(sender, escalationType)
    }

    private fun handleIncomingAcknowledge(acknowledger: String, eventId: String?) {
        if (eventId != null) {
            val ackKey = "$acknowledger:$eventId"
            if (!markAckIfNew(ackKey)) {
                Log.d("ArielService", "Ignoring duplicate ack event $ackKey")
                return
            }
        }

        showAckNotification(acknowledger)
        val intent = Intent("com.ariel.app.ACKNOWLEDGED").apply {
            putExtra("ACKNOWLEDGER_NAME", acknowledger)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun markEventIfNew(eventId: String): Boolean {
        synchronized(handledEventIds) {
            if (handledEventIds.contains(eventId)) return false
            handledEventIds.add(eventId)
            while (handledEventIds.size > 100) {
                handledEventIds.remove(handledEventIds.first())
            }
            return true
        }
    }

    private fun markAckIfNew(ackKey: String): Boolean {
        synchronized(handledAckIds) {
            if (handledAckIds.contains(ackKey)) return false
            handledAckIds.add(ackKey)
            while (handledAckIds.size > 100) {
                handledAckIds.remove(handledAckIds.first())
            }
            return true
        }
    }

    private fun showMonitorNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                monitorChannelId,
                "Ariel Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, monitorChannelId)
            .setContentTitle("Ariel is active")
            .setContentText("Listening for panic signals from friends")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(monitorNotificationId, notification)
    }

    private fun startSiren() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        if (mediaPlayer == null) {
            val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
            var alertUri = prefs.getString("panic_ringtone", null)?.let { Uri.parse(it) }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                isLooping = true
                prepare()
                start()
            }
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Ariel:SirenWhilePlaying"
                ).apply {
                    acquire(10 * 60 * 1000L)
                }
            }
        }
    }

    private fun stopSiren() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        currentPanicSender?.let { sender ->
            nearbyManager?.sendAcknowledge(sender)
            sendRelayAcknowledgment(sender, currentPanicEventId)
        }
        currentPanicSender = null
        currentPanicEventId = null

        val ackIntent = Intent("com.ariel.app.ACKNOWLEDGED").apply {
            setPackage(packageName)
        }
        sendBroadcast(ackIntent)

        showMonitorNotification()
        nearbyManager?.triggerPeerCountRefresh()
    }

    private fun showPanicNotification(senderName: String, escalationType: String) {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname_$senderName", null)
        val displayName = nickname ?: senderName
        val normalizedEscalation = when (escalationType.uppercase()) {
            PanicViewModel.ESCALATION_MEDICAL -> PanicViewModel.ESCALATION_MEDICAL
            PanicViewModel.ESCALATION_ARMED -> PanicViewModel.ESCALATION_ARMED
            else -> PanicViewModel.ESCALATION_GENERIC
        }

        val (emoji, reasonText) = when (normalizedEscalation) {
            PanicViewModel.ESCALATION_MEDICAL -> "🚑" to "$displayName requires medical assistance."
            PanicViewModel.ESCALATION_ARMED -> "🔫" to "$displayName is in danger and requires armed response."
            else -> "🚨" to "$displayName requires urgent assistance."
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Panic Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Loud alerts for panic signals"
                enableVibration(true)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, SirenService::class.java).apply {
            action = "STOP_SIREN"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ARIEL PANIC ALERT $emoji")
            .setContentText(reasonText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stopPendingIntent)
            .addAction(android.R.drawable.ic_delete, "I am coming!", stopPendingIntent)
            .build()

        startForeground(notificationId, notification)
    }

    private fun showAckNotification(acknowledgerId: String) {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname_$acknowledgerId", null)
        val displayName = nickname ?: acknowledgerId

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ackChannelId = "AckChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ackChannelId,
                "Acknowledgments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when friends acknowledge your alert"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, ackChannelId)
            .setContentTitle("Help is on the way!")
            .setContentText("$displayName has acknowledged your alert.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
    }

    companion object {
        private const val PREF_FCM_TOKEN = "fcm_token"
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyManager?.stopPairing()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        relayHeartbeatJob?.cancel()
        relayHeartbeatJob = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
