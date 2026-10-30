import SwiftUI
import WidgetKit

/// The Home Screen and Lock Screen panic button, the counterpart of Android's PanicWidget. Tapping it
/// opens Ariel through `ariel://panic`, and the app sends a generic panic straight away. A link needs
/// no App Group, so the widget works in builds signed by a free Apple team too.
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
                .widgetURL(DeepLink.panicURL)
        }
        .configurationDisplayName("Panic Button")
        .description("One tap alerts every buddy in your panic pool.")
        .supportedFamilies([.systemSmall, .accessoryCircular])
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
    @Environment(\.widgetFamily) private var family

    private static let red = Color(red: 0xDB / 255, green: 0x1F / 255, blue: 0x2B / 255)

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Image(systemName: "light.beacon.max.fill")
                        .font(.title3)
                    Text("SOS")
                        .font(.caption2.bold())
                }
            }
            .widgetAccentable()
            .containerBackground(for: .widget) { Color.clear }
            .accessibilityLabel("Send panic alert")
        default:
            VStack(spacing: 6) {
                Image(systemName: "light.beacon.max.fill")
                    .font(.system(size: 40, weight: .bold))
                Text("PANIC")
                    .font(.headline.weight(.black))
                Text("Tap to alert buddies")
                    .font(.caption2)
                    .opacity(0.85)
            }
            .foregroundStyle(.white)
            .containerBackground(for: .widget) { Self.red }
            .accessibilityLabel("Send panic alert")
        }
    }
}
