import Foundation

/// The events already handled, newest last, so that a panic arriving by push and from the inbox
/// rings once. Bounded like Android's handledEventIds.
struct EventLog: Codable, Equatable, Sendable {
    static let capacity = 100

    private(set) var keys: [String] = []

    func contains(_ key: String) -> Bool { keys.contains(key) }

    /// Records a key; false when it was already there.
    @discardableResult
    mutating func insert(_ key: String) -> Bool {
        guard !keys.contains(key) else { return false }
        keys.append(key)
        if keys.count > Self.capacity {
            keys.removeFirst(keys.count - Self.capacity)
        }
        return true
    }
}
