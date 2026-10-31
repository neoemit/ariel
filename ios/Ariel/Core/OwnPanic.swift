import Foundation

/// A panic this device sent: tells another tap on a widget from a new panic, and lets the notification
/// that says it failed send it again.
struct OwnPanic: Equatable, Sendable {
    /// Another tap on the same button within this long is the same panic: someone mashing it should
    /// not alert their buddies over and over.
    static let repeatWindow: TimeInterval = 30

    private static let eventIdKey = "ownPanicEventId"
    private static let escalationKey = "ownPanicEscalation"

    let eventId: String
    let escalation: Escalation
    let sentAt: Date

    init(eventId: String = UUID().uuidString.lowercased(), escalation: Escalation, sentAt: Date = Date()) {
        self.eventId = eventId
        self.escalation = escalation
        self.sentAt = sentAt
    }

    func isRepeated(by escalation: Escalation, at date: Date) -> Bool {
        escalation == self.escalation && date.timeIntervalSince(sentAt) < Self.repeatWindow
    }

    var retryUserInfo: [String: String] {
        [Self.eventIdKey: eventId, Self.escalationKey: escalation.rawValue]
    }

    init?(retryUserInfo: [AnyHashable: Any]) {
        guard let eventId = retryUserInfo[Self.eventIdKey] as? String,
              let escalation = (retryUserInfo[Self.escalationKey] as? String).flatMap(Escalation.init(rawValue:))
        else { return nil }
        self.init(eventId: eventId, escalation: escalation)
    }
}
