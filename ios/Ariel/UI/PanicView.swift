import SwiftUI

struct PanicView: View {
    @Environment(ArielModel.self) private var model
    @State private var showingBuddies = false

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 0) {
            if let pending = model.acknowledgmentFailed {
                Button {
                    Task { await model.retryAcknowledgment() }
                } label: {
                    Label("Could not tell \(pending.displayName) you are coming. Tap to try again.",
                          systemImage: "exclamationmark.triangle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(12)
                        .frame(maxWidth: .infinity)
                        .background(Color.arielRedDark, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .padding(.bottom, 8)
            }
            if let name = model.acknowledgedBy {
                AcknowledgmentBanner(name: name)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            Spacer(minLength: 16)
            BuddyStatusBadge(showingBuddies: $showingBuddies)
                .padding(.bottom, 40)
            if model.isPanicTriggered {
                PanicSentView()
            } else {
                PanicButton(escalation: $model.selectedEscalation) {
                    model.triggerPanic()
                }
                .frame(maxWidth: 380)
                Text("HOLD BUTTON FOR 1.5 SECONDS TO TRIGGER")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.arielOnSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.top, 24)
            }
            Spacer(minLength: 16)
            if model.alertsNeedOpenApp {
                Label("Alerts reach this iPhone while Ariel is open.", systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(Color.arielOnSurfaceVariant)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.arielSurface)
        .animation(.default, value: model.acknowledgedBy)
        // The incoming alert covers everything; it cannot while a sheet is up.
        .onChange(of: model.incomingAlert?.id) { _, id in
            if id != nil { showingBuddies = false }
        }
        .sheet(isPresented: $showingBuddies) {
            OnlineBuddiesSheet()
                .environment(model)
                .presentationDetents([.medium, .large])
        }
    }
}

private struct BuddyStatusBadge: View {
    @Environment(ArielModel.self) private var model
    @Binding var showingBuddies: Bool

    var body: some View {
        let status = model.presenceStatus
        Button {
            showingBuddies = true
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(status.dot)
                    .frame(width: 8, height: 8)
                Text(status.label(online: model.onlineBuddies.count, recentlySeen: model.recentlySeenBuddies.count))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(status.background, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Shows which buddies are online")
    }
}

private struct OnlineBuddiesSheet: View {
    @Environment(ArielModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let showingRecent = model.presenceStatus == .recentlySeenChecking
        let buddies = showingRecent ? model.recentlySeenBuddies : model.onlineBuddies
        NavigationStack {
            List {
                if buddies.isEmpty {
                    Text("No buddies are currently online.")
                        .foregroundStyle(.secondary)
                } else {
                    Section {
                        ForEach(buddies) { buddy in
                            VStack(alignment: .leading) {
                                Text(buddy.displayName)
                                if buddy.displayName != buddy.id {
                                    Text(buddy.id)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    } footer: {
                        if showingRecent {
                            Text("These buddies were seen recently. Ariel is checking current availability now, and panic alerts are still sent to all trusted buddies.")
                        }
                    }
                }
            }
            .navigationTitle("Buddies Online")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

/// After the hold: where the alert stands, and the reset.
private struct PanicSentView: View {
    @Environment(ArielModel.self) private var model

    var body: some View {
        VStack(spacing: 16) {
            Text("ALERT ACTIVE")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Color.arielRed)
            switch model.panicDelivery {
            case .idle, .sending:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Notifying your buddies…")
                }
                .foregroundStyle(Color.arielOnSurfaceVariant)
            case .sent:
                Text("Your buddies have been notified")
                    .foregroundStyle(Color.arielOnSurfaceVariant)
                Text(model.selectedEscalation.sentConfirmation)
                    .font(.callout.weight(.medium))
            case .failed:
                Label("The relay could not be reached, so your buddies may not know.", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(Color.arielRed)
                    .multilineTextAlignment(.center)
                RetryPanicButton()
            case .undelivered:
                Label(
                    "The relay could not alert \(model.unreachedNames ?? "some buddies"), so they may not know.",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .foregroundStyle(Color.arielRed)
                .multilineTextAlignment(.center)
                RetryPanicButton()
            case .noBuddies:
                Text("Your panic pool is empty: add buddies on the Pairing tab first.")
                    .foregroundStyle(Color.arielOnSurfaceVariant)
                    .multilineTextAlignment(.center)
            }
            Button {
                model.resetPanic()
            } label: {
                Text("RESET PANIC")
                    .bold()
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .padding(.top, 16)
        }
        .frame(maxWidth: 320)
    }
}

private struct RetryPanicButton: View {
    @Environment(ArielModel.self) private var model

    var body: some View {
        Button {
            model.retryPanic()
        } label: {
            Text("TRY AGAIN")
                .bold()
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(.arielRed)
    }
}

private struct AcknowledgmentBanner: View {
    let name: String

    var body: some View {
        Label("\(name) has acknowledged your alert!", systemImage: "checkmark.circle.fill")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(Color.presenceOnline, in: RoundedRectangle(cornerRadius: 12))
            .accessibilityAddTraits(.isStaticText)
    }
}
