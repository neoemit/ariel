import Foundation
import Observation
import UIKit
import UserNotifications

/// A trusted buddy as the UI shows it.
struct Buddy: Identifiable, Hashable, Sendable {
    let id: String
    let displayName: String
}

/// A panic from a buddy that has not been acknowledged yet.
struct IncomingAlert: Identifiable, Equatable, Sendable {
    let senderId: String
    let displayName: String
    let eventId: String
    let escalation: Escalation

    var id: String { eventId }
}

/// Where this device's own panic is.
enum PanicDelivery: Equatable, Sendable {
    case idle
    case sending
    case sent
    /// The relay could not be reached; `retry` sends the same event again.
    case failed(eventId: String, escalation: Escalation)
    /// There is nobody to send it to.
    case noBuddies
}

/// The app's state and behaviour: the iOS counterpart of Android's PanicViewModel and SirenService
/// together. Panics go out through the relay (Nearby is Android-only); they come in by push, or, in
/// builds without push, from the relay's inbox, which is polled while the app is open.
@MainActor
@Observable
final class ArielModel {
    static let shared = ArielModel()

    static let acknowledgmentBannerDuration: Duration = .seconds(5)
    /// How often the relay's inbox is read while the app is open. Pushes make it a safety net only.
    static let inboxPollInterval: [DeliveryMode: Duration] = [.inApp: .seconds(4), .push: .seconds(20)]
    static let registrationInterval: Duration = .seconds(60)
    /// Inbox panics older than this do not ring any more.
    static let inboxMaxAgeSeconds = 300

    let configuration: AppConfiguration
    let myId: String

    private(set) var friends: [String]
    private(set) var nicknames: [String: String]
    private(set) var relayURL: URL
    /// What was typed in Settings, empty for the default relay.
    private(set) var relayURLOverride: String
    var discreetMode: Bool {
        didSet { prefs.discreetMode = discreetMode }
    }

    // Outgoing panic
    var selectedEscalation: Escalation = .generic
    private(set) var isPanicTriggered = false
    private(set) var panicDelivery: PanicDelivery = .idle
    /// Who just acknowledged this device's panic; shown for a few seconds.
    private(set) var acknowledgedBy: String?

    // Incoming panic
    private(set) var incomingAlert: IncomingAlert?

    // Presence
    private(set) var onlineBuddies: [Buddy] = []
    private(set) var recentlySeenBuddies: [Buddy] = []
    private(set) var isPresenceChecking = true

    /// The first launch's introduction has not been shown yet.
    private(set) var needsOnboarding: Bool

    // Notifications and relay status, for Settings
    private(set) var notificationStatus: UNAuthorizationStatus = .notDetermined
    private(set) var pushToken: String?
    private(set) var lastRelayContact: Date?
    private(set) var lastRelayError: String?

    var presenceStatus: PresenceStatus {
        PresenceStatus(
            confirmedOnlineCount: onlineBuddies.count,
            recentlySeenCount: recentlySeenBuddies.count,
            isChecking: isPresenceChecking
        )
    }

    var deliveryMode: DeliveryMode { configuration.deliveryMode }

    @ObservationIgnored private let prefs: Preferences
    @ObservationIgnored private let player = AlertPlayer()
    @ObservationIgnored private let notifications = NotificationService()
    @ObservationIgnored private var tracker = PresenceTracker()
    @ObservationIgnored private var isActive = false
    @ObservationIgnored private var foregroundTasks: [Task<Void, Never>] = []
    @ObservationIgnored private var acknowledgmentTask: Task<Void, Never>?
    @ObservationIgnored private var inboxPollInFlight = false

    private init(prefs: Preferences = Preferences(), configuration: AppConfiguration = .load()) {
        self.prefs = prefs
        self.configuration = configuration

        // The ID is what buddies scanned: keep it across reinstalls through the Keychain.
        let id = prefs.myId ?? KeychainStore.string(for: KeychainStore.buddyIdAccount) ?? BuddyID.generate()
        prefs.myId = id
        KeychainStore.set(id, for: KeychainStore.buddyIdAccount)
        myId = id

        let friends = BuddyID.sanitizeFriends(prefs.friends, myId: id)
        prefs.friends = friends
        self.friends = friends
        nicknames = prefs.nicknames
        relayURLOverride = prefs.relayURL ?? ""
        relayURL = RelayURL.resolve(prefs.relayURL)
        discreetMode = prefs.discreetMode
        pushToken = prefs.fcmToken
        needsOnboarding = !prefs.onboarded
        refreshRecentlySeen()
    }

    private var relay: RelayClient {
        RelayClient(baseURL: relayURL, appVersion: configuration.version)
    }

    func displayName(for id: String) -> String {
        nicknames[id]?.trimmed.nonEmpty ?? id
    }

    // MARK: - Lifecycle

    /// Called once at launch.
    func start() {
        notifications.registerCategories()
        Task { await refreshNotificationStatus() }
    }

    /// The app came to the foreground or left it.
    func setActive(_ active: Bool) {
        guard active != isActive else { return }
        isActive = active
        foregroundTasks.forEach { $0.cancel() }
        foregroundTasks = []
        guard active else {
            BackgroundRefresh.schedule()
            return
        }
        tracker.restartDiscovery(now: Date())
        applyTracker()
        foregroundTasks = [
            Task { [weak self] in await self?.registrationLoop() },
            Task { [weak self] in await self?.presenceLoop() },
            Task { [weak self] in await self?.inboxLoop() },
        ]
        Task { await refreshNotificationStatus() }
    }

    private func registrationLoop() async {
        while !Task.isCancelled {
            await register()
            try? await Task.sleep(for: Self.registrationInterval)
        }
    }

    private func presenceLoop() async {
        while !Task.isCancelled {
            await refreshPresence()
            try? await Task.sleep(for: .seconds(tracker.nextPollDelay))
        }
    }

    private func inboxLoop() async {
        let interval = Self.inboxPollInterval[deliveryMode] ?? .seconds(10)
        while !Task.isCancelled {
            await pollInbox()
            try? await Task.sleep(for: interval)
        }
    }

    /// Background app refresh: keep presence and the push registration fresh, and in builds without push
    /// pick up panics, which then show as notifications.
    func performBackgroundRefresh() async {
        await register()
        // With push, the relay's alert has already been shown; reading the inbox would show it twice.
        if deliveryMode == .inApp {
            await pollInbox()
        }
    }

    // MARK: - Relay registration

    /// Tells the relay this device is around: with its push token when it has one, else a heartbeat.
    /// Like Android, a device without buddies stays quiet.
    func register() async {
        guard !friends.isEmpty else { return }
        do {
            if deliveryMode == .push, let token = pushToken {
                try await relay.registerDevice(buddyId: myId, token: token)
            } else {
                try await relay.heartbeat(buddyId: myId)
            }
            noteRelaySuccess()
        } catch {
            noteRelayFailure(error)
        }
    }

    /// Firebase issued or rotated the FCM token.
    func updatePushToken(_ token: String) {
        guard token != pushToken else { return }
        pushToken = token
        prefs.fcmToken = token
        Task { await register() }
    }

    // MARK: - Presence

    func refreshPresence() async {
        let friendSet = Set(friends)
        var online: Set<String>?
        if !friendSet.isEmpty {
            do {
                online = try await relay.presence(buddyIds: friends, staleAfterSeconds: 180)
                noteRelaySuccess()
            } catch {
                noteRelayFailure(error)
            }
        }
        tracker.update(relayOnlineIds: online, friends: Set(friends), now: Date())
        applyTracker()
    }

    private func applyTracker() {
        let ids = tracker.onlineIds.intersection(Set(friends))
        onlineBuddies = buddies(for: ids)
        isPresenceChecking = tracker.isChecking
        if !ids.isEmpty {
            prefs.lastOnlineBuddyIds = Array(ids)
            prefs.lastOnlineSeenAt = Date()
        }
        refreshRecentlySeen()
    }

    private func refreshRecentlySeen() {
        let ids = PresenceTracker.recentlySeen(
            cachedIds: prefs.lastOnlineBuddyIds,
            trustedIds: Set(friends),
            seenAt: prefs.lastOnlineSeenAt,
            now: Date()
        )
        recentlySeenBuddies = buddies(for: ids)
    }

    private func buddies(for ids: Set<String>) -> [Buddy] {
        ids.map { Buddy(id: $0, displayName: displayName(for: $0)) }
            .sorted { ($0.displayName.lowercased(), $0.id) < ($1.displayName.lowercased(), $1.id) }
    }

    // MARK: - Outgoing panic

    /// Sends a panic to every trusted buddy. The 1.5 s hold, the widget and the Shortcuts action end here.
    func triggerPanic(_ escalation: Escalation? = nil) {
        guard !isPanicTriggered else { return }
        if let escalation { selectedEscalation = escalation }
        isPanicTriggered = true
        send(eventId: UUID().uuidString.lowercased(), escalation: selectedEscalation)
    }

    /// Sends a panic that could not reach the relay again, with the same event ID, so a buddy who did get
    /// it is not alerted twice.
    func retryPanic() {
        guard case .failed(let eventId, let escalation) = panicDelivery else { return }
        send(eventId: eventId, escalation: escalation)
    }

    private func send(eventId: String, escalation: Escalation) {
        let recipients = friends
        guard !recipients.isEmpty else {
            panicDelivery = .noBuddies
            return
        }
        panicDelivery = .sending
        let relay = relay
        let myId = myId
        Task {
            do {
                try await relay.sendPanic(senderId: myId, eventId: eventId, escalation: escalation, recipientIds: recipients)
                noteRelaySuccess()
                if panicDelivery == .sending { panicDelivery = .sent }
            } catch {
                noteRelayFailure(error)
                if panicDelivery == .sending { panicDelivery = .failed(eventId: eventId, escalation: escalation) }
            }
        }
    }

    func resetPanic() {
        isPanicTriggered = false
        panicDelivery = .idle
        selectedEscalation = .generic
    }

    // MARK: - Incoming events

    /// How an event reached the app.
    enum EventOrigin {
        /// A push that arrived while the app was open, or the relay inbox.
        case live
        /// The user tapped its notification, which already rang.
        case notificationTap
    }

    func receive(_ event: RelayEvent, origin: EventOrigin) {
        let isNew = prefs.markHandled(event)
        switch event {
        case .panic(let senderId, let eventId, let escalation):
            switch origin {
            case .live:
                guard isNew else { return }
            case .notificationTap:
                // Opens the alert, unless it is on screen already or was answered.
                guard incomingAlert?.eventId != eventId, !prefs.isAcknowledged(eventId: eventId) else { return }
            }
            let alert = IncomingAlert(
                senderId: senderId, displayName: displayName(for: senderId), eventId: eventId, escalation: escalation
            )
            incomingAlert = alert
            guard origin == .live else { return }
            if isActive {
                player.start(discreet: discreetMode)
            } else {
                // Found by a background refresh: the notification is the only way to ring.
                notifications.postPanic(alert, discreet: discreetMode)
            }
        case .ack(let acknowledgerId, let eventId):
            guard isNew else { return }
            let name = displayName(for: acknowledgerId)
            showAcknowledgment(from: name)
            if !isActive && origin == .live {
                notifications.postAcknowledgment(from: name, acknowledgerId: acknowledgerId, eventId: eventId, discreet: discreetMode)
            }
        }
    }

    /// The user is on their way: stop the siren and tell the sender, through the relay.
    func acknowledgeIncomingAlert() async {
        player.stop()
        guard let alert = incomingAlert else { return }
        incomingAlert = nil
        await acknowledge(senderId: alert.senderId, eventId: alert.eventId)
    }

    /// "I am coming!" on a panic notification, without opening the app.
    func acknowledge(_ event: RelayEvent) async {
        guard case .panic(let senderId, let eventId, _) = event else { return }
        _ = prefs.markHandled(event)
        if incomingAlert?.eventId == eventId {
            player.stop()
            incomingAlert = nil
        }
        await acknowledge(senderId: senderId, eventId: eventId)
    }

    private func acknowledge(senderId: String, eventId: String) async {
        notifications.removeDelivered(eventId: eventId)
        guard prefs.noteAcknowledged(eventId: eventId) else { return }
        // The sender is waiting for this: try a few times before giving up.
        for attempt in 1...3 {
            do {
                try await relay.sendAcknowledgment(senderId: senderId, acknowledgerId: myId, eventId: eventId)
                noteRelaySuccess()
                return
            } catch {
                noteRelayFailure(error)
                if attempt < 3 { try? await Task.sleep(for: .seconds(3)) }
            }
        }
    }

    private func showAcknowledgment(from name: String) {
        acknowledgedBy = name
        player.playAcknowledgment()
        acknowledgmentTask?.cancel()
        acknowledgmentTask = Task {
            try? await Task.sleep(for: Self.acknowledgmentBannerDuration)
            guard !Task.isCancelled else { return }
            acknowledgedBy = nil
        }
    }

    func pollInbox() async {
        guard !inboxPollInFlight else { return }
        inboxPollInFlight = true
        defer { inboxPollInFlight = false }
        do {
            let (events, cursor) = try await relay.events(
                buddyId: myId, afterId: prefs.eventsCursor, maxAgeSeconds: Self.inboxMaxAgeSeconds
            )
            noteRelaySuccess()
            prefs.eventsCursor = cursor
            events.forEach { receive($0, origin: .live) }
        } catch {
            noteRelayFailure(error)
        }
    }

    // MARK: - Buddy pool

    /// Adds a buddy by ID (a scanned QR code). Returns a message for the user when it is refused.
    @discardableResult
    func addFriend(_ rawId: String) -> String? {
        guard let id = BuddyID.sanitize(rawId) else { return "That code is not a buddy ID." }
        guard id.caseInsensitiveCompare(myId) != .orderedSame else { return "That is your own code." }
        guard !friends.contains(id) else { return "\(displayName(for: id)) is already in your panic pool." }
        friends.append(id)
        prefs.friends = friends
        tracker.restartDiscovery(now: Date())
        applyTracker()
        Task {
            await register()
            await refreshPresence()
        }
        return nil
    }

    func removeFriend(_ id: String) {
        friends.removeAll { $0 == id }
        nicknames[id] = nil
        prefs.friends = friends
        prefs.nicknames = nicknames
        tracker.retain(friends: Set(friends))
        applyTracker()
    }

    func setNickname(_ nickname: String, for id: String) {
        nicknames[id] = nickname.trimmed.nonEmpty
        prefs.nicknames = nicknames
        applyTracker()
    }

    func clearPool() {
        prefs.clearPool()
        friends = []
        nicknames = [:]
        tracker.retain(friends: [])
        applyTracker()
    }

    // MARK: - Settings

    /// Stores a relay URL typed in Settings (empty for the default). Returns false when it is not valid.
    @discardableResult
    func setRelayURL(_ raw: String) -> Bool {
        guard let normalized = RelayURL.normalize(raw) else { return false }
        prefs.relayURL = normalized.isEmpty ? nil : normalized
        relayURLOverride = normalized
        relayURL = RelayURL.resolve(prefs.relayURL)
        lastRelayContact = nil
        lastRelayError = nil
        Task {
            await register()
            await refreshPresence()
        }
        return true
    }

    /// Asks the relay's /health, for Settings' "Test connection".
    func testRelay() async -> String {
        do {
            let health = try await relay.health()
            noteRelaySuccess()
            return health.pushReady ? "Relay reachable, push delivery ready." : "Relay reachable, but it cannot send pushes."
        } catch {
            noteRelayFailure(error)
            return "Relay not reachable: \(Self.describe(error))"
        }
    }

    func completeOnboarding() {
        prefs.onboarded = true
        needsOnboarding = false
    }

    func refreshNotificationStatus() async {
        notificationStatus = await notifications.authorizationStatus()
    }

    /// Asks for notification permission, then registers for push when this build has it.
    func requestNotifications() async {
        _ = await notifications.requestAuthorization()
        await refreshNotificationStatus()
        if deliveryMode == .push {
            PushManager.shared.registerIfAuthorized()
        }
    }

    // MARK: - Relay status

    private func noteRelaySuccess() {
        lastRelayContact = Date()
        lastRelayError = nil
    }

    private func noteRelayFailure(_ error: Error) {
        guard !(error is CancellationError) else { return }
        lastRelayError = Self.describe(error)
    }

    private static func describe(_ error: Error) -> String {
        switch error {
        case RelayError.http(let status): "the relay answered HTTP \(status)"
        case RelayError.invalidResponse: "the relay's answer was not understood"
        default: error.localizedDescription
        }
    }
}
