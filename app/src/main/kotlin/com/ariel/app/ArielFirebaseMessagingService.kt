package com.thomaslamendola.ariel

import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArielFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (!FirebaseBootstrap.ensureInitialized(applicationContext)) return
        val prefs = getSharedPreferences("ariel_prefs", MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        val myName = prefs.getString("user_name", null) ?: return
        serviceScope.launch {
            RelayBackendClient.registerDevice(applicationContext, myName, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (!FirebaseBootstrap.ensureInitialized(applicationContext)) return
        val type = message.data["type"]?.lowercase() ?: return
        when (type) {
            "panic" -> {
                val senderId = message.data["senderId"] ?: return
                val eventId = message.data["eventId"] ?: return
                val escalationType = message.data["escalationType"] ?: PanicViewModel.ESCALATION_GENERIC
                dispatchToSirenService(
                    action = "REMOTE_PANIC_PUSH",
                    extras = mapOf(
                        "SENDER_NAME" to senderId,
                        "EVENT_ID" to eventId,
                        "ESCALATION_TYPE" to escalationType
                    )
                )
            }

            "ack" -> {
                val acknowledgerId = message.data["acknowledgerId"] ?: return
                val eventId = message.data["eventId"] ?: return
                dispatchToSirenService(
                    action = "REMOTE_ACK_PUSH",
                    extras = mapOf(
                        "ACKNOWLEDGER_NAME" to acknowledgerId,
                        "EVENT_ID" to eventId
                    )
                )
            }

            else -> Log.d("ArielFcmService", "Ignoring unsupported push type: $type")
        }
    }

    private fun dispatchToSirenService(action: String, extras: Map<String, String>) {
        val intent = Intent(this, SirenService::class.java).apply {
            this.action = action
            extras.forEach { (key, value) -> putExtra(key, value) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
