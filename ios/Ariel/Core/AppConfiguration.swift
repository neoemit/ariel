import Foundation

/// How panics reach this device while the app is not on screen.
enum DeliveryMode: Equatable, Sendable {
    /// Firebase Cloud Messaging through APNs: alerts arrive with the app closed. Needs the paid Apple
    /// Developer Program (Push Notifications entitlement) and GoogleService-Info.plist.
    case push
    /// The app polls the relay while it is open, and shows its own notifications. For builds signed by
    /// a free Apple team, which cannot have push.
    case inApp
}

/// Build-time settings, from Info.plist (set through ios/Config/*.xcconfig).
struct AppConfiguration: Equatable, Sendable {
    static let pushEnabledKey = "ArielPushEnabled"

    /// The build asks for push (ARIEL_PUSH_ENABLED).
    var pushRequested: Bool
    /// GoogleService-Info.plist is in the app.
    var hasFirebaseConfig: Bool
    var version: String
    var build: String

    var deliveryMode: DeliveryMode {
        pushRequested && hasFirebaseConfig ? .push : .inApp
    }

    /// "1.64 (64)".
    var versionDescription: String { "\(version) (\(build))" }

    static func load(from bundle: Bundle = .main) -> AppConfiguration {
        let info = bundle.infoDictionary ?? [:]
        return AppConfiguration(
            pushRequested: isYes(info[pushEnabledKey]),
            hasFirebaseConfig: bundle.path(forResource: "GoogleService-Info", ofType: "plist") != nil,
            version: info["CFBundleShortVersionString"] as? String ?? "?",
            build: info["CFBundleVersion"] as? String ?? "?"
        )
    }

    /// Info.plist build-setting values arrive as strings ("YES"), sometimes as booleans.
    static func isYes(_ value: Any?) -> Bool {
        switch value {
        case let flag as Bool: flag
        case let text as String: ["yes", "true", "1"].contains(text.trimmed.lowercased())
        default: false
        }
    }
}
