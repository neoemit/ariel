package com.thomaslamendola.ariel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPreferencesTest {
    @Test
    fun `background monitoring only runs when enabled and friends exist`() {
        assertTrue(
            MonitoringPreferences.shouldRunBackgroundMonitoring(
                backgroundMonitoringEnabled = true,
                trustedFriendCount = 1,
            )
        )
        assertFalse(
            MonitoringPreferences.shouldRunBackgroundMonitoring(
                backgroundMonitoringEnabled = true,
                trustedFriendCount = 0,
            )
        )
        assertFalse(
            MonitoringPreferences.shouldRunBackgroundMonitoring(
                backgroundMonitoringEnabled = false,
                trustedFriendCount = 3,
            )
        )
    }
}
