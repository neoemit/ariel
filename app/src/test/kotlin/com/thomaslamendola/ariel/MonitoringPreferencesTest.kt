package com.thomaslamendola.ariel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPreferencesTest {
    @Test
    fun `background monitoring only runs when friends exist`() {
        assertTrue(MonitoringPreferences.shouldRunBackgroundMonitoring(trustedFriendCount = 1))
        assertFalse(MonitoringPreferences.shouldRunBackgroundMonitoring(trustedFriendCount = 0))
    }
}
