import Foundation

/// `ariel://` links: the widget's button and anything else that wants to raise a panic.
///
///   ariel://panic                  sends a generic panic straight away
///   ariel://panic?type=MEDICAL     with an escalation (GENERIC, MEDICAL or ARMED)
enum DeepLink: Equatable {
    static let scheme = "ariel"

    case panic(escalationType: String?)

    static let panicURL = URL(string: "\(scheme)://panic")!

    init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme else { return nil }
        switch url.host?.lowercased() {
        case "panic":
            let type = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "type" }?.value
            self = .panic(escalationType: type)
        default:
            return nil
        }
    }
}
