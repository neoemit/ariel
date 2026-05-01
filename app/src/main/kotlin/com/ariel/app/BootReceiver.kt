package com.thomaslamendola.ariel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevantActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
        )
        if (!relevantActions.contains(action)) return

        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val userUnlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
            } else {
                true
            }
            if (!userUnlocked) {
                Log.d("BootReceiver", "LOCKED_BOOT_COMPLETED received while user still locked; deferring monitor startup")
                return
            }
        }

        val prefs = context.getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE)
        val trustedFriendCount = prefs.getStringSet("friends", emptySet())
            ?.map { it.trim() }
            ?.count { it.isNotBlank() }
            ?: 0
        if (!MonitoringPreferences.shouldRunBackgroundMonitoring(
                backgroundMonitoringEnabled = MonitoringPreferences.isBackgroundMonitoringEnabled(prefs),
                trustedFriendCount = trustedFriendCount,
            )
        ) {
            Log.d("BootReceiver", "Skipping monitor startup from $action; monitoring disabled or no friends")
            MonitoringSafetyWorker.cancel(context)
            return
        }

        Log.d("BootReceiver", "Starting SirenService from broadcast action=$action")
        val serviceIntent = Intent(context, SirenService::class.java).apply {
            this.action = "START_MONITORING"
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure { error ->
            Log.w("BootReceiver", "Failed to start SirenService for action=$action: ${error.message}")
        }
    }
}
