import UIKit

/// The Home Screen quick actions: holding Ariel's icon offers the three kinds of panic
/// (`UIApplicationShortcutItems` in Info.plist). iOS always brings the app to the screen for these, so
/// it shows the panic going out. Only the widget sends without opening Ariel.
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    /// Ariel was not running.
    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        if let item = connectionOptions.shortcutItem {
            _ = perform(item)
        }
    }

    /// Ariel was in the background.
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(perform(shortcutItem))
    }

    private func perform(_ item: UIApplicationShortcutItem) -> Bool {
        guard let escalation = Escalation(quickActionType: item.type) else { return false }
        Task { await ArielModel.shared.sendQuickPanic(escalation) }
        return true
    }
}
