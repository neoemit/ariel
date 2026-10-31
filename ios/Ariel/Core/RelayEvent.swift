import Foundation

/// A panic or an acknowledgment, as the relay delivers it: in a push's data (FCM puts the data keys at
/// the top level of the APNs payload) or in the inbox that `/v1/events` returns. Both use the keys of
/// the backend's payload: `type`, `senderId`, `eventId`, `escalationType`, `acknowledgerId`.
enum RelayEvent: Equatable, Sendable {
    case panic(senderId: String, eventId: String, escalation: Escalation)
    case ack(acknowledgerId: String, eventId: String)

    init?(
        type: String?,
        senderId: String?,
        eventId: String?,
        escalationType: String?,
        acknowledgerId: String?
    ) {
        switch type?.trimmed.lowercased() {
        case "panic":
            guard let senderId = senderId?.trimmed.nonEmpty, let eventId = eventId?.trimmed.nonEmpty else { return nil }
            self = .panic(senderId: senderId, eventId: eventId, escalation: Escalation(wireValue: escalationType))
        case "ack":
            guard let acknowledgerId = acknowledgerId?.trimmed.nonEmpty, let eventId = eventId?.trimmed.nonEmpty else {
                return nil
            }
            self = .ack(acknowledgerId: acknowledgerId, eventId: eventId)
        default:
            return nil
        }
    }

    /// From a notification's userInfo (a push, or a local notification the app posted itself).
    init?(userInfo: [AnyHashable: Any]) {
        func value(_ key: String) -> String? { userInfo[key] as? String }
        self.init(
            type: value("type"),
            senderId: value("senderId"),
            eventId: value("eventId"),
            escalationType: value("escalationType"),
            acknowledgerId: value("acknowledgerId")
        )
    }

    var eventId: String {
        switch self {
        case .panic(_, let eventId, _), .ack(_, let eventId): eventId
        }
    }

    /// The same panic arrives by push and from the inbox, and every acknowledger of it answers with
    /// the same event ID: this tells them apart.
    var deduplicationKey: String {
        switch self {
        case .panic(_, let eventId, _): "panic:\(eventId)"
        case .ack(let acknowledgerId, let eventId): "ack:\(acknowledgerId):\(eventId)"
        }
    }

    /// The notification userInfo for this event, in the push data's shape.
    var userInfo: [String: String] {
        switch self {
        case .panic(let senderId, let eventId, let escalation):
            ["type": "panic", "senderId": senderId, "eventId": eventId, "escalationType": escalation.rawValue]
        case .ack(let acknowledgerId, let eventId):
            ["type": "ack", "acknowledgerId": acknowledgerId, "eventId": eventId]
        }
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nonEmpty: String? { isEmpty ? nil : self }
}
