package com.ariel.app

import android.app.*
import android.util.Log
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class SirenService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private val CHANNEL_ID = "PanicChannel"
    private val MONITOR_CHANNEL_ID = "MonitorChannel"
    private val NOTIFICATION_ID = 1001
    private val MONITOR_ID = 1002
    
    private var nearbyManager: NearbyManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentPanicSender: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ariel:SirenServiceWakeLock").apply {
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Ariel:WifiLock").apply {
                acquire()
            }
        }
        val action = intent?.action
        Log.d("ArielService", "onStartCommand: action=$action")
        
        when (action) {
            "START_MONITORING" -> {
                startMonitoring()
                nearbyManager?.triggerPeerCountRefresh()
            }
            "STOP_SIREN" -> stopSiren()
            "TRIGGER_PANIC" -> {
                startMonitoring() // ensure initialized
                nearbyManager?.broadcastPanic()
            }
            "ADD_FRIEND" -> {
                startMonitoring() // ensure initialized
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
                showPanicNotification(sender)
            }
            "SIMULATED_ACK" -> {
                val id = intent.getStringExtra("SENDER_NAME") ?: "VirtualBuddy_01"
                Log.d("ArielService", "SIMULATED_ACK from $id")
                showAckNotification(id)
                val it = Intent("com.ariel.app.ACKNOWLEDGED").apply {
                    putExtra("ACKNOWLEDGER_NAME", id)
                    setPackage(packageName)
                }
                sendBroadcast(it)
            }
        }

        val senderName = intent?.getStringExtra("SENDER_NAME")
        if (senderName != null && action != "SIMULATED_PANIC" && action != "SIMULATED_ACK" && action != "com.ariel.app.ACKNOWLEDGED") {
            startSiren()
            showPanicNotification(senderName)
        }
        
        return START_STICKY
    }

    private fun startMonitoring() {
        if (nearbyManager == null) {
            val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
            val myName = prefs.getString("user_name", null) ?: run {
                val newName = "User_${(1000..9999).random()}"
                prefs.edit().putString("user_name", newName).apply()
                newName
            }
            nearbyManager = NearbyManager(this, myName).apply {
                onPanicReceived = { sender ->
                    Log.d("ArielService", "PANIC RECEIVED FROM $sender")
                    currentPanicSender = sender
                    val intent = Intent(this@SirenService, SirenService::class.java).apply {
                        putExtra("SENDER_NAME", sender)
                    }
                    startService(intent)
                }
                
                onAcknowledgeReceived = { acknowledger ->
                    Log.d("ArielService", "ACK RECEIVED FROM $acknowledger")
                    showAckNotification(acknowledger)
                    val it = Intent("com.ariel.app.ACKNOWLEDGED").apply {
                        putExtra("ACKNOWLEDGER_NAME", acknowledger)
                        setPackage(packageName)
                    }
                    sendBroadcast(it)
                }

                onPairingReceived = { friendName ->
                    Log.d("ArielService", "Reciprocal pairing request from $friendName")
                    val currentFriends = prefs.getStringSet("friends", emptySet())?.toMutableSet() ?: mutableSetOf()
                    if (!currentFriends.contains(friendName)) {
                        currentFriends.add(friendName)
                        prefs.edit().putStringSet("friends", currentFriends).apply()
                        Log.d("ArielService", "Added $friendName to friends via reciprocal pairing")
                        // Reload NearbyManager with the new friend so we don't ignore them in discovery
                        addFriend(friendName)
                        
                        // Notify UI
                        val it = Intent("com.ariel.app.FRIENDS_UPDATED").apply {
                            setPackage(packageName)
                        }
                        sendBroadcast(it)
                    }
                }


            }
            
            // Reload friends
            val savedFriends = prefs.getStringSet("friends", emptySet()) ?: emptySet()
            savedFriends.forEach { nearbyManager?.addFriend(it) }
            
            nearbyManager?.startPairing()
        }
        showMonitorNotification()
    }

    private fun showMonitorNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Ariel Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setContentTitle("Ariel is active")
            .setContentText("Listening for panic signals from friends")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(MONITOR_ID, notification)
    }

    private fun startSiren() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        if (mediaPlayer == null) {
            var alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alert == null) {
                alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alert)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun stopSiren() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        // Notify the buddy who triggered the alert that we are coming
        currentPanicSender?.let { sender ->
            nearbyManager?.sendAcknowledge(sender)
        }
        currentPanicSender = null

        // Notify the local app that it was acknowledged
        val ackIntent = Intent("com.ariel.app.ACKNOWLEDGED")
        sendBroadcast(ackIntent)

        // Return to the monitoring notification (don't kill the service!)
        showMonitorNotification()
        
        // Ensure peer count is still accurate in UI
        nearbyManager?.triggerPeerCountRefresh()
    }

    private fun showPanicNotification(senderName: String) {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname_$senderName", null)
        val displayName = nickname ?: senderName

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Panic Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Loud alerts for panic signals"
                enableVibration(true)
                setBypassDnd(true) // Crucial for DND bypass
            }
            notificationManager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, SirenService::class.java).apply {
            action = "STOP_SIREN"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIEL PANIC ALERT!")
            .setContentText("$displayName is in danger!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stopPendingIntent) // Tapping notification stops sirens
            .addAction(android.R.drawable.ic_delete, "I am coming!", stopPendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showAckNotification(acknowledgerId: String) {
        val prefs = getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname_$acknowledgerId", null)
        val displayName = nickname ?: acknowledgerId

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ACK_CHANNEL_ID = "AckChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ACK_CHANNEL_ID,
                "Acknowledgments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when friends acknowledge your alert"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, ACK_CHANNEL_ID)
            .setContentTitle("Help is on the way!")
            .setContentText("$displayName has acknowledged your alert.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyManager?.stopPairing()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
