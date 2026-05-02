package com.thomaslamendola.ariel

import android.app.Notification
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.UUID

class SirenService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private val monitorChannelId = "MonitorChannel"
    private val panicChannelLoudId = "PanicChannelLoud"
    private val panicChannelDiscreetId = "PanicChannelDiscreet"
    private val ackChannelLoudId = "AckChannelLoud"
    private val ackChannelDiscreetId = "AckChannelDiscreet"
    private val discreetVibrationPattern = longArrayOf(0L, 450L, 300L)
    private val notificationId = 1001
    private val monitorNotificationId = 1002
    private val relayRegistrationMinIntervalMs = 45_000L
    private val connectivityRegistrationMinIntervalMs = 10_000L
    private val relayBootstrapDelaysMs = listOf(15_000L, 30_000L)
    private val tokenRetryBackoffMs = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

    private var nearbyManager: NearbyManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var currentPanicSender: String? = null
    private var currentPanicEventId: String? = null
    private val handledEventIds = LinkedHashSet<String>()
    private val handledAckIds = LinkedHashSet<String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayBootstrapRetryJob: Job? = null
    private var tokenRefreshRetryJob: Job? = null
    private var cachedFcmToken: String? = null
    private var lastRelayRegistrationAtMs: Long = 0L
    private var lastSuccessfulRelayRegistrationAtMs: Long = 0L
    private var lastConnectivityRegistrationAtMs: Long = 0L
    private var relayRegistrationInFlight = false
    private var tokenRetryAttempt = 0
    private var ackHandledForActivePanic = false
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            if (now - lastConnectivityRegistrationAtMs < connectivityRegistrationMinIntervalMs) {
                return
            }
            lastConnectivityRegistrationAtMs = now

            Log.d("ArielService", "Network available; forcing relay registration sync")
            startMonitoring()
            syncPushRegistration(force = true, reason = "network_available")
            nearbyManager?.triggerPeerCountRefresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("ArielService", "onStartCommand: action=$action")

        when (action) {
            "START_MONITORING" -> {
                startMonitoring()
                nearbyManager?.triggerPeerCountRefresh()
            }

            "SYNC_PUSH_REGISTRATION" -> {
                syncPushRegistration(force = true, reason = "manual_sync")
            }

            "FORCE_REACHABILITY_REFRESH" -> {
                startMonitoring()
                nearbyManager?.enterUrgentMode(durationMs = 30_000L)
                nearbyManager?.triggerPeerCountRefresh()
                syncPushRegistration(force = false, reason = "forced_reachability_refresh")
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
                    startMonitoring()
                }
            }

            "REMOVE_FRIEND" -> {
                startMonitoring()
                val friendName = intent.getStringExtra("FRIEND_NAME")
                friendName?.let {
                    nearbyManager?.removeFriend(it)
                    startMonitoring()
                }
            }

            "CLEAR_POOL" -> {
                startMonitoring()
                nearbyManager?.clearFriends()
                startMonitoring()
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

            "QUERY_STATE" -> {
                broadcastPanicAlertState(
                    active = currentPanicSender != null,
                    senderId = currentPanicSender
                )
            }
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        cachedFcmToken = prefs.getString(PREF_FCM_TOKEN, null)
        val myName = getOrCreateMyName(prefs)

        val savedFriends = getTrustedFriends(prefs)

        if (nearbyManager == null) {
            nearbyManager = NearbyManager(this, myName).apply {
                onPanicReceived = { sender, escalationType, eventId ->
                    Log.d("ArielService", "PANIC RECEIVED FROM $sender type=$escalationType event=$eventId")
                    handleIncomingPanic(sender, escalationType, eventId)
                }

                onAcknowledgeReceived = { acknowledger ->
                    Log.d("ArielService", "ACK RECEIVED FROM $acknowledger")
                    handleIncomingAcknowledge(acknowledger, null)
                }
            }
        }
        nearbyManager?.replaceTrustedState(savedFriends)

        if (!MonitoringPreferences.shouldRunBackgroundMonitoring(trustedFriendCount = savedFriends.size)) {
            Log.d("ArielService", "Background monitoring idle: no trusted friends")
            nearbyManager?.stopPairing()
            stopBackgroundMonitoringJobs()
            MonitoringSafetyWorker.cancel(applicationContext)
            DailyRegistrationWorker.cancel(applicationContext)
            hideMonitorNotification()
            return
        }

        nearbyManager?.startPairing()
        MonitoringSafetyWorker.schedule(applicationContext)
        DailyRegistrationWorker.schedule(applicationContext)

        showMonitorNotification()
        syncPushRegistration(force = true, reason = "start_monitoring")
        startRelayBootstrapRetries()
    }

    private fun getOrCreateMyName(prefs: android.content.SharedPreferences): String {
        return prefs.getString("user_name", null) ?: run {
            val newName = "User_${(1000..9999).random()}"
            prefs.edit().putString("user_name", newName).apply()
            newName
        }
    }

    private fun getTrustedFriends(prefs: android.content.SharedPreferences): Set<String> {
        val myName = getOrCreateMyName(prefs)
        return prefs.getStringSet("friends", emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !it.equals(myName, ignoreCase = true) }
            ?.toSet()
            ?: emptySet()
    }

    private fun stopBackgroundMonitoringJobs() {
        relayBootstrapRetryJob?.cancel()
        relayBootstrapRetryJob = null
    }

    private fun syncPushRegistration(force: Boolean, reason: String) {
        serviceScope.launch {
            performPushRegistration(force = force, reason = reason)
        }
    }

    private suspend fun performPushRegistration(force: Boolean, reason: String): Boolean {
        val backendUrl = RelayBackendClient.getBackendUrl(this) ?: return false
        if (backendUrl.isBlank()) return false
        if (!FirebaseBootstrap.ensureInitialized(this)) return false

        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        if (getTrustedFriends(prefs).isEmpty()) return false

        val myName = getOrCreateMyName(prefs)
        val cachedToken = cachedFcmToken ?: prefs.getString(PREF_FCM_TOKEN, null)
        val token = if (!cachedToken.isNullOrBlank()) {
            cachedFcmToken = cachedToken
            cachedToken
        } else {
            val refreshedToken = fetchFcmToken()
            if (refreshedToken.isNullOrBlank()) {
                scheduleTokenRefreshRetry()
                Log.w("ArielService", "Skipping relay registration ($reason): missing FCM token")
                return false
            }
            prefs.edit().putString(PREF_FCM_TOKEN, refreshedToken).apply()
            cachedFcmToken = refreshedToken
            resetTokenRefreshRetry()
            refreshedToken
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastRelayRegistrationAtMs < relayRegistrationMinIntervalMs) {
            return true
        }

        if (relayRegistrationInFlight) return false
        relayRegistrationInFlight = true

        Log.d("ArielDiagnostics", "relay_registration_request reason=$reason force=$force")
        return runCatching {
            RelayBackendClient.registerDevice(
                context = this@SirenService,
                buddyId = myName,
                token = token
            )
        }.onFailure { error ->
            Log.w("ArielService", "Relay registration failed ($reason): ${error.message}")
        }.getOrDefault(false).also { success ->
            relayRegistrationInFlight = false
            if (success) {
                lastRelayRegistrationAtMs = now
                lastSuccessfulRelayRegistrationAtMs = now
                resetTokenRefreshRetry()
                Log.d("ArielService", "Relay registration succeeded ($reason)")
            } else {
                Log.w("ArielService", "Relay registration unsuccessful ($reason)")
            }
        }
    }

    private suspend fun fetchFcmToken(): String? {
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (continuation.isActive) continuation.resume(token)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) {
                            continuation.resume(null)
                            Log.w("ArielService", "FCM token fetch failed: ${error.message}")
                        }
                    }
            }
        }.onFailure { error ->
            Log.w("ArielService", "Firebase Messaging unavailable: ${error.message}")
        }.getOrNull()
    }

    private fun scheduleTokenRefreshRetry() {
        if (tokenRefreshRetryJob?.isActive == true) return
        val attemptIndex = tokenRetryAttempt.coerceIn(0, tokenRetryBackoffMs.lastIndex)
        val delayMs = tokenRetryBackoffMs[attemptIndex]
        tokenRetryAttempt = (attemptIndex + 1).coerceAtMost(tokenRetryBackoffMs.lastIndex)
        tokenRefreshRetryJob = serviceScope.launch {
            delay(delayMs)
            tokenRefreshRetryJob = null
            performPushRegistration(force = true, reason = "token_retry")
        }
    }

    private fun resetTokenRefreshRetry() {
        tokenRefreshRetryJob?.cancel()
        tokenRefreshRetryJob = null
        tokenRetryAttempt = 0
    }

    private fun startRelayBootstrapRetries() {
        if (relayBootstrapRetryJob?.isActive == true) return
        relayBootstrapRetryJob = serviceScope.launch {
            for ((index, delayMs) in relayBootstrapDelaysMs.withIndex()) {
                delay(delayMs)
                val recentSuccess = System.currentTimeMillis() - lastSuccessfulRelayRegistrationAtMs < relayRegistrationMinIntervalMs
                if (recentSuccess) break

                val success = performPushRegistration(
                    force = true,
                    reason = "bootstrap_retry_${index + 1}"
                )
                if (success) break
            }
            relayBootstrapRetryJob = null
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

        val discreetModeEnabled = isDiscreetModeEnabled()
        ackHandledForActivePanic = false
        currentPanicSender = sender
        currentPanicEventId = eventId
        startSiren(discreetModeEnabled)
        showPanicNotification(sender, escalationType, discreetModeEnabled)
        broadcastPanicAlertState(active = true, senderId = sender)
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
        val intent = Intent("com.thomaslamendola.ariel.ACKNOWLEDGED").apply {
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

    private fun ensureMonitorChannelCreated() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                monitorChannelId,
                "Ariel Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildMonitorNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, monitorChannelId)
            .setContentTitle("Ariel is active")
            .setContentText(getString(R.string.notification_monitor_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showMonitorNotification() {
        ensureMonitorChannelCreated()
        startForeground(monitorNotificationId, buildMonitorNotification())
    }

    private fun hideMonitorNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(monitorNotificationId)
    }

    private fun startSiren(discreetModeEnabled: Boolean) {
        if (discreetModeEnabled) {
            startDiscreetVibration()
            return
        }

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

    private fun startDiscreetVibration() {
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }

        val alarmVibrator = vibrator ?: return
        if (!alarmVibrator.hasVibrator()) return

        val effect = VibrationEffect.createWaveform(discreetVibrationPattern, 0)
        runCatching {
            alarmVibrator.vibrate(effect)
        }.onFailure { error ->
            Log.w("ArielService", "Failed to start discreet vibration: ${error.message}")
        }
    }

    private fun stopDiscreetVibration() {
        runCatching { vibrator?.cancel() }
    }

    private fun stopSiren() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopDiscreetVibration()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        val sender = currentPanicSender
        val eventId = currentPanicEventId
        if (!sender.isNullOrBlank() && !ackHandledForActivePanic) {
            ackHandledForActivePanic = true
            nearbyManager?.sendAcknowledge(sender)
            sendRelayAcknowledgment(sender, eventId)

            val ackIntent = Intent("com.thomaslamendola.ariel.ACKNOWLEDGED").apply {
                setPackage(packageName)
            }
            sendBroadcast(ackIntent)
        } else if (!sender.isNullOrBlank()) {
            Log.d("ArielService", "Skipping duplicate acknowledgment for active panic sender=$sender event=$eventId")
        }
        currentPanicSender = null
        currentPanicEventId = null
        ackHandledForActivePanic = false
        broadcastPanicAlertState(active = false, senderId = null)

        showMonitorNotification()
        nearbyManager?.triggerPeerCountRefresh()
    }

    private fun showPanicNotification(senderName: String, escalationType: String, discreetModeEnabled: Boolean) {
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
        val panicChannelId = if (discreetModeEnabled) panicChannelDiscreetId else panicChannelLoudId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                panicChannelId,
                if (discreetModeEnabled) "Panic Alerts (Discreet)" else "Panic Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (discreetModeEnabled) {
                    "Silent panic alerts with vibration only"
                } else {
                    "Loud alerts for panic signals"
                }
                enableVibration(true)
                vibrationPattern = discreetVibrationPattern
                setBypassDnd(true)
                if (discreetModeEnabled) {
                    setSound(null, null)
                }
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

        val fullScreenIntent = Intent(this, IncomingAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(IncomingAlertActivity.EXTRA_DISPLAY_NAME, displayName)
            putExtra(IncomingAlertActivity.EXTRA_ESCALATION_TYPE, normalizedEscalation)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            1,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else {
            true
        }

        val notificationBuilder = NotificationCompat.Builder(this, panicChannelId)
            .setContentTitle("ARIEL PANIC ALERT $emoji")
            .setContentText(reasonText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(stopPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .addAction(android.R.drawable.ic_delete, "I am coming!", stopPendingIntent)

        if (canUseFullScreenIntent) {
            notificationBuilder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        val notification = notificationBuilder.build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR
            }

        startForeground(notificationId, notification)
        notificationManager.notify(notificationId, notification)
    }

    private fun showAckNotification(acknowledgerId: String) {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val discreetModeEnabled = isDiscreetModeEnabled()
        val nickname = prefs.getString("nickname_$acknowledgerId", null)
        val displayName = nickname ?: acknowledgerId

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ackChannelId = if (discreetModeEnabled) ackChannelDiscreetId else ackChannelLoudId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ackChannelId,
                if (discreetModeEnabled) "Acknowledgments (Discreet)" else "Acknowledgments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (discreetModeEnabled) {
                    "Silent acknowledgments with vibration only"
                } else {
                    "Notifications when friends acknowledge your alert"
                }
                enableVibration(true)
                vibrationPattern = discreetVibrationPattern
                setBypassDnd(true)
                if (discreetModeEnabled) {
                    setSound(null, null)
                }
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
        private const val PREF_DISCREET_MODE_ENABLED = PanicViewModel.PREF_DISCREET_MODE_ENABLED
        const val ACTION_PANIC_ALERT_STATE = "com.thomaslamendola.ariel.PANIC_ALERT_STATE"
        const val EXTRA_ALERT_ACTIVE = "EXTRA_ALERT_ACTIVE"
        const val EXTRA_ALERT_SENDER_ID = "EXTRA_ALERT_SENDER_ID"
    }

    private fun isDiscreetModeEnabled(): Boolean {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DISCREET_MODE_ENABLED, false)
    }

    private fun broadcastPanicAlertState(active: Boolean, senderId: String?) {
        val intent = Intent(ACTION_PANIC_ALERT_STATE).apply {
            putExtra(EXTRA_ALERT_ACTIVE, active)
            putExtra(EXTRA_ALERT_SENDER_ID, senderId)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        nearbyManager?.stopPairing()
        stopDiscreetVibration()
        broadcastPanicAlertState(active = false, senderId = null)
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        relayBootstrapRetryJob?.cancel()
        relayBootstrapRetryJob = null
        tokenRefreshRetryJob?.cancel()
        tokenRefreshRetryJob = null
        serviceScope.cancel()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { error ->
            Log.w("ArielService", "Failed to register network callback: ${error.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
