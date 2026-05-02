package com.thomaslamendola.ariel

object MonitoringPreferences {
    fun shouldRunBackgroundMonitoring(trustedFriendCount: Int): Boolean {
        return trustedFriendCount > 0
    }
}
