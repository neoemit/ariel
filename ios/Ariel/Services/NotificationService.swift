import Foundation
@preconcurrency import UserNotifications

/// Local notifications, and the categories that push alerts use too. Local notifications need no
/// entitlement, so builds without push (a free Apple team) still get them for whatever the app finds
/// while it runs, such as a background refresh.
@MainActor
final class NotificationService {
    /// Must match the relay's APNs `category` (backend/app/main.py).
    static let panicCategory = "PANIC"
    static let acknowledgmentCategory = "ACK"
    static let acknowledgeAction = "ACKNOWLEDGE"
    static let soundName = UNNotificationSoundName("siren.wav")

    private var center: UNUserNotificationCenter { .current() }

    func registerCategories() {
        let acknowledge = UNNotificationAction(
            identifier: Self.acknowledgeAction,
            title: "I am coming!",
            options: []
        )
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.panicCategory, actions: [acknowledge], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.acknowledgmentCategory, actions: [], intentIdentifiers: []),
        ])
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    func postPanic(_ alert: IncomingAlert, discreet: Bool) {
        let content = UNMutableNotificationContent()
        content.title = alert.escalation.alertTitle
        content.body = alert.escalation.alertBody(for: alert.displayName)
        content.sound = discreet ? nil : UNNotificationSound(named: Self.soundName)
        content.interruptionLevel = .timeSensitive
        content.categoryIdentifier = Self.panicCategory
        content.threadIdentifier = alert.eventId
        content.userInfo = RelayEvent.panic(
            senderId: alert.senderId, eventId: alert.eventId, escalation: alert.escalation
        ).userInfo
        post(content, id: "panic-\(alert.eventId)")
    }

    func postAcknowledgment(from name: String, acknowledgerId: String, eventId: String, discreet: Bool) {
        let content = UNMutableNotificationContent()
        content.title = "Help is on the way!"
        content.body = "\(name) has acknowledged your alert."
        content.sound = discreet ? nil : .default
        content.interruptionLevel = .timeSensitive
        content.categoryIdentifier = Self.acknowledgmentCategory
        content.threadIdentifier = eventId
        content.userInfo = RelayEvent.ack(acknowledgerId: acknowledgerId, eventId: eventId).userInfo
        post(content, id: "ack-\(acknowledgerId)-\(eventId)")
    }

    /// Clears the notifications of a panic once it is answered.
    func removeDelivered(eventId: String) {
        let center = center
        center.getDeliveredNotifications { notifications in
            let ids = notifications
                .filter {
                    $0.request.content.threadIdentifier == eventId
                        || ($0.request.content.userInfo["eventId"] as? String) == eventId
                }
                .map(\.request.identifier)
            if !ids.isEmpty {
                center.removeDeliveredNotifications(withIdentifiers: ids)
            }
        }
    }

    private func post(_ content: UNMutableNotificationContent, id: String) {
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil)) { error in
            if let error {
                print("[Ariel] Could not post a notification: \(error.localizedDescription)")
            }
        }
    }
}
