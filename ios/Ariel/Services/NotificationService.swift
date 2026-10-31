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

    /// How a panic sent from the widget went, as it has no screen to say so. Success makes no sound: it
    /// could give someone away. Tapping a failure sends the panic again.
    func postOwnPanic(_ panic: OwnPanic, delivery: PanicDelivery, unreachedNames: String?, discreet: Bool) {
        let content = UNMutableNotificationContent()
        switch delivery {
        case .sent:
            content.title = "Panic alert sent \(panic.escalation.emoji)"
            content.body = "Your buddies have been notified."
        case .failed:
            content.title = "Panic alert NOT sent"
            content.body = "The relay could not be reached, so your buddies may not know. Tap to try again."
            content.userInfo = panic.retryUserInfo
        case .undelivered:
            content.title = "Panic alert NOT delivered to everyone"
            content.body = "The relay could not alert \(unreachedNames ?? "some buddies"), so they may not know. Tap to try again."
            content.userInfo = panic.retryUserInfo
        case .noBuddies:
            content.title = "Panic alert NOT sent"
            content.body = "Your panic pool is empty: add buddies on the Pairing tab first."
        case .idle, .sending:
            return
        }
        if delivery != .sent {
            content.sound = discreet ? nil : .default
            content.interruptionLevel = .timeSensitive
        }
        post(content, id: "own-panic-\(panic.eventId)")
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
