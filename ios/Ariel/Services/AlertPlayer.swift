import AudioToolbox
import AVFoundation
import UIKit

/// The siren of an incoming panic, the counterpart of SirenService's MediaPlayer. It plays through the
/// `.playback` audio session, so the Ring/Silent switch does not mute it, and the `audio` background
/// mode keeps it going when the phone is locked while it rings. Discreet mode vibrates only.
@MainActor
final class AlertPlayer {
    /// Android's discreet pattern: 450 ms on, 300 ms off.
    private static let vibrationInterval: Duration = .milliseconds(750)

    private var player: AVAudioPlayer?
    private var vibration: Task<Void, Never>?

    var isPlaying: Bool { player?.isPlaying == true || vibration != nil }

    func start(discreet: Bool) {
        startVibration()
        guard !discreet, player == nil else { return }
        guard let url = Bundle.main.url(forResource: "siren", withExtension: "wav") else {
            assertionFailure("siren.wav is missing from the app bundle")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url)
            player.numberOfLoops = -1
            player.volume = 1
            player.play()
            self.player = player
        } catch {
            print("[Ariel] Could not play the siren: \(error.localizedDescription)")
        }
    }

    func stop() {
        vibration?.cancel()
        vibration = nil
        guard let player else { return }
        player.stop()
        self.player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// A short confirmation when a buddy acknowledges this device's panic.
    func playAcknowledgment() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    private func startVibration() {
        guard vibration == nil else { return }
        vibration = Task {
            while !Task.isCancelled {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(for: Self.vibrationInterval)
            }
        }
    }
}
