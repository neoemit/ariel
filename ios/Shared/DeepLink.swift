import Foundation

/// `ariel://` links: the widget's button, anything else that wants to raise a panic, and tapping a
/// notification in the ntfy app (backend/app/main.py).
///
///   ariel://panic                  sends a generic panic straight away
///   ariel://panic?type=MEDICAL     with an escalation (GENERIC, MEDICAL or ARMED)
///   ariel://alert?event=<id>       shows a buddy's panic, if the relay's inbox has it for this device
///   ariel://home                   just opens the app
enum DeepLink: Equatable {
    static let scheme = "ariel"

    case panic(escalationType: String?)
    case alert(eventId: String)
    case home

    static let panicURL = URL(string: "\(scheme)://panic")!

    init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme else { return nil }
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        switch url.host?.lowercased() {
        case "panic":
            self = .panic(escalationType: query?.first { $0.name == "type" }?.value)
        case "alert":
            guard let eventId = query?.first(where: { $0.name == "event" })?.value, !eventId.isEmpty else { return nil }
            self = .alert(eventId: eventId)
        case "home":
            self = .home
        default:
            return nil
        }
    }
}
