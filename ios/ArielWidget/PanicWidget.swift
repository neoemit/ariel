import AppIntents
import SwiftUI
import WidgetKit

/// The Home Screen and Lock Screen panic buttons, the counterpart of Android's PanicWidget: Medical,
/// Generic and Armed, and a tap sends that alert without opening Ariel (`WidgetPanicIntent`). The Lock
/// Screen's circle only has room for one, Generic.
@main
struct ArielWidgets: WidgetBundle {
    var body: some Widget {
        PanicWidget()
    }
}

struct PanicWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ArielPanicWidget", provider: Provider()) { _ in
            PanicWidgetView()
        }
        .configurationDisplayName("Panic Button")
        .description("One tap on Medical, Generic or Armed alerts every buddy in your panic pool.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular])
        .contentMarginsDisabled()
    }
}

extension WidgetPanicIntent {
    /// Never runs: iOS runs this intent in the app. The app has its own `perform()`.
    func perform() async throws -> some IntentResult {
        .result()
    }
}

private struct Provider: TimelineProvider {
    struct Entry: TimelineEntry {
        let date: Date
    }

    func placeholder(in context: Context) -> Entry { Entry(date: .now) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry(date: .now)], policy: .never))
    }
}

private struct PanicWidgetView: View {
    /// In the order of the Android widget.
    private static let escalations: [Escalation] = [.medical, .generic, .armed]

    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            Button(intent: WidgetPanicIntent(escalation: .generic)) {
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: 0) {
                        Image(systemName: "light.beacon.max.fill")
                            .font(.title3)
                        Text("SOS")
                            .font(.caption2.bold())
                    }
                }
            }
            .buttonStyle(.plain)
            .widgetAccentable()
            .containerBackground(for: .widget) { Color.clear }
            .accessibilityLabel(Escalation.generic.actionDescription)
        case .accessoryRectangular:
            HStack(spacing: 4) {
                ForEach(Self.escalations, id: \.self) { escalation in
                    PanicSegment(escalation: escalation) {
                        AccessoryWidgetBackground()
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .widgetAccentable()
            .containerBackground(for: .widget) { Color.clear }
        default:
            HStack(spacing: 2) {
                ForEach(Self.escalations, id: \.self) { escalation in
                    PanicSegment(escalation: escalation) { escalation.widgetColor }
                }
            }
            .foregroundStyle(.white)
            .containerBackground(for: .widget) { Color(red: 0x12 / 255, green: 0x12 / 255, blue: 0x12 / 255) }
        }
    }
}

private struct PanicSegment<Background: View>: View {
    let escalation: Escalation
    @ViewBuilder let background: Background

    var body: some View {
        Button(intent: WidgetPanicIntent(escalation: escalation)) {
            VStack(spacing: 2) {
                Text(escalation.emoji)
                    .font(.title3)
                Text(escalation.title)
                    .font(.caption2.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { background }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(escalation.actionDescription)
    }
}

private extension Escalation {
    /// The Android widget's colors.
    var widgetColor: Color {
        switch self {
        case .medical: Color(red: 0x15 / 255, green: 0x65 / 255, blue: 0xC0 / 255)
        case .generic: Color(red: 0xD3 / 255, green: 0x2F / 255, blue: 0x2F / 255)
        case .armed: Color(red: 0x6A / 255, green: 0x1B / 255, blue: 0x1A / 255)
        }
    }
}
