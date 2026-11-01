import Foundation

/// Tells the relay this device is around while iOS has Ariel suspended. iOS gives a suspended app no
/// timers, and no entitlement a free Apple team can sign buys them; it does run a background session's
/// transfers by itself, without waking the app. So a few days of small heartbeats go to iOS in advance,
/// each with the earliest time it may go out (`HeartbeatPlan`).
///
/// iOS decides when each one really leaves: it may hold them back on low battery, in Low Power Mode or
/// without a network, and it drops them all if the user force-quits Ariel. The relay allows for the
/// lateness (`ARIEL_IOS_PRESENCE_WINDOW_SECONDS`), and a buddy who stops reporting goes offline there.
///
/// Once the last one has gone out iOS launches Ariel in the background, which schedules more.
final class BackgroundHeartbeat: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = BackgroundHeartbeat()

    static var identifier: String {
        (Bundle.main.bundleIdentifier ?? "ariel") + ".heartbeat"
    }

    /// Bigger than any answer the relay gives to a heartbeat, for iOS to plan the transfer with.
    private static let expectedResponseBytes: Int64 = 1024

    // The delegate runs on the main queue, which also guards `eventsFinished`.
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        // Only counts for tasks created in the foreground: iOS decides by itself for the others.
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    }()

    private var eventsFinished: (() -> Void)?
    /// The latest `sync`: each waits for the one before, or two at once would both schedule.
    @MainActor private var lastSync: Task<Void, Never>?

    /// iOS launched Ariel to report on the session's transfers: `completion` runs once it has.
    @MainActor
    func handleEvents(completion: @escaping () -> Void) {
        eventsFinished = completion
        _ = session
    }

    /// Makes sure that iOS holds the heartbeats of this plan, or none with nil: nobody to report to.
    @MainActor
    func sync(_ plan: HeartbeatPlan?, relay: RelayClient) async {
        let previous = lastSync
        let sync = Task { @MainActor in
            await previous?.value
            await apply(plan, relay: relay)
        }
        lastSync = sync
        await sync.value
    }

    @MainActor
    private func apply(_ plan: HeartbeatPlan?, relay: RelayClient) async {
        let pending = await session.allTasks.filter { $0.state == .running || $0.state == .suspended }
        if let plan, plan.isCovered(byPending: pending.map(\.taskDescription)) { return }
        pending.forEach { $0.cancel() }
        guard let plan else { return }
        do {
            let upload = try relay.heartbeatUpload(buddyId: plan.buddyId)
            let file = try Self.writeBody(upload.body)
            for date in plan.beginDates(from: Date()) {
                let task = session.uploadTask(with: upload.request, fromFile: file)
                task.taskDescription = plan.key
                task.earliestBeginDate = date
                task.countOfBytesClientExpectsToSend = Int64(upload.body.count)
                task.countOfBytesClientExpectsToReceive = Self.expectedResponseBytes
                task.resume()
            }
        } catch {
            print("[Ariel] Background heartbeats not scheduled: \(error.localizedDescription)")
        }
    }

    /// Every heartbeat reads this file. It has to stay readable while the phone is locked, which is when
    /// they go out.
    private static func writeBody(_ body: Data) throws -> URL {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        let file = directory.appendingPathComponent("heartbeat.json")
        try body.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return file
    }

    // MARK: - URLSessionTaskDelegate

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error, (error as? URLError)?.code != .cancelled {
            print("[Ariel] Background heartbeat failed: \(error.localizedDescription)")
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let finished = eventsFinished
        eventsFinished = nil
        finished?()
    }
}
