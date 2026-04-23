package com.thomaslamendola.ariel

internal fun mergedOnlineBuddyCount(
    nearbyOnlineBuddyIds: Set<String>,
    relayOnlineBuddyIds: Set<String>,
    trustedBuddyIds: Set<String>,
): Int {
    val trusted = trustedBuddyIds
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    if (trusted.isEmpty()) return 0

    return (nearbyOnlineBuddyIds + relayOnlineBuddyIds)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it in trusted }
        .toSet()
        .size
}
