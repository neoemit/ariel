import Foundation

/// The relay backend's address, as typed in Settings.
enum RelayURL {
    static let defaultString = "https://ariel.lmbdev.co.za"
    static let `default` = URL(string: defaultString)!

    /// The URL to store for what was typed: `.some("")` for an empty field (the default relay), nil
    /// when it is not an http(s) URL with a host. Trailing slashes are dropped.
    static func normalize(_ raw: String) -> String? {
        var value = raw.trimmed
        while value.hasSuffix("/") { value.removeLast() }
        if value.isEmpty { return "" }
        guard let components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty,
              URL(string: value) != nil
        else { return nil }
        return value
    }

    /// The relay to use for a stored value: the default when nothing valid is stored.
    static func resolve(_ stored: String?) -> URL {
        guard let stored, let normalized = normalize(stored), !normalized.isEmpty,
              let url = URL(string: normalized) else { return `default` }
        return url
    }
}
