@preconcurrency import AVFoundation
import SwiftUI
import UIKit

/// Scans a buddy's QR code with the camera. Calls `onCode` once, with the first QR code's text.
struct QRScannerView: View {
    let onCode: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        NavigationStack {
            Group {
                switch access {
                case .authorized:
                    CameraPreview(onCode: onCode)
                        .ignoresSafeArea(edges: .bottom)
                        .overlay {
                            RoundedRectangle(cornerRadius: 24)
                                .strokeBorder(.white.opacity(0.8), lineWidth: 3)
                                .frame(width: 240, height: 240)
                                .accessibilityHidden(true)
                        }
                case .notDetermined:
                    ProgressView()
                        .task {
                            _ = await AVCaptureDevice.requestAccess(for: .video)
                            access = AVCaptureDevice.authorizationStatus(for: .video)
                        }
                default:
                    ContentUnavailableView {
                        Label("Camera not allowed", systemImage: "camera.fill")
                    } description: {
                        Text("Allow Ariel to use the camera to scan your buddies' QR codes.")
                    } actions: {
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                        }
                    }
                }
            }
            .navigationTitle("Scan buddy QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

private struct CameraPreview: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: ScannerViewController, context: Context) {}
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "ariel.qr-scanner")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var delivered = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            showUnavailable()
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            showUnavailable()
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        previewLayer = preview
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let session = session
        sessionQueue.async { if !session.isRunning { session.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let session = session
        sessionQueue.async { if session.isRunning { session.stopRunning() } }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !delivered,
              let code = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first
        else { return }
        delivered = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onCode?(code)
    }

    /// The Simulator, or a device without a usable camera.
    private func showUnavailable() {
        let label = UILabel()
        label.text = "No camera available. Use \"Enter buddy ID\" instead."
        label.textColor = .white
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
        ])
    }
}
