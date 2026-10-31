import SwiftUI

/// Ariel's colours, from the Android theme (ui/theme/Color.kt).
extension Color {
    static let arielRed = Color(red: 0xDB / 255, green: 0x1F / 255, blue: 0x2B / 255)
    static let arielRedDark = Color(red: 0xB7 / 255, green: 0x1C / 255, blue: 0x1C / 255)
    static let arielSurface = Color(red: 0x12 / 255, green: 0x12 / 255, blue: 0x12 / 255)
    static let arielSurfaceVariant = Color(red: 0x2A / 255, green: 0x2C / 255, blue: 0x31 / 255)
    static let arielOnSurfaceVariant = Color(red: 0xC4 / 255, green: 0xC7 / 255, blue: 0xCE / 255)
    static let arielErrorContainer = Color(red: 0x93 / 255, green: 0x00 / 255, blue: 0x0A / 255)
    static let arielOnErrorContainer = Color(red: 0xFF / 255, green: 0xDA / 255, blue: 0xD6 / 255)

    // Buddy badge, as on Android.
    static let presenceOnline = Color(red: 0x1B / 255, green: 0x5E / 255, blue: 0x20 / 255)
    static let presenceChecking = Color(red: 0x5D / 255, green: 0x40 / 255, blue: 0x37 / 255)
    static let presenceOffline = Color(red: 0x33 / 255, green: 0x33 / 255, blue: 0x33 / 255)
    static let presenceAmber = Color(red: 0xFF / 255, green: 0xC1 / 255, blue: 0x07 / 255)
}

extension PresenceStatus {
    func label(online: Int, recentlySeen: Int) -> String {
        switch self {
        case .online: online == 1 ? "1 Buddy Online" : "\(online) Buddies Online"
        case .recentlySeenChecking:
            recentlySeen == 1 ? "1 Buddy Seen Recently — Checking…" : "\(recentlySeen) Buddies Seen Recently — Checking…"
        case .checking: "Checking Buddy Availability…"
        case .offline: "No Buddies Online"
        }
    }

    var background: Color {
        switch self {
        case .online: .presenceOnline
        case .recentlySeenChecking, .checking: .presenceChecking
        case .offline: .presenceOffline
        }
    }

    var dot: Color {
        switch self {
        case .online: .green
        case .recentlySeenChecking, .checking: .presenceAmber
        case .offline: .gray
        }
    }
}
