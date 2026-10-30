import SwiftUI

/// The buddy pool: this device's QR code for others to scan, and the buddies it alerts.
struct PairingView: View {
    @Environment(ArielModel.self) private var model
    @State private var scanning = false
    @State private var scannedCode: String?
    @State private var enteringId = false
    @State private var typedId = ""
    @State private var editing: Buddy?
    @State private var nickname = ""
    @State private var removing: Buddy?
    @State private var message: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 12) {
                        Text("Share your QR code and scan trusted buddies to build your panic pool.")
                            .font(.callout)
                            .foregroundStyle(Color.arielOnSurfaceVariant)
                            .multilineTextAlignment(.center)
                        QRCodeView(text: model.myId)
                            .frame(width: 200, height: 200)
                            .padding(12)
                            .background(.white, in: RoundedRectangle(cornerRadius: 16))
                        Text("Your ID: \(model.myId)")
                            .font(.headline)
                            .textSelection(.enabled)
                        ShareLink(item: model.myId) {
                            Label("Share ID", systemImage: "square.and.arrow.up")
                        }
                        .font(.callout)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                } header: {
                    Text("Your safety network")
                }

                Section {
                    Button {
                        scanning = true
                    } label: {
                        Label("Scan buddy QR code", systemImage: "qrcode.viewfinder")
                    }
                    Button {
                        typedId = ""
                        enteringId = true
                    } label: {
                        Label("Enter buddy ID", systemImage: "keyboard")
                    }
                }

                Section {
                    if model.friends.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("No friends paired yet.")
                            Text("Scan a buddy QR code to start your panic pool.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    ForEach(model.friends, id: \.self) { id in
                        let buddy = Buddy(id: id, displayName: model.displayName(for: id))
                        BuddyRow(buddy: buddy, online: model.onlineBuddies.contains { $0.id == id })
                            .swipeActions {
                                Button("Delete", role: .destructive) { removing = buddy }
                                Button("Nickname") { startEditing(buddy) }
                                    .tint(.gray)
                            }
                            .contextMenu {
                                Button("Edit Nickname", systemImage: "pencil") { startEditing(buddy) }
                                Button("Delete Friend", systemImage: "trash", role: .destructive) { removing = buddy }
                            }
                    }
                } header: {
                    Text(model.friends.count == 1 ? "Your panic pool · 1 buddy" : "Your panic pool · \(model.friends.count) buddies")
                } footer: {
                    Text("The trusted people who receive and respond to your panic alerts. Swipe a buddy to rename or remove them.")
                }
            }
            .navigationTitle("Pairing")
            .sheet(isPresented: $scanning, onDismiss: {
                // Added once the scanner is gone, so that a refusal can show.
                if let code = scannedCode {
                    scannedCode = nil
                    add(code)
                }
            }) {
                QRScannerView { code in
                    scannedCode = code
                    scanning = false
                }
            }
            .alert("Enter buddy ID", isPresented: $enteringId) {
                TextField("User_1234", text: $typedId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Add") { add(typedId) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The ID shown under your buddy's QR code.")
            }
            .alert("Set Nickname", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
                TextField("Nickname", text: $nickname)
                Button("Save") {
                    if let editing { model.setNickname(nickname, for: editing.id) }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("ID: \(editing?.id ?? "")")
            }
            .confirmationDialog(
                "Remove \(removing?.displayName ?? "")?",
                isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                titleVisibility: .visible
            ) {
                Button("Delete Friend", role: .destructive) {
                    if let removing { model.removeFriend(removing.id) }
                }
            } message: {
                Text("They will no longer get your panic alerts.")
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
    }

    private func startEditing(_ buddy: Buddy) {
        nickname = model.nicknames[buddy.id] ?? ""
        editing = buddy
    }

    private func add(_ code: String) {
        if let refusal = model.addFriend(code) {
            message = refusal
        }
    }
}

private struct BuddyRow: View {
    let buddy: Buddy
    let online: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill")
                .font(.title2)
                .foregroundStyle(Color.arielOnSurfaceVariant)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(buddy.displayName)
                if buddy.displayName != buddy.id {
                    Text(buddy.id)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if online {
                Circle()
                    .fill(.green)
                    .frame(width: 8, height: 8)
                    .accessibilityLabel("Online")
            }
        }
    }
}
