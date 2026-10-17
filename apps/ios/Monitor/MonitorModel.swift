import AVFoundation
import MonitorCore
import SwiftUI

@MainActor
final class MonitorModel: ObservableObject {
  @Published var credentials: Credentials?
  @Published var name: String
  @Published var setup = "name"
  @Published var busy = false
  @Published var error: String?
  @Published var fingerprint: String?
  @Published var host = ""
  @Published var port = "8444"
  @Published var code = ""
  @Published var listening = false
  @Published var connected = false
  @Published var watching = false
  @Published var talking = false
  @Published var calibrating = false
  @Published var level = silenceDb
  @Published var floor = silenceDb
  @Published var threshold = 12.0
  @Published var lastEvent: Date?
  @Published var connectionText = "Stopped"
  @Published var battery: Int?
  @Published var charging = false
  @Published var videoNotice: String?
  private let store = CredentialStore()
  private var hub: HubTransport?
  private var audio: AudioCapturing?
  private let camera: CameraCapturing
  private let audioFactory: () -> AudioCapturing
  private let connectionFactory: @MainActor (Credentials) -> HubTransport
  private let microphonePermission: () async -> Bool
  private var detector = NoiseDetector()
  private var history = NoiseHistory()
  private var calibration: Calibrator?
  private var stream: UInt16?, talkId: UInt16?
  private var streamStart = 0.0
  private var audioWanted = false, videoWanted = false, announced = false
  private var videoFormat: CameraFormat?
  private var facing = "back"
  private var streamGeneration = 0, captureGeneration = 0
  private var lastTalkFrame = Date()
  private var lastAudioWindow = Date()
  private var sweep: Task<Void, Never>?
  private var foreground = true
  private var observers: [NSObjectProtocol] = []
  init(
    loadCredentials: () -> Credentials? = { CredentialStore().load() },
    defaultName: @MainActor () -> String = { DeviceName.current },
    camera: CameraCapturing = CameraCapture(),
    audioFactory: @escaping () -> AudioCapturing = { AudioCapture() },
    connectionFactory: @escaping @MainActor (Credentials) -> HubTransport = { HubConnection($0) },
    microphonePermission: @escaping () async -> Bool = {
      await withCheckedContinuation { continuation in
        AVAudioSession.sharedInstance().requestRecordPermission {
          continuation.resume(returning: $0)
        }
      }
    }
  ) {
    self.camera = camera
    self.audioFactory = audioFactory
    self.connectionFactory = connectionFactory
    self.microphonePermission = microphonePermission
    let savedCredentials = loadCredentials()
    credentials = savedCredentials
    name = savedCredentials?.name ?? defaultName()
    UIDevice.current.isBatteryMonitoringEnabled = true
    refreshBattery()
    for notification in [
      UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification,
    ] {
      observers.append(
        NotificationCenter.default.addObserver(forName: notification, object: nil, queue: .main) {
          [weak self] _ in Task { @MainActor in self?.refreshBattery() }
        })
    }
    observers.append(
      NotificationCenter.default.addObserver(
        forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
      ) { [weak self] notification in
        guard let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
          type == AVAudioSession.InterruptionType.began.rawValue
        else { return }
        Task { @MainActor in
          self?.stop()
          self?.error =
            "Microphone interrupted. Tap Start when this phone is ready to listen again."
        }
      })
    observers.append(
      NotificationCenter.default.addObserver(
        forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main
      ) { [weak self] _ in
        Task { @MainActor in
          self?.stop()
          self?.error = "Audio system restarted. Tap Start to resume monitoring."
        }
      })
    camera.onFailure = { [weak self] in Task { @MainActor in self?.cameraUnavailable() } }
  }
  deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
  func continueName() {
    guard monitorNameError(name) == nil else { return }
    name = name.trimmingCharacters(in: .whitespacesAndNewlines)
    setup = "qr"
    error = nil
  }
  func pairQR(_ raw: String) {
    guard setup == "qr", !busy, credentials == nil else { return }
    do {
      let payload = try PairingPayload.parse(raw)
      redeem(
        host: payload.host, port: payload.port, fingerprint: payload.fingerprint, code: payload.code
      )
    } catch { self.error = error.localizedDescription }
  }
  func probe() {
    guard !busy else { return }
    do {
      let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
      guard let portNumber = Int(port) else {
        throw MonitorFailure("Enter the hub's port, like 8444")
      }
      try validateAddress(trimmedHost, portNumber)
      let digits = code.filter { !$0.isWhitespace }
      guard digits.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else {
        throw MonitorFailure("The code is six digits")
      }
      host = trimmedHost
      code = digits
      busy = true
      error = nil
      Task {
        defer { busy = false }
        do {
          fingerprint = try await HubTrust.probe(host: trimmedHost, port: portNumber)
          setup = "confirm"
        } catch {
          self.error =
            "Couldn't reach the hub. Check Wi-Fi, address, port and Local Network access in Settings."
        }
      }
    } catch { self.error = error.localizedDescription }
  }
  func confirm() {
    guard let fingerprint, let number = Int(port) else { return }
    redeem(host: host, port: number, fingerprint: fingerprint, code: code)
  }
  func mismatch() {
    fingerprint = nil
    setup = "manual"
    error = "The code has not been sent. Check the address and try again."
  }
  private func redeem(host: String, port: Int, fingerprint: String, code: String) {
    busy = true
    error = nil
    Task {
      defer { busy = false }
      do {
        let paired = try await HubTrust.pair(
          host: host, port: port, fingerprint: fingerprint, code: code, name: name)
        try store.save(paired)
        credentials = paired
        self.code = ""
        self.fingerprint = nil
      } catch { self.error = error.localizedDescription }
    }
  }
  func start() {
    guard !listening, !busy, let credentials else { return }
    busy = true
    error = nil
    captureGeneration += 1
    let requestedGeneration = captureGeneration
    Task {
      let allowed = await microphonePermission()
      defer { busy = false }
      guard captureGeneration == requestedGeneration else { return }
      guard allowed, foreground else {
        error = "Microphone access is needed to monitor. Enable it in Settings, then tap Start."
        return
      }
      let recorder = audioFactory()
      let generation = requestedGeneration
      recorder.onWindows = { [weak self] windows in
        let endMs = Int64(Date().timeIntervalSince1970 * 1000)
        Task { @MainActor in
          guard let self, self.listening, self.captureGeneration == generation else { return }
          for (index, samples) in windows.enumerated() {
            self.process(samples, at: endMs - Int64((windows.count - 1 - index) * 20))
          }
        }
      }
      recorder.onFailure = { [weak self] in
        Task { @MainActor in
          guard let self, self.captureGeneration == generation else { return }
          self.stop()
          self.error = "Microphone capture failed. Tap Start to retry."
        }
      }
      do { try recorder.start() } catch {
        recorder.stop()
        self.error = "Microphone unavailable. Close other recording apps and tap Start to retry."
        return
      }
      audio = recorder
      listening = true
      lastAudioWindow = Date()
      facing = "back"
      detector = NoiseDetector()
      history = NoiseHistory()
      floor = detector.floor
      threshold = detector.config.thresholdDb
      let connection = connectionFactory(credentials)
      connection.heartbeat = { [weak self] in
        self?.heartbeat() ?? ["t": "heartbeat", "batteryPct": NSNull(), "thermal": "unknown"]
      }
      connection.onState = { [weak self] in self?.connectionText = $0 }
      connection.onConnected = { [weak self] in
        self?.connected = true
        if let value = self?.heartbeat() { self?.hub?.send(value) }
      }
      connection.onDisconnected = { [weak self] in
        self?.connected = false
        self?.endStream()
      }
      connection.onControl = { [weak self] in self?.handle($0) }
      connection.onMedia = { [weak self] in self?.voice($0) }
      hub = connection
      connection.start()
      UIApplication.shared.isIdleTimerDisabled = true  // keep foreground camera available while mounted
      sweep = Task { [weak self] in
        while !Task.isCancelled {
          try? await Task.sleep(nanoseconds: 1_000_000_000)
          guard !Task.isCancelled, let self else { return }
          if self.audio?.isCapturing != true || Date().timeIntervalSince(self.lastAudioWindow) > 3 {
            self.stop()
            self.error = "Microphone capture stopped. Tap Start to resume monitoring."
            return
          }
          if self.talkId != nil && Date().timeIntervalSince(self.lastTalkFrame) > 3 {
            self.endTalk("Voice connection interrupted")
          }
        }
      }
    }
  }
  func stop() {
    captureGeneration += 1
    if let sample = history.finish() { hub?.send(["t": "noise_level", "sample": sample.json]) }
    if let id = stream {
      hub?.send(["t": "stream_stopped", "streamId": id, "reason": "The monitor was stopped"])
    }
    endStream()
    audio?.stop()
    audio = nil
    hub?.stop()
    hub = nil
    listening = false
    connected = false
    calibrating = false
    calibration = nil
    sweep?.cancel()
    sweep = nil
    UIApplication.shared.isIdleTimerDisabled = false
  }
  func scene(_ phase: ScenePhase) {
    // Ignore transient inactive state (permission dialogs, Control Center).
    if phase == .background {
      foreground = false
      UIApplication.shared.isIdleTimerDisabled = false
      if stream != nil && videoWanted { cameraUnavailable() }
      endTalk("Monitor moved to background")
    } else if phase == .active {
      foreground = true
      UIApplication.shared.isIdleTimerDisabled = listening
      // Do not silently restart a camera or a voice session on return.
    }
  }
  private func refreshBattery() {
    let device = UIDevice.current
    battery =
      device.batteryLevel < 0 ? nil : min(100, max(0, Int((device.batteryLevel * 100).rounded())))
    charging = device.batteryState == .charging || device.batteryState == .full
  }
  private func heartbeat() -> [String: Any] {
    refreshBattery()
    let thermal: String
    switch ProcessInfo.processInfo.thermalState {
    case .nominal: thermal = "none"
    case .fair: thermal = "light"
    case .serious: thermal = "severe"
    case .critical: thermal = "critical"
    @unknown default: thermal = "unknown"
    }
    return [
      "t": "heartbeat", "batteryPct": battery as Any? ?? NSNull(), "thermal": thermal,
      "charging": charging,
      "batteryTemperatureC": NSNull(),
      "speakerVolumePct": Int((AVAudioSession.sharedInstance().outputVolume * 100).rounded()),
    ]
  }
  private func process(_ samples: [Int16], at now: Int64) {
    lastAudioWindow = Date()
    let db = rmsDb(samples)
    level = db
    if let sample = history.window(
      db, at: now,
      threshold: calibration == nil ? detector.floor + detector.config.thresholdDb : nil)
    {
      hub?.send(["t": "noise_level", "sample": sample.json])
    }
    if let calibration {
      if let result = calibration.window(db, at: now) {
        detector.setFloor(result.floor)
        self.calibration = nil
        calibrating = false
        hub?.send([
          "t": "calibration_result", "noiseFloorDb": result.floor, "spreadDb": result.spread,
          "sampledMs": result.sampledMs,
        ])
      }
    } else if let detection = detector.window(db, at: now) {
      lastEvent = Date(timeIntervalSince1970: Double(now) / 1000)
      hub?.send([
        "t": "event", "clientEventId": UUID().uuidString.lowercased(), "type": "LOUD_NOISE",
        "tsMs": now,
        "metadata": ["peakDb": detection.peakDb, "durationMs": detection.durationMs],
      ])
    }
    floor = detector.floor
    if let id = stream, audioWanted, announced {
      sendMedia(kind: 2, id: id, payload: pcmData(samples))
    }
  }
  private func handle(_ message: [String: Any]) {
    let id = (message["streamId"] as? Int).flatMap(UInt16.init(exactly:))
    switch message["t"] as? String {
    case "config":
      if let json = message["config"], let data = try? JSONSerialization.data(withJSONObject: json),
        let config = try? JSONDecoder().decode(MonitorConfig.self, from: data), config.isValid
      {
        detector.update(config)
        floor = config.noiseFloorDb
        threshold = config.thresholdDb
      }
    case "recalibrate":
      if let ms = message["windowMs"] as? Int, (5000...120000).contains(ms) {
        calibration = Calibrator(start: Int64(Date().timeIntervalSince1970 * 1000), duration: ms)
        calibrating = true
      }
    case "start_stream":
      if let id {
        beginStream(
          id: id, video: message["video"] as? Bool == true, audio: message["audio"] as? Bool == true
        )
      }
    case "stop_stream": if id == stream { endStream() }
    case "select_camera":
      if let id, let requested = message["facing"] as? String,
        let request = message["requestId"] as? String
      {
        selectCamera(id: id, facing: requested, request: request)
      }
    case "start_talk":
      if let id, let value = message["talkId"] as? Int, let talk = UInt16(exactly: value), talk > 0
      {
        beginTalk(stream: id, talk: talk)
      }
    case "stop_talk":
      if message["talkId"] as? Int == talkId.map(Int.init) { endTalk("Talk stopped") }
    case "error":
      // Treat revoked/invalid credentials as terminal rather than repeatedly retrying.
      if ["unauthorized", "revoked"].contains(message["code"] as? String ?? "") {
        stop()
        error = "This pairing is no longer authorized by the hub."
      }
    default: break
    }
  }
  private func beginStream(id: UInt16, video: Bool, audio: Bool) {
    if stream == id && announced {
      announce()
      return
    }
    endStream()
    stream = id
    streamStart = ProcessInfo.processInfo.systemUptime
    audioWanted = audio && listening
    videoWanted = video
    announced = false
    videoNotice = nil
    let generation = streamGeneration
    hub?.send(["t": "stream_preparing", "streamId": id])
    if !video || !foreground {
      finishStart(id: id)
      return
    }
    Task {
      let format = await openCamera(generation: generation)
      guard stream == id, streamGeneration == generation else { return }
      videoFormat = format
      watching = format != nil
      if format == nil { videoNotice = "Camera unavailable · sound-only live view" }
      finishStart(id: id)
    }
  }
  private func openCamera(generation: Int) async -> CameraFormat? {
    await camera.start(facing: facing) { [weak self] jpeg, format in
      Task { @MainActor in
        guard let self, self.streamGeneration == generation, let id = self.stream, self.announced,
          self.foreground
        else { return }
        if self.videoFormat?.width != format.width || self.videoFormat?.height != format.height {
          self.videoFormat = format
          self.announce()
        }
        self.sendMedia(kind: 1, id: id, payload: jpeg)
      }
    }
  }
  private func finishStart(id: UInt16) {
    if videoFormat == nil && !audioWanted {
      hub?.send([
        "t": "stream_stopped", "streamId": id,
        "reason": "Neither camera nor microphone is available",
      ])
      endStream()
      return
    }
    if videoWanted && !foreground {
      videoNotice = "iOS pauses video when locked · sound continues. Keep this app open for video."
    }
    announced = true
    announce()
  }
  private func announce() {
    guard let id = stream else { return }
    hub?.send([
      "t": "stream_started", "streamId": id, "video": videoFormat?.json as Any? ?? NSNull(),
      "audio": audioWanted ? ["sampleRate": 16000, "channels": 1] : NSNull(),
    ])
  }
  private func sendMedia(kind: UInt8, id: UInt16, payload: Data) {
    let elapsed = max(0, ProcessInfo.processInfo.systemUptime - streamStart)
    hub?.send(
      MediaFrame(kind: kind, id: id, timestampUs: UInt64(elapsed * 1_000_000), payload: payload))
  }
  private func endStream() {
    streamGeneration += 1
    stream = nil
    announced = false
    audioWanted = false
    videoWanted = false
    videoFormat = nil
    watching = false
    endTalk("Live view stopped")
    hub?.discardMedia()
    // Queue this synchronously before any subsequent camera.start is scheduled.
    camera.cancel()
  }
  private func cameraUnavailable() {
    guard stream != nil else { return }
    streamGeneration += 1
    camera.cancel()
    videoFormat = nil
    watching = false
    videoNotice =
      "iOS paused video · sound continues. Keep this app open and retry video on the parent."
    if announced { announce() } else if let stream { finishStart(id: stream) }
  }
  private func selectCamera(id: UInt16, facing requested: String, request: String) {
    func reply(_ reason: String?) {
      hub?.send([
        "t": "camera_result", "streamId": id, "requestId": request,
        "reason": reason as Any? ?? NSNull(),
      ])
    }
    guard id == stream, announced, foreground, videoFormat != nil,
      videoFormat?.cameras.contains(requested) == true
    else {
      reply("Camera switching is unavailable for this live view")
      return
    }
    if requested == facing {
      reply(nil)
      return
    }
    let previous = facing
    facing = requested
    streamGeneration += 1
    let generation = streamGeneration
    videoFormat = nil
    watching = false
    announce()
    camera.cancel()
    Task {
      var format = await openCamera(generation: generation)
      guard streamGeneration == generation, stream == id else { return }
      let failed = format == nil
      if failed {
        facing = previous
        format = await openCamera(generation: generation)
      }
      guard streamGeneration == generation, stream == id else { return }
      videoFormat = format
      watching = format != nil
      announce()
      reply(failed ? "Couldn't open that camera. Previous camera restoration attempted." : nil)
    }
  }
  private func beginTalk(stream id: UInt16, talk: UInt16) {
    guard stream == id, announced, listening, foreground else {
      hub?.send([
        "t": "talk_stopped", "talkId": talk,
        "reason": "Live view is no longer active in the foreground",
      ])
      return
    }
    if talkId == talk { return }
    endTalk("Talk replaced")
    do {
      try audio?.startTalk()
      talkId = talk
      talking = true
      lastTalkFrame = Date()
      hub?.send(["t": "talk_started", "talkId": talk])
    } catch {
      hub?.send(["t": "talk_stopped", "talkId": talk, "reason": "Monitor speaker unavailable"])
    }
  }
  private func voice(_ frame: MediaFrame) {
    guard frame.kind == 2, frame.id == talkId, !frame.payload.isEmpty, frame.payload.count <= 3200,
      frame.payload.count % 2 == 0
    else { return }
    guard audio?.play(frame.payload) == true else {
      endTalk("Speaker playback failed")
      return
    }
    lastTalkFrame = Date()
  }
  private func endTalk(_ reason: String) {
    guard let talkId else { return }
    self.talkId = nil
    talking = false
    audio?.stopTalk()
    hub?.send(["t": "talk_stopped", "talkId": talkId, "reason": reason])
  }
}
