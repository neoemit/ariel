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

struct PendingAcknowledgment: Equatable, Sendable {
    let senderId: String
    let eventId: String
    let displayName: String
}

/// Where this device's own panic is.
enum PanicDelivery: Equatable, Sendable {
    case idle
    case sending
    case sent
    /// The relay could not be reached; `retry` sends the same event again.
    case failed(eventId: String, escalation: Escalation)
    /// The relay took it, but had no way to alert these buddies (no push went through, no ntfy topic):
    /// they may not know. `retry` sends the same event again.
    case undelivered(eventId: String, escalation: Escalation, buddyIds: [String])
    /// There is nobody to send it to.
    case noBuddies

    /// The panic to send again, with its event ID, when it did not get to everyone.
    var panicToRetry: OwnPanic? {
        switch self {
        case .failed(let eventId, let escalation), .undelivered(let eventId, let escalation, _):
            OwnPanic(eventId: eventId, escalation: escalation)
        case .idle, .sending, .sent, .noBuddies:
            nil
        }
    }
}

/// The app's state and behaviour: the iOS counterpart of Android's PanicViewModel and SirenService
/// together. Panics go out through the relay (Nearby is Android-only); they come in by push, or, in
/// builds without push, from the relay's inbox, which is polled while the app is open. With an ntfy
/// topic, the ntfy app also shows them with Ariel closed, and tapping one opens the alert here.
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
    /// How far back a tapped ntfy notification finds its panic: all that the relay's inbox keeps.
    static let inboxRetentionSeconds = 3600
    /// With nobody watching a widget's tap there is no retry button, and each attempt can take the relay
    /// client's 7 s timeout. iOS gives an app launched in the background about 30 s.
    static let quickPanicAttempts = 3

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
    @ObservationIgnored private var lastPanic: OwnPanic?
    /// Who just acknowledged this device's panic; shown for a few seconds.
    private(set) var acknowledgedBy: String?

    // Incoming panic
    private(set) var incomingAlert: IncomingAlert?
    /// An acknowledgment the relay never took: the sender does not know help is coming.
    private(set) var acknowledgmentFailed: PendingAcknowledgment?
    @ObservationIgnored private var acknowledgmentsInFlight: Set<String> = []

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

    // ntfy, for Settings
    /// The topic the relay publishes this device's alerts to.
    private(set) var ntfyTopic: String?
    /// The ntfy server the relay publishes to; nil when it has none, or has not answered yet.
    private(set) var ntfyServerURL: String?
    /// The relay has answered whether it has an ntfy server.
    private(set) var ntfyStatusKnown = false

    var presenceStatus: PresenceStatus {
        PresenceStatus(
            confirmedOnlineCount: onlineBuddies.count,
            recentlySeenCount: recentlySeenBuddies.count,
            isChecking: isPresenceChecking
        )
    }

    var deliveryMode: DeliveryMode { configuration.deliveryMode }

    /// Nothing tells this device about a panic unless Ariel is open.
    var alertsNeedOpenApp: Bool { deliveryMode == .inApp && ntfyTopic == nil }

    @ObservationIgnored private let prefs: Preferences
    @ObservationIgnored private let player = AlertPlayer()
    @ObservationIgnored private let notifications = NotificationService()
    @ObservationIgnored private var tracker = PresenceTracker()
    @ObservationIgnored private var isActive = false
    @ObservationIgnored private var foregroundTasks: [Task<Void, Never>] = []
    @ObservationIgnored private var acknowledgmentTask: Task<Void, Never>?
    @ObservationIgnored private var inboxPollInFlight = false
    /// Panics that arrived while another was on screen; shown one after the other.
    @ObservationIgnored private var queuedAlerts: [IncomingAlert] = []
    /// Panics whose ntfy notification was tapped: someone is looking, so they open without the siren.
    @ObservationIgnored private var tappedPanics: Set<String> = []

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
        // Like the ID, it survives a reinstall: the devices subscribed to it stay subscribed.
        ntfyTopic = prefs.ntfyTopic ?? KeychainStore.string(for: KeychainStore.ntfyTopicAccount)
        needsOnboarding = !prefs.onboarded
        refreshRecentlySeen()
    }

    private var relay: RelayClient {
        RelayClient(baseURL: relayURL, appVersion: configuration.version)
    }

    func displayName(for id: String) -> String {
        nicknames[id]?.trimmed.nonEmpty ?? id
    }

    /// "Alice and User_1234".
    func displayNames(for ids: [String]) -> String {
        ListFormatter.localizedString(byJoining: ids.map(displayName(for:)))
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
        Task { await refreshNtfy() }
        Task { await syncBackgroundHeartbeat() }
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

    /// Background app refresh, or iOS reporting on the background heartbeats: keep presence and the push
    /// registration fresh, and in builds without push pick up panics, which then show as notifications.
    func performBackgroundRefresh() async {
        await syncBackgroundHeartbeat()
        await register()
        await refreshNtfy()
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

    /// Hands iOS the heartbeats that keep this device online while Ariel is suspended, or takes them back
    /// when there is nobody left to be online for.
    private func syncBackgroundHeartbeat() async {
        let plan = friends.isEmpty
            ? nil
            : HeartbeatPlan(relayURL: relayURL, buddyId: myId, appVersion: configuration.version)
        await BackgroundHeartbeat.shared.sync(plan, relay: relay)
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

    /// Sends a panic to every trusted buddy, after the 1.5 s hold on the panic button.
    func triggerPanic() {
        guard !isPanicTriggered else { return }
        let panic = OwnPanic(escalation: selectedEscalation)
        lastPanic = panic
        isPanicTriggered = true
        Task { await send(panic) }
    }

    /// Sends a panic with no hold first: the widget, a Home Screen quick action, a link or Shortcuts. It
    /// returns once the relay has answered, as iOS may suspend the app after that. An older panic still
    /// on the panic screen does not stop it.
    @discardableResult
    func sendQuickPanic(_ escalation: Escalation) async -> PanicDelivery {
        if let lastPanic, lastPanic.isRepeated(by: escalation, at: Date()) {
            guard panicDelivery.panicToRetry != nil else { return panicDelivery }
            return await deliverQuickPanic(lastPanic)
        }
        return await deliverQuickPanic(OwnPanic(escalation: escalation))
    }

    /// The notification that said a panic failed was tapped.
    func retryQuickPanic(_ panic: OwnPanic) async {
        await deliverQuickPanic(panic)
    }

    /// Sends a panic that did not reach everyone again, with the same event ID, so a buddy who did get it
    /// is not alerted twice.
    func retryPanic() {
        guard let panic = panicDelivery.panicToRetry else { return }
        Task { await send(panic) }
    }

    @discardableResult
    private func deliverQuickPanic(_ panic: OwnPanic) async -> PanicDelivery {
        lastPanic = panic
        selectedEscalation = panic.escalation
        isPanicTriggered = true
        // A panic has to get out even if iOS would suspend the app meanwhile.
        let application = UIApplication.shared
        let backgroundTask = application.beginBackgroundTask(withName: "Send panic", expirationHandler: nil)
        defer { application.endBackgroundTask(backgroundTask) }
        await send(panic, attempts: Self.quickPanicAttempts)
        // On screen, the panic screen says how it went.
        if application.applicationState == .background {
            notifications.postOwnPanic(panic, delivery: panicDelivery, unreachedNames: unreachedNames, discreet: discreetMode)
        }
        return panicDelivery
    }

    private func send(_ panic: OwnPanic, attempts: Int = 1) async {
        let recipients = friends
        guard !recipients.isEmpty else {
            panicDelivery = .noBuddies
            return
        }
        panicDelivery = .sending
        let relay = relay
        let myId = myId
        for attempt in 1...attempts {
            do {
                let unreached = try await relay.sendPanic(
                    senderId: myId, eventId: panic.eventId, escalation: panic.escalation, recipientIds: recipients
                )
                noteRelaySuccess()
                // The relay answered: sending again straight away would not reach them either.
                if panicDelivery == .sending {
                    panicDelivery = unreached.isEmpty
                        ? .sent
                        : .undelivered(eventId: panic.eventId, escalation: panic.escalation, buddyIds: unreached)
                }
                return
            } catch {
                noteRelayFailure(error)
                if attempt < attempts { try? await Task.sleep(for: .seconds(1)) }
            }
        }
        if panicDelivery == .sending { panicDelivery = .failed(eventId: panic.eventId, escalation: panic.escalation) }
    }

    /// The buddies the relay could not alert about this device's panic, for the panic screen.
    var unreachedNames: String? {
        guard case .undelivered(_, _, let ids) = panicDelivery else { return nil }
        return displayNames(for: ids)
    }

    func resetPanic() {
        isPanicTriggered = false
        panicDelivery = .idle
        selectedEscalation = .generic
        lastPanic = nil
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
            // The inbox poll of the app opening may get to a tapped panic first.
            let origin = tappedPanics.contains(eventId) ? .notificationTap : origin
            switch origin {
            case .live:
                guard isNew else { return }
            case .notificationTap:
                // Opens the alert, unless it is on screen already or was answered.
                guard incomingAlert?.eventId != eventId, !prefs.isAcknowledged(eventId: eventId) else { return }
            }
            guard !queuedAlerts.contains(where: { $0.eventId == eventId }) else { return }
            let alert = IncomingAlert(
                senderId: senderId, displayName: displayName(for: senderId), eventId: eventId, escalation: escalation
            )
            // Never replace an unanswered alert: its sender is still waiting.
            if incomingAlert == nil {
                incomingAlert = alert
            } else {
                queuedAlerts.append(alert)
            }
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
        guard let alert = incomingAlert else {
            player.stop()
            return
        }
        showNextAlert()
        await acknowledge(senderId: alert.senderId, eventId: alert.eventId)
    }

    /// Shows the next queued panic, still ringing, or stops the siren when there is none.
    private func showNextAlert() {
        if queuedAlerts.isEmpty {
            incomingAlert = nil
            player.stop()
        } else {
            incomingAlert = queuedAlerts.removeFirst()
        }
    }

    /// "I am coming!" on a panic notification, without opening the app.
    func acknowledge(_ event: RelayEvent) async {
        guard case .panic(let senderId, let eventId, _) = event else { return }
        _ = prefs.markHandled(event)
        queuedAlerts.removeAll { $0.eventId == eventId }
        if incomingAlert?.eventId == eventId {
            showNextAlert()
        }
        await acknowledge(senderId: senderId, eventId: eventId)
    }

    private func acknowledge(senderId: String, eventId: String) async {
        notifications.removeDelivered(eventId: eventId)
        guard !prefs.isAcknowledged(eventId: eventId), !acknowledgmentsInFlight.contains(eventId) else { return }
        acknowledgmentsInFlight.insert(eventId)
        defer { acknowledgmentsInFlight.remove(eventId) }
        // The sender is waiting for this: keep trying for a while, and only then record it as sent.
        for attempt in 1...5 {
            do {
                try await relay.sendAcknowledgment(senderId: senderId, acknowledgerId: myId, eventId: eventId)
                noteRelaySuccess()
                _ = prefs.noteAcknowledged(eventId: eventId)
                acknowledgmentFailed = nil
                return
            } catch {
                noteRelayFailure(error)
                if attempt < 5 { try? await Task.sleep(for: .seconds(3)) }
            }
        }
        acknowledgmentFailed = PendingAcknowledgment(senderId: senderId, eventId: eventId,
                                                      displayName: displayName(for: senderId))
    }

    /// Tries a failed acknowledgment again (the panic screen offers it).
    func retryAcknowledgment() async {
        guard let pending = acknowledgmentFailed else { return }
        acknowledgmentFailed = nil
        await acknowledge(senderId: pending.senderId, eventId: pending.eventId)
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

    /// A tap on a panic's ntfy notification, which cannot be answered there. It can come long after the
    /// panic, when the inbox poll no longer shows it, and the sender may still be waiting for an answer.
    func openAlert(eventId: String) async {
        tappedPanics.insert(eventId)
        for attempt in 1...3 {
            do {
                let (events, _) = try await relay.events(
                    buddyId: myId, afterId: 0, maxAgeSeconds: Self.inboxRetentionSeconds
                )
                noteRelaySuccess()
                let panic = events.first {
                    if case .panic(_, eventId, _) = $0 { true } else { false }
                }
                if let panic { receive(panic, origin: .notificationTap) }
                return
            } catch {
                noteRelayFailure(error)
                if attempt < 3 { try? await Task.sleep(for: .seconds(2)) }
            }
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
            await syncBackgroundHeartbeat()
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
        Task { await syncBackgroundHeartbeat() }
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
        Task { await syncBackgroundHeartbeat() }
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
        ntfyServerURL = nil
        ntfyStatusKnown = false
        Task {
            await register()
            await refreshPresence()
            await refreshNtfy()
            await syncBackgroundHeartbeat()
        }
        return true
    }

    /// Asks the relay's /health, for Settings' "Test connection".
    func testRelay() async -> String {
        do {
            let health = try await relay.health()
            noteRelaySuccess()
            noteNtfyServer(health.ntfyServerURL)
            let push = health.pushReady ? "push delivery ready" : "it cannot send pushes"
            let ntfy = health.ntfyServerURL == nil ? "no ntfy server" : "ntfy ready"
            return "Relay reachable: \(push), \(ntfy)."
        } catch {
            noteRelayFailure(error)
            return "Relay not reachable: \(Self.describe(error))"
        }
    }

    // MARK: - ntfy

    /// Reads whether the relay has an ntfy server, and makes sure that it knows this device's topic: it
    /// may be another relay by now, or have lost its database.
    func refreshNtfy() async {
        do {
            let health = try await relay.health()
            noteRelaySuccess()
            noteNtfyServer(health.ntfyServerURL)
            if let topic = ntfyTopic, health.ntfyServerURL != nil {
                _ = try await relay.subscribeNtfy(buddyId: myId, topic: topic)
            }
        } catch {
            noteRelayFailure(error)
        }
    }

    /// Attaches a topic to this device: what was typed, or, when nothing was, a random one from the
    /// relay. Returns a message for the user when it does not work.
    func setNtfyTopic(_ raw: String) async -> String? {
        var requested: String?
        if !raw.trimmed.isEmpty {
            guard let topic = NtfyTopic.normalize(raw) else {
                return "A topic is up to \(NtfyTopic.maxLength - NtfyTopic.prefix.count) letters, digits, - or _."
            }
            requested = topic
        }
        do {
            let subscription = try await relay.subscribeNtfy(buddyId: myId, topic: requested)
            noteRelaySuccess()
            noteNtfyServer(subscription.serverURL)
            storeNtfyTopic(subscription.topic)
            return nil
        } catch {
            noteRelayFailure(error)
            return Self.describeNtfy(error)
        }
    }

    /// Stops the relay publishing to this device's topic. Returns a message for the user when it does not work.
    func removeNtfyTopic() async -> String? {
        guard let topic = ntfyTopic else { return nil }
        do {
            try await relay.unsubscribeNtfy(buddyId: myId, topic: topic)
            noteRelaySuccess()
            storeNtfyTopic(nil)
            return nil
        } catch {
            noteRelayFailure(error)
            return Self.describeNtfy(error)
        }
    }

    /// Sends a test notification to this device's topic, and says how it went.
    func testNtfy() async -> String {
        guard let topic = ntfyTopic else { return "" }
        do {
            let result = try await relay.testNtfy(buddyId: myId, topic: topic)
            noteRelaySuccess()
            return result.summary
        } catch {
            noteRelayFailure(error)
            return Self.describeNtfy(error)
        }
    }

    private func noteNtfyServer(_ url: String?) {
        ntfyServerURL = url
        ntfyStatusKnown = true
    }

    private func storeNtfyTopic(_ topic: String?) {
        ntfyTopic = topic
        prefs.ntfyTopic = topic
        if let topic {
            KeychainStore.set(topic, for: KeychainStore.ntfyTopicAccount)
        } else {
            KeychainStore.remove(KeychainStore.ntfyTopicAccount)
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

    private static func describeNtfy(_ error: Error) -> String {
        switch error {
        case RelayError.http(403): "The relay already has too many ntfy topics for this device."
        case RelayError.http(404): "The relay does not know this topic yet. Try again in a moment."
        case RelayError.http(409): "This relay has no ntfy server set up."
        case RelayError.http(422): "The relay does not accept that topic."
        default: describe(error)
        }
    }
}
