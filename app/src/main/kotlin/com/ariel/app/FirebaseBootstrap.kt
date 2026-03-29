package com.thomaslamendola.ariel

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseBootstrap {
    private const val TAG = "FirebaseBootstrap"

    fun ensureInitialized(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            return true
        }

        val apiKey = BuildConfig.FIREBASE_API_KEY
        val appId = BuildConfig.FIREBASE_APP_ID
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val senderId = BuildConfig.FIREBASE_SENDER_ID

        if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank() || senderId.isBlank()) {
            Log.w(TAG, "Firebase config missing. Set firebase.* keys in local.properties.")
            return false
        }

        return runCatching {
            val options = FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build()
            FirebaseApp.initializeApp(context, options)
            true
        }.getOrElse { error ->
            Log.w(TAG, "Firebase init failed: ${error.message}")
            false
        }
    }
}
