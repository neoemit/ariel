import SwiftUI

struct RootView: View {
    private enum Tab: Hashable {
        case panic, pairing, settings
    }

    @Environment(ArielModel.self) private var model
    @State private var tab: Tab = .panic

    var body: some View {
        TabView(selection: $tab) {
            PanicView()
                .tabItem { Label("Panic", systemImage: "bell.fill") }
                .tag(Tab.panic)
            PairingView()
                .tabItem { Label("Pairing", systemImage: "person.2.fill") }
                .tag(Tab.pairing)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(Tab.settings)
        }
        // Only acknowledging closes it, as Android's IncomingAlertActivity.
        .fullScreenCover(item: Binding(get: { model.incomingAlert }, set: { _ in })) { alert in
            IncomingAlertView(alert: alert)
                .environment(model)
                .preferredColorScheme(.dark)
        }
        .sheet(isPresented: Binding(get: { model.needsOnboarding }, set: { if !$0 { model.completeOnboarding() } })) {
            OnboardingView()
                .environment(model)
                .interactiveDismissDisabled()
        }
        .onChange(of: model.isPanicTriggered) { _, triggered in
            // The widget, a link or Shortcuts raised it: show where it stands.
            if triggered { tab = .panic }
        }
    }
}
