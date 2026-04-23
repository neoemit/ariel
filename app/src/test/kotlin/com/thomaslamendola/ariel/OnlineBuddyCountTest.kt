package com.thomaslamendola.ariel

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineBuddyCountTest {
    @Test
    fun `counts relay-online buddies even when nearby has none`() {
        val trusted = setOf("alpha", "bravo", "charlie")
        val nearby = emptySet<String>()
        val relay = setOf("alpha", "charlie")

        val count = mergedOnlineBuddyCount(
            nearbyOnlineBuddyIds = nearby,
            relayOnlineBuddyIds = relay,
            trustedBuddyIds = trusted,
        )

        assertEquals(2, count)
    }

    @Test
    fun `deduplicates across nearby and relay and ignores unknown ids`() {
        val trusted = setOf("alpha", "bravo")
        val nearby = setOf("alpha", "ghost")
        val relay = setOf("alpha", "bravo")

        val count = mergedOnlineBuddyCount(
            nearbyOnlineBuddyIds = nearby,
            relayOnlineBuddyIds = relay,
            trustedBuddyIds = trusted,
        )

        assertEquals(2, count)
    }
}
