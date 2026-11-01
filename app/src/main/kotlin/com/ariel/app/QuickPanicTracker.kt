package com.thomaslamendola.ariel

/**
 * Tells another press of a panic with no hold (a launcher shortcut, the widget) from a new panic: someone
 * mashing the button should not alert their buddies over and over. Sending the same event ID again is
 * harmless, as receivers and the relay drop what they have seen, and gets through to whoever missed it.
 * The counterpart of iOS's OwnPanic.
 */
class QuickPanicTracker(
    private val newEventId: () -> String,
) {
    private var lastEscalationType: String? = null
    private var lastEventId: String? = null
    private var lastSentAtMs = 0L

    fun eventIdFor(escalationType: String, nowMs: Long): String {
        val eventId = lastEventId
        if (eventId != null && escalationType == lastEscalationType && nowMs - lastSentAtMs < REPEAT_WINDOW_MS) {
            return eventId
        }
        return newEventId().also {
            lastEscalationType = escalationType
            lastEventId = it
            lastSentAtMs = nowMs
        }
    }

    companion object {
        const val REPEAT_WINDOW_MS = 30_000L
    }
}
