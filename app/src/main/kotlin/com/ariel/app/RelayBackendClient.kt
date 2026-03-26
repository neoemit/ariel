package com.ariel.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RelayBackendClient {
    private const val TAG = "RelayBackendClient"
    private const val PREFS_NAME = "ariel_prefs"
    private const val PREF_RELAY_URL = "relay_backend_url"
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 7_000

    fun getBackendUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configured = prefs.getString(PREF_RELAY_URL, null)?.trim().orEmpty()
        if (configured.isBlank()) return null
        return configured.trimEnd('/')
    }

    fun setBackendUrl(context: Context, rawUrl: String) {
        val normalized = rawUrl.trim().trimEnd('/')
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (normalized.isBlank()) {
            prefs.edit().remove(PREF_RELAY_URL).apply()
        } else {
            prefs.edit().putString(PREF_RELAY_URL, normalized).apply()
        }
    }

    suspend fun registerDevice(context: Context, buddyId: String, token: String) {
        if (buddyId.isBlank() || token.isBlank()) return
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        postJson(
            context = context,
            endpoint = "/v1/register-device",
            body = JSONObject().apply {
                put("buddyId", buddyId)
                put("token", token)
                put("appVersion", appVersion)
            }
        )
    }

    suspend fun sendPanic(
        context: Context,
        senderId: String,
        eventId: String,
        escalationType: String,
        recipientIds: Collection<String>
    ) {
        if (senderId.isBlank() || eventId.isBlank()) return
        if (recipientIds.isEmpty()) return

        val recipientArray = JSONArray()
        recipientIds.filter { it.isNotBlank() }.distinct().forEach { recipientArray.put(it) }
        if (recipientArray.length() == 0) return

        postJson(
            context = context,
            endpoint = "/v1/panic",
            body = JSONObject().apply {
                put("senderId", senderId)
                put("eventId", eventId)
                put("escalationType", escalationType)
                put("recipientIds", recipientArray)
            }
        )
    }

    suspend fun sendAcknowledgment(
        context: Context,
        senderId: String,
        acknowledgerId: String,
        eventId: String
    ) {
        if (senderId.isBlank() || acknowledgerId.isBlank() || eventId.isBlank()) return
        postJson(
            context = context,
            endpoint = "/v1/ack",
            body = JSONObject().apply {
                put("senderId", senderId)
                put("acknowledgerId", acknowledgerId)
                put("eventId", eventId)
            }
        )
    }

    private suspend fun postJson(context: Context, endpoint: String, body: JSONObject) {
        val baseUrl = getBackendUrl(context) ?: return
        val url = URL("$baseUrl$endpoint")

        withContext(Dispatchers.IO) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            try {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val response = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }.getOrDefault("")
                    Log.w(TAG, "Relay request failed ($responseCode) endpoint=$endpoint body=$response")
                } else {
                    Unit
                }
            } catch (error: Exception) {
                Log.w(TAG, "Relay request error for endpoint=$endpoint: ${error.message}")
            } finally {
                connection.disconnect()
            }
        }
    }
}
