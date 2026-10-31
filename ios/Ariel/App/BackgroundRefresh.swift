import BackgroundTasks
import Foundation

/// Background app refresh, the closest iOS has to Android's always-on monitoring service. iOS decides
/// when (and whether) it runs, so it is a safety net: it keeps this device's presence and push
/// registration fresh, and in builds without push looks for panics, which it shows as notifications.
enum BackgroundRefresh {
    static var identifier: String {
        (Bundle.main.bundleIdentifier ?? "ariel") + ".refresh"
    }

    @MainActor
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refresh)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Simulators and devices with Background App Refresh off refuse it.
            print("[Ariel] Background refresh not scheduled: \(error.localizedDescription)")
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()
        let work = Task { @MainActor in
            await ArielModel.shared.performBackgroundRefresh()
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { work.cancel() }
    }
}
