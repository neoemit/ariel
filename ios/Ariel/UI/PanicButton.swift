import SwiftUI
import UIKit

/// The round panic button, in three segments like Android's SegmentedPanicButton: generic at the
/// bottom, medical top left, armed top right. Touching a segment picks it; holding for
/// `holdDuration` (sliding between segments is fine) sends the panic. Lifting the finger or leaving the
/// button starts over.
struct PanicButton: View {
    static let holdDuration: TimeInterval = 1.5

    @Binding var escalation: Escalation
    let onTrigger: () -> Void

    @State private var progress: Double = 0
    @State private var hold: Task<Void, Never>?

    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let ringWidth: CGFloat = 8
            let buttonSide = side - 2 * ringWidth - 6
            ZStack {
                Circle()
                    .stroke(Color.arielSurfaceVariant, lineWidth: ringWidth)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(Color.arielRed, style: StrokeStyle(lineWidth: ringWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                segments(side: buttonSide)
                    .frame(width: buttonSide, height: buttonSide)
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func segments(side: CGFloat) -> some View {
        let radius = side / 2
        let center = CGPoint(x: radius, y: radius)
        return ZStack {
            ForEach(Escalation.allCases, id: \.self) { segment in
                SectorShape(startDegrees: segment.startDegrees, endDegrees: segment.startDegrees + 120)
                    .fill(segment == escalation ? Color.arielRed : Color.arielRed.opacity(0.72))
            }
            Circle()
                .stroke(Color.white.opacity(0.55), lineWidth: 2)
            DividerShape(angles: Escalation.allCases.map(\.startDegrees))
                .stroke(Color.white.opacity(0.55), lineWidth: 2)
            ForEach(Escalation.allCases, id: \.self) { segment in
                let angle = segment.iconDegrees * .pi / 180
                Text(segment.emoji)
                    .font(.system(size: min(side * 0.13, 52)))
                    .position(
                        x: center.x + cos(angle) * side * 0.30,
                        y: center.y + sin(angle) * side * 0.30
                    )
                    .accessibilityHidden(true)
            }
        }
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    if let segment = Escalation.segment(at: value.location, center: center, radius: radius) {
                        if segment != escalation {
                            escalation = segment
                            UISelectionFeedbackGenerator().selectionChanged()
                        }
                        startHold()
                    } else {
                        cancelHold()
                    }
                }
                .onEnded { _ in cancelHold() }
        )
        .accessibilityElement()
        .accessibilityLabel("Panic button")
        .accessibilityValue(escalation.title)
        .accessibilityHint("Hold for one and a half seconds to alert your buddies. Use the actions to pick the alert type and send it.")
        .accessibilityAddTraits(.isButton)
        .accessibilityActions {
            ForEach(Escalation.allCases, id: \.self) { segment in
                Button(segment.actionDescription) {
                    escalation = segment
                    onTrigger()
                }
            }
        }
    }

    private func startHold() {
        guard hold == nil else { return }
        let start = Date()
        hold = Task { @MainActor in
            let haptic = UIImpactFeedbackGenerator(style: .medium)
            haptic.prepare()
            var lastBuzz = 0.0
            while !Task.isCancelled {
                let value = min(Date().timeIntervalSince(start) / Self.holdDuration, 1)
                progress = value
                if value - lastBuzz >= 0.1 {
                    haptic.impactOccurred()
                    lastBuzz = value
                }
                if value >= 1 {
                    UINotificationFeedbackGenerator().notificationOccurred(.warning)
                    hold = nil
                    progress = 0
                    onTrigger()
                    return
                }
                try? await Task.sleep(for: .milliseconds(16))
            }
        }
    }

    private func cancelHold() {
        hold?.cancel()
        hold = nil
        progress = 0
    }
}

private extension Escalation {
    /// Where each segment starts, clockwise from three o'clock (screen coordinates, y down), as on
    /// Android: each spans 120°.
    var startDegrees: Double {
        switch self {
        case .generic: 30
        case .medical: 150
        case .armed: 270
        }
    }

    var iconDegrees: Double { startDegrees + 60 }

    /// The segment under a point, nil outside the button.
    static func segment(at point: CGPoint, center: CGPoint, radius: CGFloat) -> Escalation? {
        let dx = point.x - center.x
        let dy = point.y - center.y
        guard dx * dx + dy * dy <= radius * radius else { return nil }
        let degrees = (atan2(dy, dx) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
        switch degrees {
        case 30..<150: return .generic
        case 150..<270: return .medical
        default: return .armed
        }
    }
}

/// A pie slice, drawn point by point so that angles mean the same as in `Escalation.segment(at:)`.
private struct SectorShape: Shape {
    let startDegrees: Double
    let endDegrees: Double

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        path.move(to: center)
        let steps = 48
        for step in 0...steps {
            let angle = (startDegrees + (endDegrees - startDegrees) * Double(step) / Double(steps)) * .pi / 180
            path.addLine(to: CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius))
        }
        path.closeSubpath()
        return path
    }
}

private struct DividerShape: Shape {
    let angles: [Double]

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        for degrees in angles {
            let angle = degrees * .pi / 180
            path.move(to: center)
            path.addLine(to: CGPoint(x: center.x + cos(angle) * radius, y: center.y + sin(angle) * radius))
        }
        return path
    }
}
