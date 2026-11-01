import Foundation

/// The heartbeats handed to iOS in advance, to keep this device showing as online while it has Ariel
/// suspended (`BackgroundHeartbeat` runs them). Each pending one is tagged with `key`, so that the plan
/// can be read back from iOS without Ariel keeping any state of its own: iOS also drops them all when
/// the user force-quits the app.
struct HeartbeatPlan: Equatable, Sendable {
    /// The relay counts an iPhone as online for three of these (`ARIEL_IOS_PRESENCE_WINDOW_SECONDS`), so
    /// that iOS can run a couple of them late without the buddy going offline.
    static let interval: TimeInterval = 10 * 60
    /// Three days ahead: a panic app can go unopened for days, and what iOS is given while Ariel is in
    /// the background it runs when it suits, so the ones handed over while it is open count the most.
    static let count = 432
    /// With less than two days left, opening the app starts over. Not every time: an app going back and
    /// forth should not cancel and create hundreds of tasks each time.
    static let refillBelow = 288

    let relayURL: URL
    let buddyId: String
    let appVersion: String?

    /// What the heartbeats of this plan say and where they go: any change means replacing them.
    var key: String {
        [relayURL.absoluteString, buddyId, appVersion ?? ""].joined(separator: "|")
    }

    /// When each heartbeat may go out, the first one `interval` from now.
    func beginDates(from now: Date) -> [Date] {
        (1...Self.count).map { now.addingTimeInterval(Double($0) * Self.interval) }
    }

    /// Whether the heartbeats iOS still holds, given by their tags, can stay as they are.
    func isCovered(byPending keys: [String?]) -> Bool {
        keys.count >= Self.refillBelow && keys.allSatisfy { $0 == key }
    }
}
