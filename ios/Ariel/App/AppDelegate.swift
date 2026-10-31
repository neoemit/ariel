import UIKit
@preconcurrency import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Set before launch finishes, or the tap on a notification that launched the app is lost.
        UNUserNotificationCenter.current().delegate = self
        MainActor.assumeIsolated {
            let model = ArielModel.shared
            PushManager.shared.configure(for: model.configuration)
            PushManager.shared.onToken = { model.updatePushToken($0) }
            PushManager.shared.registerIfAuthorized()
            BackgroundRefresh.register()
            model.start()
        }
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MainActor.assumeIsolated {
            PushManager.shared.setAPNsToken(deviceToken)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("[Ariel] APNs registration failed: \(error.localizedDescription)")
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// A push (or one of the app's own notifications) while Ariel is on screen: the app rings and shows
    /// the alert itself, so the notification only goes to Notification Center.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        guard let event = RelayEvent(userInfo: notification.request.content.userInfo) else {
            completionHandler([.banner, .list, .sound])
            return
        }
        Task { @MainActor in
            ArielModel.shared.receive(event, origin: .live)
            completionHandler([.list])
        }
    }

    /// A tap on a notification, or its "I am coming!" button.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        if let panic = OwnPanic(retryUserInfo: userInfo) {
            Task { @MainActor in
                await ArielModel.shared.retryQuickPanic(panic)
                completionHandler()
            }
            return
        }
        guard let event = RelayEvent(userInfo: userInfo) else {
            completionHandler()
            return
        }
        let action = response.actionIdentifier
        Task { @MainActor in
            let model = ArielModel.shared
            if action == NotificationService.acknowledgeAction {
                await model.acknowledge(event)
            } else if action == UNNotificationDefaultActionIdentifier {
                model.receive(event, origin: .notificationTap)
            }
            completionHandler()
        }
    }
}
