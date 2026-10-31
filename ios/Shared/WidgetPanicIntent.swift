import AppIntents

/// A tap on one of the widget's buttons. Being a `LiveActivityIntent` makes iOS run it in the app's
/// process, launching the app in the background if need be, without opening it; no Live Activity is
/// involved. Only the app has the buddies and the relay: sharing them with the widget would take an
/// App Group, which a free Apple team cannot sign.
///
/// Both targets compile this, as the widget's `Button` needs the type. `perform()` is in
/// Intents/PanicIntents.swift for the app; the widget's never runs.
///
/// The alert type is its wire value rather than an `AppEnum`: an enum shared between the targets does
/// not reach the app on iOS 26.5 (the intent is cancelled and nothing is sent).
struct WidgetPanicIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Send Panic Alert"
    static let isDiscoverable = false

    @Parameter(title: "Alert Type")
    var escalationType: String

    init() {}

    init(escalation: Escalation) {
        escalationType = escalation.rawValue
    }
}
