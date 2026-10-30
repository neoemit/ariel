import FirebaseCore
import FirebaseMessaging
import Foundation
import UIKit

/// Firebase Cloud Messaging, which the relay uses for every platform: FCM hands iOS messages to APNs.
/// Only builds with push (ARIEL_PUSH_ENABLED and GoogleService-Info.plist, docs/ios.md) configure it;
/// the others leave Firebase untouched and read the relay's inbox instead.
@MainActor
final class PushManager: NSObject {
    static let shared = PushManager()

    private(set) var isConfigured = false
    /// Called whenever Firebase issues or rotates the FCM token.
    var onToken: ((String) -> Void)?

    private override init() {
        super.init()
    }

    func configure(for configuration: AppConfiguration) {
        guard !isConfigured else { return }
        guard configuration.deliveryMode == .push else {
            if configuration.pushRequested {
                print("[Ariel] GoogleService-Info.plist is not bundled: push is off, alerts arrive while the app is open.")
            }
            return
        }
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        isConfigured = true
    }

    /// Registers with APNs once the user has allowed notifications; APNs answers in the AppDelegate.
    func registerIfAuthorized() {
        guard isConfigured else { return }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
            default:
                break
            }
        }
    }

    func setAPNsToken(_ deviceToken: Data) {
        guard isConfigured else { return }
        Messaging.messaging().apnsToken = deviceToken
    }
}

extension PushManager: MessagingDelegate {
    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken, !token.isEmpty else { return }
        Task { @MainActor in self.onToken?(token) }
    }
}
