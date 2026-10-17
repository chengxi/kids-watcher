import AVFoundation
import SwiftUI

struct QRScanner: View {
  let onScan: (String) -> Void
  @State private var allowed = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
  var body: some View {
    Group {
      if allowed {
        ScannerPreview(onScan: onScan)
      } else {
        VStack(spacing: 16) {
          Text("Camera access is needed to scan the pairing code.")
          Button("Grant camera access") {
            Task {
              if AVCaptureDevice.authorizationStatus(for: .video) == .denied {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                  await UIApplication.shared.open(url)
                }
              } else {
                allowed = await AVCaptureDevice.requestAccess(for: .video)
              }
            }
          }
        }.font(.system(size: 14)).padding(16).frame(maxWidth: .infinity, maxHeight: .infinity)
          .background(Palette.line)
      }
    }.task {
      if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
        allowed = await AVCaptureDevice.requestAccess(for: .video)
      }
    }.onAppear { UIApplication.shared.isIdleTimerDisabled = true }.onDisappear {
      UIApplication.shared.isIdleTimerDisabled = false
    }
  }
}
private struct ScannerPreview: UIViewRepresentable {
  let onScan: (String) -> Void
  func makeUIView(context: Context) -> ScannerView {
    let view = ScannerView()
    view.onScan = onScan
    view.start()
    return view
  }
  func updateUIView(_ uiView: ScannerView, context: Context) {}
  static func dismantleUIView(_ uiView: ScannerView, coordinator: ()) { uiView.stop() }
}
private final class ScannerView: UIView, AVCaptureMetadataOutputObjectsDelegate {
  private let session = AVCaptureSession()
  private let queue = DispatchQueue(label: "kidsmonitor.qr")
  private var sent = false
  var onScan: ((String) -> Void)?
  override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
  func start() {
    (layer as? AVCaptureVideoPreviewLayer)?.session = session
    (layer as? AVCaptureVideoPreviewLayer)?.videoGravity = .resizeAspectFill
    queue.async {
      guard let device = AVCaptureDevice.default(for: .video),
        let input = try? AVCaptureDeviceInput(device: device), self.session.canAddInput(input)
      else { return }
      self.session.addInput(input)
      let output = AVCaptureMetadataOutput()
      guard self.session.canAddOutput(output) else { return }
      self.session.addOutput(output)
      output.setMetadataObjectsDelegate(self, queue: .main)
      output.metadataObjectTypes = [.qr]
      self.session.startRunning()
    }
  }
  func stop() { queue.async { if self.session.isRunning { self.session.stopRunning() } } }
  func metadataOutput(
    _ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
    from connection: AVCaptureConnection
  ) {
    guard !sent, let raw = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue
    else { return }
    sent = true
    onScan?(raw)
    // Allow a retry after an invalid code without destroying the scanner.
    DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.sent = false }
  }
}
