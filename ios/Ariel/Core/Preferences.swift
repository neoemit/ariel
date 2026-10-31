import Foundation

/// Everything the app keeps on the device, in UserDefaults: the iOS side of Android's `ariel_prefs`.
final class Preferences: @unchecked Sendable {
    private enum Key {
        static let myId = "user_name"
        static let friends = "friends"
        static let nicknames = "nicknames"
        static let relayURL = "relay_backend_url"
        static let discreetMode = "discreet_mode_enabled"
        static let lastOnlineBuddyIds = "last_online_buddy_ids"
        static let lastOnlineSeenAt = "last_online_buddy_seen_at"
        static let handledEvents = "handled_events"
        static let acknowledgedEvents = "acknowledged_events"
        static let eventsCursor = "events_cursor"
        static let fcmToken = "fcm_token"
        static let ntfyTopic = "ntfy_topic"
        static let onboarded = "onboarded"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// This device's buddy ID; nil until the first launch picks one.
    var myId: String? {
        get { defaults.string(forKey: Key.myId) }
        set { defaults.set(newValue, forKey: Key.myId) }
    }

    /// The trusted buddies who get this device's panics, in the order they were added.
    var friends: [String] {
        get { defaults.stringArray(forKey: Key.friends) ?? [] }
        set { defaults.set(newValue, forKey: Key.friends) }
    }

    /// Nicknames by buddy ID.
    var nicknames: [String: String] {
        get { defaults.dictionary(forKey: Key.nicknames) as? [String: String] ?? [:] }
        set { defaults.set(newValue, forKey: Key.nicknames) }
    }

    /// The relay URL typed in Settings; nil for the default relay.
    var relayURL: String? {
        get { defaults.string(forKey: Key.relayURL) }
        set { defaults.set(newValue, forKey: Key.relayURL) }
    }

    /// Vibration only, no siren.
    var discreetMode: Bool {
        get { defaults.bool(forKey: Key.discreetMode) }
        set { defaults.set(newValue, forKey: Key.discreetMode) }
    }

    var lastOnlineBuddyIds: [String] {
        get { defaults.stringArray(forKey: Key.lastOnlineBuddyIds) ?? [] }
        set { defaults.set(newValue, forKey: Key.lastOnlineBuddyIds) }
    }

    var lastOnlineSeenAt: Date? {
        get { defaults.object(forKey: Key.lastOnlineSeenAt) as? Date }
        set { defaults.set(newValue, forKey: Key.lastOnlineSeenAt) }
    }

    var handledEvents: EventLog {
        get {
            defaults.data(forKey: Key.handledEvents).flatMap { try? JSONDecoder().decode(EventLog.self, from: $0) } ?? EventLog()
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: Key.handledEvents) }
    }

    /// The panics this device has answered.
    var acknowledgedEvents: EventLog {
        get {
            defaults.data(forKey: Key.acknowledgedEvents).flatMap { try? JSONDecoder().decode(EventLog.self, from: $0) } ?? EventLog()
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: Key.acknowledgedEvents) }
    }

    /// The relay inbox position already read (`/v1/events`).
    var eventsCursor: Int {
        get { defaults.integer(forKey: Key.eventsCursor) }
        set { defaults.set(newValue, forKey: Key.eventsCursor) }
    }

    var fcmToken: String? {
        get { defaults.string(forKey: Key.fcmToken) }
        set { defaults.set(newValue, forKey: Key.fcmToken) }
    }

    /// The ntfy topic the relay publishes this device's alerts to.
    var ntfyTopic: String? {
        get { defaults.string(forKey: Key.ntfyTopic) }
        set { defaults.set(newValue, forKey: Key.ntfyTopic) }
    }

    /// Whether the first-launch permission screen was shown.
    var onboarded: Bool {
        get { defaults.bool(forKey: Key.onboarded) }
        set { defaults.set(newValue, forKey: Key.onboarded) }
    }

    /// Records an event as handled; false when it already was.
    func markHandled(_ event: RelayEvent) -> Bool {
        var log = handledEvents
        guard log.insert(event.deduplicationKey) else { return false }
        handledEvents = log
        return true
    }

    func isAcknowledged(eventId: String) -> Bool {
        acknowledgedEvents.contains(eventId)
    }

    /// Records that this device answered a panic; false when it already had.
    func noteAcknowledged(eventId: String) -> Bool {
        var log = acknowledgedEvents
        guard log.insert(eventId) else { return false }
        acknowledgedEvents = log
        return true
    }

    /// Forgets the buddy pool and what was learnt about it, as Android's "Reset all connections".
    func clearPool() {
        friends = []
        nicknames = [:]
        lastOnlineBuddyIds = []
        lastOnlineSeenAt = nil
    }
}
