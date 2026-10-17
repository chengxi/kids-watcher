import AVFoundation
import CoreImage
import CoreMotion
import ImageIO
import MonitorCore

struct CameraFormat {
  let width: Int, height: Int, facing: String, cameras: [String]
  var json: [String: Any] {
    [
      "width": width, "height": height, "fps": 10, "rotationDegrees": 0, "facing": facing,
      "availableCameras": cameras,
    ]
  }
}

final class CameraCapture: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate,
  @unchecked Sendable
{
  private let queue = DispatchQueue(label: "kidsmonitor.camera")
  private let session = AVCaptureSession()
  private let context = CIContext(options: [.cacheIntermediates: false])
  private let motion = CMMotionManager()
  private var orientation: CGImagePropertyOrientation = .right
  private var lastFrame = 0.0
  private var generation = 0
  private var facing = "back"
  private var proving: ((CameraFormat?) -> Void)?
  private var onFrame: ((Data, CameraFormat) -> Void)?
  var onFailure: (() -> Void)?
  private var observers: [NSObjectProtocol] = []
  override init() {
    super.init()
    // This session captures video only. AudioCapture owns the shared audio
    // session, including measurement/voice mode and the speaker route.
    session.automaticallyConfiguresApplicationAudioSession = false
    for name in [
      AVCaptureSession.wasInterruptedNotification, AVCaptureSession.runtimeErrorNotification,
    ] {
      observers.append(
        NotificationCenter.default.addObserver(forName: name, object: session, queue: nil) {
          [weak self] _ in self?.onFailure?()
        })
    }
  }
  deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
  static var cameras: [String] {
    [AVCaptureDevice.Position.back, .front].compactMap {
      AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: $0) == nil
        ? nil : ($0 == .back ? "back" : "front")
    }
  }
  func start(facing: String, onFrame: @escaping (Data, CameraFormat) -> Void) async -> CameraFormat?
  {
    await withCheckedContinuation { continuation in
      queue.async {
        self.stopLocked()
        self.generation += 1
        let generation = self.generation
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized,
          let device = AVCaptureDevice.default(
            .builtInWideAngleCamera, for: .video, position: facing == "front" ? .front : .back),
          let input = try? AVCaptureDeviceInput(device: device)
        else {
          continuation.resume(returning: nil)
          return
        }
        self.session.beginConfiguration()
        self.session.sessionPreset = .vga640x480
        guard self.session.canAddInput(input) else {
          self.session.commitConfiguration()
          continuation.resume(returning: nil)
          return
        }
        self.session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
          kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        output.setSampleBufferDelegate(self, queue: self.queue)
        guard self.session.canAddOutput(output) else {
          self.session.commitConfiguration()
          self.stopLocked()
          continuation.resume(returning: nil)
          return
        }
        self.session.addOutput(output)
        if let connection = output.connection(with: .video) {
          // Keep sensor pixels unmirrored; JPEGs are then oriented using gravity.
          connection.videoOrientation = .landscapeRight
          if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
          }
        }
        self.session.commitConfiguration()
        self.facing = facing
        self.onFrame = onFrame
        self.proving = { continuation.resume(returning: $0) }
        self.lastFrame = 0
        if self.motion.isAccelerometerAvailable {
          self.motion.accelerometerUpdateInterval = 0.25
          self.motion.startAccelerometerUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let data, let self else { return }
            self.queue.async {
              let g = data.acceleration
              if abs(g.x) > 0.75 {
                self.orientation = g.x > 0 ? .down : .up
              } else if abs(g.y) > 0.75 {
                self.orientation = g.y > 0 ? .left : .right
              }
            }
          }
        }
        self.session.startRunning()
        self.queue.asyncAfter(deadline: .now() + 8) {
          if self.generation == generation && self.proving != nil { self.stopLocked() }
        }
      }
    }
  }
  func cancel() { queue.async { self.stopLocked() } }
  func stop() async {
    await withCheckedContinuation { continuation in
      queue.async {
        self.stopLocked()
        continuation.resume()
      }
    }
  }
  private func stopLocked() {
    generation += 1
    onFrame = nil
    let reply = proving
    proving = nil
    reply?(nil)
    motion.stopAccelerometerUpdates()
    if session.isRunning { session.stopRunning() }
    session.beginConfiguration()
    for input in session.inputs { session.removeInput(input) }
    for output in session.outputs { session.removeOutput(output) }
    session.commitConfiguration()
  }
  func captureOutput(
    _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    let now = ProcessInfo.processInfo.systemUptime
    guard now - lastFrame >= 0.1, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
    else { return }
    lastFrame = now
    let image = CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation)
    guard
      let jpeg = context.jpegRepresentation(
        of: image, colorSpace: CGColorSpaceCreateDeviceRGB(),
        options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.6])
    else { return }
    let format = CameraFormat(
      width: Int(image.extent.width), height: Int(image.extent.height), facing: facing,
      cameras: Self.cameras)
    if let reply = proving {
      proving = nil
      reply(format)
    }  // advertise only after capture is proven
    onFrame?(jpeg, format)
  }
}
