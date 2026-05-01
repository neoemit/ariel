package com.thomaslamendola.ariel

import android.content.Context
import android.content.SharedPreferences

object MonitoringPreferences {
    const val PREF_BACKGROUND_MONITORING_ENABLED = "background_monitoring_enabled"
    const val BACKGROUND_PRESENCE_POLL_INTERVAL_MS = 120_000L
    const val RELAY_HEARTBEAT_INTERVAL_MS = 120_000L

    fun isBackgroundMonitoringEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(PREF_BACKGROUND_MONITORING_ENABLED, true)
    }

    fun isBackgroundMonitoringEnabled(context: Context): Boolean {
        return isBackgroundMonitoringEnabled(context.getSharedPreferences("ariel_prefs", Context.MODE_PRIVATE))
    }

    fun shouldRunBackgroundMonitoring(
        backgroundMonitoringEnabled: Boolean,
        trustedFriendCount: Int,
    ): Boolean {
        return backgroundMonitoringEnabled && trustedFriendCount > 0
    }
}
