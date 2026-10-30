import Foundation

/// What the buddy badge on the panic screen says. Port of Android's PresenceAvailabilityStatus.
enum PresenceStatus: Equatable, Sendable {
    /// Not known yet: the first answers are still coming in.
    case checking
    /// Nobody confirmed yet, but these buddies were online a few minutes ago.
    case recentlySeenChecking
    case online
    case offline

    init(confirmedOnlineCount: Int, recentlySeenCount: Int, isChecking: Bool) {
        if confirmedOnlineCount > 0 {
            self = .online
        } else if isChecking && recentlySeenCount > 0 {
            self = .recentlySeenChecking
        } else if isChecking {
            self = .checking
        } else {
            self = .offline
        }
    }
}

/// Which buddies are online, from the relay's presence answers. A port of PanicViewModel's
/// reconciliation, without Nearby (which iOS cannot speak to): one empty answer does not make
/// everyone offline straight away.
///
/// - For `discoveryWindow` after the screen opens (or the pool changes) the badge keeps saying
///   "checking" rather than settling on "no buddies online".
/// - Buddies that were online keep showing for `offlineGrace` while their absence is re-checked.
/// - Otherwise it takes two empty answers in a row to say offline.
struct PresenceTracker: Sendable {
    static let discoveryWindow: TimeInterval = 60
    static let offlineGrace: TimeInterval = 120
    static let recentlySeenWindow: TimeInterval = 5 * 60
    /// How often to ask while things are settled, and while they are being checked.
    static let pollInterval: TimeInterval = 60
    static let checkingRetryInterval: TimeInterval = 6
    static let zeroConfirmationDelay: TimeInterval = 2.5

    private(set) var onlineIds: Set<String> = []
    private(set) var isChecking = true
    private var discoveryStartedAt: Date
    private var lastReachableAt: Date?
    private var emptyAnswers = 0

    init(now: Date = Date()) {
        discoveryStartedAt = now
    }

    /// Opens the discovery window again: the screen came back, or a buddy was added.
    mutating func restartDiscovery(now: Date) {
        discoveryStartedAt = now
        emptyAnswers = 0
        if onlineIds.isEmpty { isChecking = true }
    }

    /// The pool shrank: forget whoever left it, without waiting for the next answer.
    mutating func retain(friends: Set<String>) {
        onlineIds.formIntersection(friends)
        if friends.isEmpty {
            isChecking = false
            emptyAnswers = 0
        }
    }

    /// Takes a presence answer: the online IDs, or nil when the relay could not be asked.
    mutating func update(relayOnlineIds: Set<String>?, friends: Set<String>, now: Date) {
        guard !friends.isEmpty else {
            onlineIds = []
            isChecking = false
            emptyAnswers = 0
            return
        }
        let reachable = (relayOnlineIds ?? []).intersection(friends)
        if !reachable.isEmpty {
            onlineIds = reachable
            isChecking = false
            lastReachableAt = now
            emptyAnswers = 0
            return
        }

        let withinGrace = !onlineIds.isEmpty && lastReachableAt.map { now.timeIntervalSince($0) <= Self.offlineGrace } == true
        if withinGrace {
            // Keep showing who was there while their absence is re-checked.
            onlineIds.formIntersection(friends)
            isChecking = true
            return
        }
        onlineIds = []
        if now.timeIntervalSince(discoveryStartedAt) < Self.discoveryWindow {
            isChecking = true
            return
        }
        emptyAnswers += 1
        isChecking = emptyAnswers < 2
    }

    /// When to ask the relay again.
    var nextPollDelay: TimeInterval {
        guard isChecking else { return Self.pollInterval }
        return emptyAnswers > 0 ? Self.zeroConfirmationDelay : Self.checkingRetryInterval
    }

    /// The buddies remembered as online within `recentlySeenWindow` that are still trusted. Port of
    /// Android's recentlySeenOnlineBuddyIds.
    static func recentlySeen(cachedIds: [String], trustedIds: Set<String>, seenAt: Date?, now: Date) -> Set<String> {
        guard let seenAt, now >= seenAt, now.timeIntervalSince(seenAt) <= recentlySeenWindow else { return [] }
        let trusted = Set(trustedIds.map(\.trimmed).filter { !$0.isEmpty })
        return Set(cachedIds.map(\.trimmed).filter { !$0.isEmpty && trusted.contains($0) })
    }
}
