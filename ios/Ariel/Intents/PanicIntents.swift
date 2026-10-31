import AppIntents

/// The kinds of help, for Shortcuts.
enum EscalationOption: String, AppEnum {
    case generic = "GENERIC"
    case medical = "MEDICAL"
    case armed = "ARMED"

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Alert Type"
    static let caseDisplayRepresentations: [EscalationOption: DisplayRepresentation] = [
        .generic: DisplayRepresentation(title: "Generic", subtitle: "Urgent assistance"),
        .medical: DisplayRepresentation(title: "Medical", subtitle: "Medical assistance"),
        .armed: DisplayRepresentation(title: "Armed", subtitle: "Armed response"),
    ]
}

/// "Send Panic Alert" in Shortcuts, Siri and the Action Button. It opens Ariel, which sends the alert
/// to every trusted buddy.
struct SendPanicIntent: AppIntent {
    static let title: LocalizedStringResource = "Send Panic Alert"
    static let description = IntentDescription("Alerts every buddy in your panic pool straight away.")
    static let openAppWhenRun = true

    @Parameter(title: "Alert Type", default: .generic)
    var escalation: EscalationOption

    static var parameterSummary: some ParameterSummary {
        Summary("Send a \(\.$escalation) panic alert")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let dialog: IntentDialog = switch await ArielModel.shared.sendQuickPanic(Escalation(wireValue: escalation.rawValue)) {
        case .noBuddies: "Your panic pool is empty. Add buddies in Ariel first."
        case .failed: "The relay could not be reached, so your buddies may not know. Try again in Ariel."
        case .undelivered: "The relay could not alert some of your buddies, so they may not know. Try again in Ariel."
        case .idle, .sending: "Your panic alert is on its way."
        case .sent: "Panic alert sent to your buddies."
        }
        return .result(dialog: dialog)
    }
}

extension WidgetPanicIntent {
    @MainActor
    func perform() async throws -> some IntentResult {
        await ArielModel.shared.sendQuickPanic(Escalation(wireValue: escalationType))
        return .result()
    }
}

struct ArielShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SendPanicIntent(),
            phrases: [
                "Send a panic alert with \(.applicationName)",
                "\(.applicationName) panic",
                "Alert my \(.applicationName) buddies",
            ],
            shortTitle: "Panic Alert",
            systemImageName: "light.beacon.max.fill"
        )
    }
}
