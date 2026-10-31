import Foundation

/// The ntfy topic that brings this device's alerts to the ntfy app, even with Ariel closed (README,
/// "iPhone notifications through ntfy").
enum NtfyTopic {
    /// The relay only publishes in Ariel's own namespace (backend/app/ntfy.py).
    static let prefix = "ariel_"
    /// ntfy's limit.
    static let maxLength = 64

    /// The topic for what was typed, where "alice" and "ariel_alice" are the same one. Nil when ntfy
    /// would not take it: its rule is letters, digits, '-' and '_'.
    static func normalize(_ raw: String) -> String? {
        let value = raw.trimmed
        let topic = value.hasPrefix(prefix) ? value : prefix + value
        guard topic.count > prefix.count, topic.count <= maxLength,
              topic.unicodeScalars.allSatisfy({ isAllowed($0) }) else { return nil }
        return topic
    }

    private static func isAllowed(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar {
        case "a"..."z", "A"..."Z", "0"..."9", "-", "_": true
        default: false
        }
    }
}

/// The relay's answer to attaching a topic.
struct NtfySubscription: Equatable, Sendable {
    let topic: String
    /// What the ntfy app has to subscribe on.
    let serverURL: String
}

/// How the relay's test notification went.
struct NtfyTestResult: Equatable, Sendable {
    let delivered: Bool
    /// The relay's check that iPhones were woken through ntfy.sh: "confirmed", "forwarded", "failed"
    /// or "unchecked".
    let upstream: String

    var summary: String {
        guard delivered else {
            return "The ntfy server did not take it. Check the relay's NTFY_TOKEN and that its user can write to ariel_* (docker compose logs ariel-relay)."
        }
        switch upstream {
        case "forwarded":
            return "Sent, but the ntfy server did not wake iPhones through ntfy.sh, so the relay did. Check upstream-base-url and base-url on the ntfy server."
        case "failed":
            return "The ntfy server has it, but nothing could wake iPhones through ntfy.sh, so it only shows once the ntfy app is opened. Check that the relay and the ntfy server can reach ntfy.sh."
        default:
            return "Sent. It should show now on every device subscribed to this topic."
        }
    }
}
