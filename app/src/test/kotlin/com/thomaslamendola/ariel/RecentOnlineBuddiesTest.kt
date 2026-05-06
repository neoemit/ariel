package com.thomaslamendola.ariel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentOnlineBuddiesTest {
    @Test
    fun `uses recently seen trusted buddies during presence warmup`() {
        val recent = recentlySeenOnlineBuddyIds(
            cachedBuddyIds = setOf(" alpha ", "ghost", "bravo"),
            trustedBuddyIds = setOf("alpha", "bravo", "charlie"),
            seenAtMs = 1_000L,
            nowMs = 2_000L,
            maxAgeMs = 5_000L,
        )

        assertEquals(setOf("alpha", "bravo"), recent)
    }

    @Test
    fun `ignores stale recently seen buddies`() {
        val recent = recentlySeenOnlineBuddyIds(
            cachedBuddyIds = setOf("alpha"),
            trustedBuddyIds = setOf("alpha"),
            seenAtMs = 1_000L,
            nowMs = 10_001L,
            maxAgeMs = 5_000L,
        )

        assertTrue(recent.isEmpty())
    }

    @Test
    fun `recently seen availability remains provisional until fresh presence is confirmed`() {
        assertEquals(
            PresenceAvailabilityStatus.RECENTLY_SEEN_CHECKING,
            presenceAvailabilityStatus(
                confirmedOnlineCount = 0,
                recentlySeenOnlineCount = 2,
                isPresenceChecking = true,
            )
        )
        assertEquals(
            PresenceAvailabilityStatus.ONLINE_CONFIRMED,
            presenceAvailabilityStatus(
                confirmedOnlineCount = 1,
                recentlySeenOnlineCount = 2,
                isPresenceChecking = true,
            )
        )
        assertEquals(
            PresenceAvailabilityStatus.OFFLINE_CONFIRMED,
            presenceAvailabilityStatus(
                confirmedOnlineCount = 0,
                recentlySeenOnlineCount = 2,
                isPresenceChecking = false,
            )
        )
    }
}
