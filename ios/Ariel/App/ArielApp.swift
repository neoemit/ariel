import SwiftUI

@main
struct ArielApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(ArielModel.shared)
                .preferredColorScheme(.dark)
                .tint(.arielRed)
                .onOpenURL(perform: open)
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            ArielModel.shared.setActive(phase == .active)
        }
    }

    private func open(_ url: URL) {
        switch DeepLink(url: url) {
        case .panic(let type):
            ArielModel.shared.triggerPanic(type.map { Escalation(wireValue: $0) })
        case nil:
            break
        }
    }
}
