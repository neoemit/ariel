import SwiftUI

/// A buddy's panic, on top of everything until it is acknowledged: the counterpart of Android's
/// IncomingAlertActivity.
struct IncomingAlertView: View {
    let alert: IncomingAlert

    @Environment(ArielModel.self) private var model
    @State private var pulse = false
    @State private var acknowledging = false

    var body: some View {
        ZStack {
            Color.arielErrorContainer.ignoresSafeArea()
            RoundedRectangle(cornerRadius: 36)
                .strokeBorder(Color.arielRed.opacity(pulse ? 1 : 0.4), lineWidth: 6)
                .ignoresSafeArea()
                .animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: pulse)

            VStack(spacing: 20) {
                Spacer()
                Text(alert.escalation.emoji)
                    .font(.system(size: 96))
                    .accessibilityHidden(true)
                Text("Incoming panic alert")
                    .font(.title.bold())
                Text(alert.escalation.alertBody(for: alert.displayName))
                    .font(.title3)
                    .multilineTextAlignment(.center)
                Text("Tap below to acknowledge and stop this alert on your device.")
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .opacity(0.85)
                Spacer()
                Button {
                    acknowledging = true
                    Task { await model.acknowledgeIncomingAlert() }
                } label: {
                    Text("I am coming!")
                        .font(.title3.bold())
                        .frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(.borderedProminent)
                .tint(.arielRed)
                .disabled(acknowledging)
                .accessibilityHint("Stops the alarm and tells \(alert.displayName) you are on your way")
            }
            .foregroundStyle(Color.arielOnErrorContainer)
            .padding(32)
        }
        .onAppear { pulse = true }
    }
}
