import Foundation

/// Buddy IDs are what a QR code holds and what the relay addresses: plain text such as `User_1234`.
enum BuddyID {
    static let maxLength = 128

    /// A new ID, in the Android app's format.
    static func generate() -> String {
        "User_\(Int.random(in: 1000...9999))"
    }

    /// A scanned or typed ID, or nil when it cannot be one (empty, too long, or several lines).
    static func sanitize(_ raw: String) -> String? {
        let value = raw.trimmed
        guard !value.isEmpty, value.count <= maxLength, !value.contains(where: \.isNewline) else { return nil }
        return value
    }

    /// The trusted buddies, without blanks, duplicates or this device's own ID.
    static func sanitizeFriends(_ ids: [String], myId: String) -> [String] {
        var seen = Set<String>()
        return ids.compactMap { sanitize($0) }.filter { id in
            id.caseInsensitiveCompare(myId) != .orderedSame && seen.insert(id).inserted
        }
    }
}
