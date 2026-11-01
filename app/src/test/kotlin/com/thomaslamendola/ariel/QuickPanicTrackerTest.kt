package com.thomaslamendola.ariel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QuickPanicTrackerTest {
    private var generated = 0
    private val tracker = QuickPanicTracker { "event-${++generated}" }

    @Test
    fun `another press of the same button is the same panic`() {
        val first = tracker.eventIdFor("MEDICAL", nowMs = 1_000)
        assertEquals(first, tracker.eventIdFor("MEDICAL", nowMs = 1_000 + QuickPanicTracker.REPEAT_WINDOW_MS - 1))
    }

    @Test
    fun `a different kind of panic is a new one`() {
        val first = tracker.eventIdFor("MEDICAL", nowMs = 1_000)
        assertNotEquals(first, tracker.eventIdFor("ARMED", nowMs = 1_001))
    }

    @Test
    fun `the same button after the window is a new panic`() {
        val first = tracker.eventIdFor("MEDICAL", nowMs = 1_000)
        assertNotEquals(first, tracker.eventIdFor("MEDICAL", nowMs = 1_000 + QuickPanicTracker.REPEAT_WINDOW_MS))
    }

    @Test
    fun `a repeat does not extend the window`() {
        val first = tracker.eventIdFor("MEDICAL", nowMs = 1_000)
        tracker.eventIdFor("MEDICAL", nowMs = 20_000)
        assertNotEquals(first, tracker.eventIdFor("MEDICAL", nowMs = 1_000 + QuickPanicTracker.REPEAT_WINDOW_MS))
    }
}
