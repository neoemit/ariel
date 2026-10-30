import SwiftUI
import UserNotifications

struct SettingsView: View {
    @Environment(ArielModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var relayText = ""
    @State private var relayInvalid = false
    @State private var relayResult: String?
    @State private var testingRelay = false
    @State private var confirmingReset = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section {
                    Toggle("Discreet mode", isOn: $model.discreetMode)
                    LabeledContent("Alert sound", value: model.discreetMode ? "Off (Discreet mode)" : "Siren")
                } header: {
                    Text("Alerts")
                } footer: {
                    Text(model.discreetMode
                        ? "Panic and acknowledgment alerts vibrate only. The alert sound is ignored while Discreet mode is on."
                        : "Incoming panics play a siren, even with the Ring/Silent switch on silent.")
                }

                notificationsSection

                Section {
                    TextField(RelayURL.defaultString, text: $relayText)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .onSubmit(saveRelay)
                    if relayInvalid {
                        Text("Enter a valid http:// or https:// relay URL.")
                            .font(.footnote)
                            .foregroundStyle(Color.arielRed)
                    }
                    Button("Save Relay URL", action: saveRelay)
                        .disabled(relayText.trimmed == model.relayURLOverride)
                    Button {
                        testingRelay = true
                        Task {
                            relayResult = await model.testRelay()
                            testingRelay = false
                        }
                    } label: {
                        HStack {
                            Text("Test connection")
                            if testingRelay {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(testingRelay)
                    if let relayResult {
                        Text(relayResult)
                            .font(.footnote)
                    }
                    relayStatus
                } header: {
                    Text("Internet relay")
                } footer: {
                    Text("Set this to your Docker relay URL so alerts work over mobile data and the internet. Leave it empty for the default relay, \(RelayURL.defaultString).")
                }

                Section {
                    Button("Reset All Connections", role: .destructive) {
                        confirmingReset = true
                    }
                } header: {
                    Text("Danger zone")
                } footer: {
                    Text("Removes all buddy links and nicknames from this device.")
                }

                Section {
                    Text("Ariel helps you stay connected with your buddies and send emergency alerts with a single press. Alerts travel through the internet relay; on iPhone, Ariel cannot use the Android app's Bluetooth and Wi-Fi Nearby link.")
                        .font(.callout)
                    LabeledContent("Your ID", value: model.myId)
                    LabeledContent("Version", value: model.configuration.versionDescription)
                } header: {
                    Text("About")
                }
            }
            .navigationTitle("Settings")
            .onAppear { relayText = model.relayURLOverride }
            .confirmationDialog("Reset all buddy links?", isPresented: $confirmingReset, titleVisibility: .visible) {
                Button("Reset links", role: .destructive) { model.clearPool() }
            } message: {
                Text("This removes all paired buddies and nicknames from this device. You can pair again anytime.")
            }
        }
    }

    @ViewBuilder
    private var notificationsSection: some View {
        Section {
            LabeledContent("Delivery", value: model.deliveryMode == .push ? "Push notifications" : "In-app alerts")
            switch model.notificationStatus {
            case .notDetermined:
                Button("Allow notifications") {
                    Task { await model.requestNotifications() }
                }
            case .denied:
                LabeledContent("Notifications", value: "Not allowed")
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
                }
            default:
                LabeledContent("Notifications", value: "Allowed")
            }
        } header: {
            Text("Notifications")
        } footer: {
            switch model.deliveryMode {
            case .push:
                Text("Panic alerts arrive as notifications with the siren, even when Ariel is closed.")
            case .inApp:
                Text(model.configuration.pushRequested
                    ? "This build has no Firebase configuration, so there is no push: panic alerts reach this iPhone while Ariel is open. Keep it open, or check in regularly."
                    : "This build has no push (it is signed by a free Apple team): panic alerts reach this iPhone while Ariel is open, and now and then in the background. Keep it open, or check in regularly.")
            }
        }
    }

    @ViewBuilder
    private var relayStatus: some View {
        if let error = model.lastRelayError {
            Label("Last attempt failed: \(error)", systemImage: "exclamationmark.triangle.fill")
                .font(.footnote)
                .foregroundStyle(Color.arielRed)
        } else if let contact = model.lastRelayContact {
            Label {
                Text("Relay answered \(contact, format: .relative(presentation: .named))")
            } icon: {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }
            .font(.footnote)
        }
    }

    private func saveRelay() {
        relayInvalid = !model.setRelayURL(relayText)
        if !relayInvalid {
            relayText = model.relayURLOverride
            relayResult = nil
        }
    }
}

/// The first launch: what Ariel does on an iPhone, and the notification permission.
struct OnboardingView: View {
    @Environment(ArielModel.self) private var model

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "light.beacon.max.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.arielRed)
                .accessibilityHidden(true)
            Text("Welcome to Ariel")
                .font(.largeTitle.bold())
            VStack(alignment: .leading, spacing: 16) {
                Label("Hold the panic button for 1.5 seconds to alert every buddy in your panic pool.", systemImage: "hand.tap.fill")
                Label("Pair by scanning each other's QR codes on the Pairing tab.", systemImage: "qrcode")
                switch model.deliveryMode {
                case .push:
                    Label("Allow notifications so a buddy's panic reaches you even when Ariel is closed.", systemImage: "bell.badge.fill")
                case .inApp:
                    Label("This build has no push: a buddy's panic reaches you while Ariel is open. Allow notifications for the ones it finds in the background.", systemImage: "bell.badge.fill")
                }
            }
            .font(.callout)
            Spacer()
            Button {
                Task {
                    await model.requestNotifications()
                    model.completeOnboarding()
                }
            } label: {
                Text("Allow notifications")
                    .bold()
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            Button("Not now") { model.completeOnboarding() }
        }
        .padding(32)
        .preferredColorScheme(.dark)
        .tint(.arielRed)
    }
}
