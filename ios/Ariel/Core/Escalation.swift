import Foundation

/// What kind of help a panic asks for. The raw values are the wire format shared with the Android app
/// and the relay backend.
enum Escalation: String, CaseIterable, Codable, Sendable {
    case generic = "GENERIC"
    case medical = "MEDICAL"
    case armed = "ARMED"

    /// Unknown or missing values fall back to generic, as on Android.
    init(wireValue: String?) {
        self = wireValue.flatMap { Escalation(rawValue: $0.trimmingCharacters(in: .whitespaces).uppercased()) } ?? .generic
    }

    var emoji: String {
        switch self {
        case .generic: "🚨"
        case .medical: "🚑"
        case .armed: "🔫"
        }
    }

    var title: String {
        switch self {
        case .generic: "Generic"
        case .medical: "Medical"
        case .armed: "Armed"
        }
    }

    /// Accessibility label of the panic button segment.
    var actionDescription: String {
        switch self {
        case .generic: "Send generic emergency alert"
        case .medical: "Request medical assistance"
        case .armed: "Request armed response"
        }
    }

    /// Shown to the sender once the alert is out.
    var sentConfirmation: String {
        switch self {
        case .generic: "Generic panic details sent."
        case .medical: "Medical assistance details sent."
        case .armed: "Armed response details sent."
        }
    }

    /// The alert title on the receiving side, the same text as Android's notification and the relay's
    /// APNs alert.
    var alertTitle: String { "ARIEL PANIC ALERT \(emoji)" }

    func alertBody(for name: String) -> String {
        switch self {
        case .generic: "\(name) requires urgent assistance."
        case .medical: "\(name) requires medical assistance."
        case .armed: "\(name) is in danger and requires armed response."
        }
    }
}
