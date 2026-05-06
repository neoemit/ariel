package com.thomaslamendola.ariel

internal enum class PresenceAvailabilityStatus {
    UNKNOWN_CHECKING,
    RECENTLY_SEEN_CHECKING,
    ONLINE_CONFIRMED,
    OFFLINE_CONFIRMED,
}

internal fun recentlySeenOnlineBuddyIds(
    cachedBuddyIds: Set<String>,
    trustedBuddyIds: Set<String>,
    seenAtMs: Long,
    nowMs: Long,
    maxAgeMs: Long,
): Set<String> {
    if (seenAtMs <= 0L || maxAgeMs <= 0L) return emptySet()
    if (nowMs < seenAtMs || nowMs - seenAtMs > maxAgeMs) return emptySet()

    val trusted = trustedBuddyIds
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    if (trusted.isEmpty()) return emptySet()

    return cachedBuddyIds
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it in trusted }
        .toSet()
}

internal fun presenceAvailabilityStatus(
    confirmedOnlineCount: Int,
    recentlySeenOnlineCount: Int,
    isPresenceChecking: Boolean,
): PresenceAvailabilityStatus = when {
    confirmedOnlineCount > 0 -> PresenceAvailabilityStatus.ONLINE_CONFIRMED
    isPresenceChecking && recentlySeenOnlineCount > 0 -> PresenceAvailabilityStatus.RECENTLY_SEEN_CHECKING
    isPresenceChecking -> PresenceAvailabilityStatus.UNKNOWN_CHECKING
    else -> PresenceAvailabilityStatus.OFFLINE_CONFIRMED
}
