import AVFoundation
import MonitorCore
import Photos
import SwiftUI
import UserNotifications

@MainActor final class ParentModel: ObservableObject {
  @Published var credentials: Credentials?
  @Published var monitors: [ParentMonitor] = []
  @Published var events: [ParentEvent] = []
  @Published var noise: [ParentNoiseSample] = []
  @Published var chartEvents: [ParentEvent] = []
  @Published var selectedId: String? {
    didSet {
      if selectedId != oldValue {
        loadNoiseCache()
        Task { await refreshNoise() }
      }
    }
  }
  @Published var deviceFilter: String?
  @Published var categoryFilter: String?
  @Published var connected = false
  @Published var currentStatus = false
  @Published var busy = false
  @Published var historyBusy = false
  @Published var noiseCurrent = false
  @Published var connectionText = "Connecting to home hub…"
  @Published var error: String?
  @Published var noiseError: String?
  @Published var notice: String?
  @Published var setup = "manual"
  @Published var host = ""
  @Published var port = "8444"
  @Published var code = ""
  @Published var fingerprint: String?
  @Published var watching: ParentMonitor?
  @Published var image: UIImage?
  @Published var liveText = "Connecting…"
  @Published var audioAvailable = false
  @Published var muted = false
  @Published var talking = false
  @Published var talkPending = false
  @Published var cameraPending = false
  @Published var facing: String?
  @Published var cameras: [String] = []
  @Published var photoBusy = false
  @Published var eventOffset = 0
  @Published var moreEvents = false
  private let vault = CredentialStore(service: "com.kidsmonitor.parent.ios")
  private var store: ParentStore?, api: ParentAPI?, socket: HubConnection?
  private var poll: Task<Void, Never>?, recovery: Task<Void, Never>?, deadline: Task<Void, Never>?,
    health: Task<Void, Never>?
  private var foreground = true, refreshing = false, noiseRefreshing = false
  private var stream: UInt16?, requestId: String?, talkId: UInt16?, talkRequest: String?,
    cameraRequest: String?
  private var rotation = 0, audioFormat: (Int, Int)?, lastImage = Date.distantPast,
    lastAudio = Date.distantPast
  private var lastVideoTimestamp: UInt64?, lastAudioTimestamp: UInt64?, attempts = 0,
    soundOnly = false
  private let speaker = ParentSpeaker(), decoder = ParentVideoDecoder()
  private var microphone: AudioCapture?
  private(set) var voiceFramesSent = 0
  private var videoAvailable = false
  var activeStreamId: UInt16? { stream }
  var audioRenderedForTests: Int { speaker.rendered }
  var selected: ParentMonitor? { monitors.first { $0.id == selectedId } ?? monitors.first }
  var statusHealthy: Bool {
    connected && currentStatus && !monitors.isEmpty
      && monitors.allSatisfy { $0.online && ($0.batteryPct ?? 100) > 15 }
  }
  var statusProblem: Bool {
    !connected
      || (currentStatus && monitors.contains { !$0.online || ($0.batteryPct ?? 100) <= 15 })
  }
  var canWatch: Bool { connected && currentStatus && selected?.online == true }
  var battery: String {
    guard connected, currentStatus, let monitor = selected, monitor.online,
      let pct = monitor.batteryPct
    else { return "Unavailable" }
    return "\(pct)%\(monitor.charging == true ? " · charging" : "")"
  }
  init(credentials: Credentials? = nil, start: Bool = true) {
    self.credentials = credentials ?? vault.load()
    decoder.onImage = { [weak self] image, _ in
      guard let self, self.watching != nil, self.stream != nil else { return }
      self.image = image
      self.lastImage = Date()
      self.liveText = "Live"
      self.deadline?.cancel()
    }
    if self.credentials != nil { configure(start: start) }
    NotificationCenter.default.addObserver(
      forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
    ) { [weak self] _ in
      Task { @MainActor in
        guard self?.watching != nil else { return }
        self?.stopTalk()
        self?.speaker.stop()
        self?.liveText = "Audio interrupted · retry live view"
      }
    }
    NotificationCenter.default.addObserver(
      forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main
    ) { [weak self] _ in
      Task { @MainActor in self?.leaveLive() }
    }
  }
  private func configure(start: Bool = true) {
    guard let credentials else { return }
    do {
      store = try ParentStore(deviceId: credentials.deviceId)
      api = try ParentAPI(credentials)
      monitors = try store!.monitors()
      selectedId = monitors.first?.id
      loadEvents()
      let connection = HubConnection(credentials, role: "parent")
      socket = connection
      connection.heartbeat = { ["t": "heartbeat"] }
      connection.onState = { [weak self] in self?.connectionText = $0 }
      connection.onConnected = { [weak self, weak connection] in
        guard let self, let connection else { return }
        self.connected = true
        connection.send(["t": "subscribe", "monitorIds": NSNull()])
        self.beginRecovery()
        Task { await self.refresh() }
        if self.watching != nil { self.requestLive() }
      }
      connection.onDisconnected = { [weak self] in
        guard let self else { return }
        self.connected = false
        self.currentStatus = false
        self.noiseCurrent = false
        self.clearMedia()
        self.liveText = "Hub disconnected · waiting to reconnect"
      }
      connection.onControl = { [weak self] in self?.control($0) }
      connection.onMedia = { [weak self] in self?.media($0) }
      if start {
        connection.start()
        startPolling()
      }
    } catch { self.error = error.localizedDescription }
  }
  func scene(_ phase: ScenePhase) {
    if phase == .background {
      foreground = false
      leaveLive()
      poll?.cancel()
      recovery?.cancel()
      recovery = nil
      socket?.stop()
      currentStatus = false
      noiseCurrent = false
      connectionText = "Paused in background · open app to receive alerts"
    } else if phase == .active {
      foreground = true
      socket?.start()
      startPolling()
      Task { await refresh() }
    }
  }
  private func startPolling() {
    poll?.cancel()
    poll = Task { [weak self] in
      var tick = 0
      while !Task.isCancelled {
        guard let self else { return }
        await self.refreshMonitors()
        if tick % 4 == 0 { await self.refreshNoise() }
        tick += 1
        try? await Task.sleep(nanoseconds: 15_000_000_000)
      }
    }
  }
  func refresh() async {
    await refreshMonitors()
    await refreshNoise()
    beginRecovery()
  }
  private func refreshMonitors() async {
    guard foreground, !refreshing, let api else { return }
    refreshing = true
    defer { refreshing = false }
    do {
      let result = try await api.get("monitors", as: ParentAPI.Monitors.self)
      guard foreground else { return }
      monitors = result.monitors
      currentStatus = true
      try store?.saveMonitors(monitors)
      if selectedId == nil || !monitors.contains(where: { $0.id == selectedId }) {
        selectedId = monitors.first?.id
      }
    } catch {
      currentStatus = false
      if !Task.isCancelled { self.error = error.localizedDescription }
    }
  }
  private func loadNoiseCache() {
    noise = (try? store?.noise(monitor: selectedId ?? "", from: Self.now - 86_400_000)) ?? []
    chartEvents = ((try? store?.events(monitor: selectedId, category: nil, limit: 5000)) ?? [])
      .filter { $0.tsMs >= Self.now - 86_400_000 }
    noiseCurrent = false
  }
  func refreshNoise() async {
    guard foreground, !noiseRefreshing, let selectedId, let api else { return }
    noiseRefreshing = true
    defer { noiseRefreshing = false }
    do {
      let result = try await api.get(
        "monitors/\(selectedId)/noise-history", as: ParentAPI.History.self)
      try store?.saveNoise(result.samples, monitor: selectedId, now: result.toMs)
      try store?.insert(result.events)
      guard self.selectedId == selectedId, foreground else { return }
      noise = (try store?.noise(monitor: selectedId, from: result.windowStartMs)) ?? result.samples
      chartEvents = result.events
      noiseCurrent = true
      noiseError = result.eventsTruncated ? "Some older chart markers are omitted by the hub." : nil
    } catch {
      if self.selectedId == selectedId {
        noiseCurrent = false
        noiseError = "Couldn't refresh sound history. Saved measurements remain available."
      }
    }
  }
  private func beginRecovery() {
    guard recovery == nil, foreground, let api, let store else { return }
    historyBusy = true
    recovery = Task { [weak self] in
      defer {
        self?.recovery = nil
        self?.historyBusy = false
      }
      do {
        var cursor = try store.recoveryCursor
        while !Task.isCancelled {
          let page = try await api.get(
            "events?since=\(cursor)&limit=200", as: ParentAPI.Events.self
          ).events
          try Task.checkCancellation()
          if let last = page.last {
            guard last.id > cursor else { throw MonitorFailure("Hub history did not advance") }
            cursor = last.id
          }
          try store.insert(page, recovery: cursor)
          self?.loadEvents(resetPage: false)
          if page.count < 200 { break }
        }
      } catch {
        if !Task.isCancelled {
          self?.error = "History sync failed. Saved events remain available; Refresh retries."
        }
      }
    }
  }
  func loadEvents(resetPage: Bool = true) {
    if resetPage { eventOffset = 0 }
    do {
      let page =
        try store?.events(
          monitor: deviceFilter, category: categoryFilter, offset: eventOffset, limit: 101) ?? []
      events = Array(page.prefix(100))
      moreEvents = page.count > 100
    } catch { self.error = error.localizedDescription }
  }
  func loadOlder() {
    eventOffset += 50
    loadEvents(resetPage: false)
  }
  func loadNewer() {
    eventOffset = max(0, eventOffset - 50)
    loadEvents(resetPage: false)
  }
  func probe() {
    guard !busy else { return }
    busy = true
    error = nil
    Task {
      defer { busy = false }
      do {
        host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        code = code.filter { !$0.isWhitespace }
        guard code.range(of: "^[0-9]{6}$", options: .regularExpression) != nil, let p = Int(port)
        else { throw MonitorFailure("Enter a hub address, port, and six-digit parent code") }
        fingerprint = try await HubTrust.probe(host: host, port: p)
        setup = "confirm"
      } catch { self.error = error.localizedDescription }
    }
  }
  func pairQR(_ raw: String) {
    guard !busy else { return }
    do {
      let payload = try PairingPayload.parse(raw, expectedRole: "parent")
      host = payload.host
      port = String(payload.port)
      code = payload.code
      fingerprint = payload.fingerprint
      pair()
    } catch { self.error = error.localizedDescription }
  }
  func pair() {
    guard !busy, let fingerprint, let p = Int(port) else { return }
    busy = true
    error = nil
    Task {
      defer { busy = false }
      do {
        let credentials = try await HubTrust.pair(
          host: host, port: p, fingerprint: fingerprint, code: code,
          name: "\(DeviceName.current) Parent", role: "parent")
        try vault.save(credentials)
        self.credentials = credentials
        code = ""
        self.fingerprint = nil
        configure()
      } catch { self.error = error.localizedDescription }
    }
  }
  func enableNotifications() async {
    do {
      _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [
        .alert, .sound,
      ])
    } catch { self.error = "Couldn't enable notifications" }
  }
  private func notify(_ event: ParentEvent) {
    let content = UNMutableNotificationContent()
    content.title =
      "\(monitors.first { $0.id == event.monitorId }?.name ?? "Monitor \(event.monitorId.prefix(8))"): \(event.title)"
    content.body =
      event.type == "MONITOR_LOST"
      ? "Check the monitor phone and its connection." : "Open Kid Monitor to see this event."
    if event.type == "LOUD_NOISE" || event.type == "MONITOR_LOST" { content.sound = .default }
    content.threadIdentifier = event.monitorId
    if event.type == "MONITOR_ONLINE" {
      UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [
        "offline-\(event.monitorId)"
      ])
    }
    let id = event.type == "MONITOR_LOST" ? "offline-\(event.monitorId)" : "event-\(event.id)"
    UNUserNotificationCenter.current().add(
      UNNotificationRequest(identifier: id, content: content, trigger: nil))
  }
  func watch(_ monitor: ParentMonitor) {
    leaveLive()
    watching = monitor
    attempts = 0
    soundOnly = false
    muted = false
    UIApplication.shared.isIdleTimerDisabled = true
    if connected { requestLive() } else { liveText = "Waiting for home hub…" }
    health = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        guard let self, !Task.isCancelled else { return }
        if self.image != nil && Date().timeIntervalSince(self.lastImage) > 3 {
          self.liveText = "Picture delayed · retry video"
        }
        if self.audioAvailable && !self.talking && Date().timeIntervalSince(self.lastAudio) > 3 {
          self.liveText = "Room audio delayed · retry live view"
        }
      }
    }
  }
  private func requestLive() {
    guard let watching, connected, foreground else { return }
    attempts += 1
    requestId = UUID().uuidString
    image = nil
    decoder.reset()
    lastVideoTimestamp = nil
    lastAudioTimestamp = nil
    liveText = soundOnly ? "Connecting room audio…" : "Contacting monitor…"
    socket?.send([
      "t": "request_stream", "requestId": requestId!, "monitorId": watching.id, "video": !soundOnly,
      "audio": true,
    ])
    armDeadline(seconds: 15)
  }
  private func armDeadline(seconds: UInt64) {
    deadline?.cancel()
    let request = requestId
    deadline = Task { [weak self] in
      try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
      guard let self, !Task.isCancelled, self.requestId == request, self.watching != nil else {
        return
      }
      if self.attempts < 3 {
        self.deadline = nil
        self.cancelStream()
        self.clearMedia()
        self.requestId = nil
        self.liveText = "Retrying video (\(self.attempts + 1)/3)…"
        try? await Task.sleep(nanoseconds: UInt64(self.attempts * 2) * 1_000_000_000)
        guard !Task.isCancelled else { return }
        self.requestLive()
      } else {
        self.liveText =
          self.audioAvailable ? "Sound only · video unavailable" : "Live view unavailable · Retry"
      }
    }
  }
  func retryLive(audioOnly: Bool = false) {
    cancelStream()
    clearMedia()
    attempts = 0
    soundOnly = audioOnly
    requestLive()
  }
  private func cancelStream() {
    if let watching, let requestId {
      socket?.send(["t": "cancel_stream_request", "monitorId": watching.id, "requestId": requestId])
    }
    if let stream { socket?.send(["t": "stop_stream", "streamId": Int(stream)]) }
  }
  private func clearMedia() {
    deadline?.cancel()
    stopTalk()
    speaker.stop()
    decoder.reset()
    stream = nil
    image = nil
    audioAvailable = false
    audioFormat = nil
    videoAvailable = false
    facing = nil
    cameras = []
    cameraPending = false
    cameraRequest = nil
  }
  func leaveLive() {
    stopTalk()
    cancelStream()
    clearMedia()
    health?.cancel()
    health = nil
    watching = nil
    requestId = nil
    UIApplication.shared.isIdleTimerDisabled = false
  }
  func setMuted() {
    muted.toggle()
    if muted { speaker.stop() } else { resumeSpeaker() }
  }
  private func resumeSpeaker() {
    guard !muted, !talking, !talkPending, let (rate, channels) = audioFormat else { return }
    do { try speaker.start(rate: rate, channels: channels) } catch {
      liveText = "Room audio unavailable · retry"
    }
  }
  func toggleTalk() {
    if talking || talkPending {
      stopTalk()
      return
    }
    guard let stream, connected else { return }
    talkPending = true
    Task {
      let allowed = await withCheckedContinuation { continuation in
        AVAudioSession.sharedInstance().requestRecordPermission {
          continuation.resume(returning: $0)
        }
      }
      guard talkPending, foreground, self.stream == stream else { return }
      guard allowed else {
        talkPending = false
        notice = "Allow microphone access in Settings to talk to the monitor."
        return
      }
      talkRequest = UUID().uuidString
      socket?.send(["t": "start_talk", "streamId": Int(stream), "requestId": talkRequest!])
      let request = talkRequest
      try? await Task.sleep(nanoseconds: 8_000_000_000)
      if talkPending && talkRequest == request {
        stopTalk()
        notice = "Monitor speaker did not respond."
      }
    }
  }
  func stopTalk() {
    if let stream, let talkRequest {
      socket?.send(["t": "stop_talk", "streamId": Int(stream), "requestId": talkRequest])
    }
    let hadVoice = talking || talkPending
    talking = false
    talkPending = false
    talkId = nil
    talkRequest = nil
    microphone?.stop()
    microphone = nil
    socket?.discardMedia()
    if hadVoice { resumeSpeaker() }
  }
  func switchCamera() {
    guard let stream, let facing, !cameraPending, cameras.count > 1 else { return }
    cameraPending = true
    cameraRequest = UUID().uuidString
    let request = cameraRequest
    socket?.send([
      "t": "select_camera", "streamId": Int(stream), "requestId": request!,
      "facing": facing == "back" ? "front" : "back",
    ])
    Task {
      try? await Task.sleep(nanoseconds: 10_000_000_000)
      if cameraRequest == request && cameraPending {
        cameraPending = false
        notice = "Camera switch did not respond."
      }
    }
  }
  func savePhoto() {
    guard let image, Date().timeIntervalSince(lastImage) < 3, !photoBusy else { return }
    photoBusy = true
    Task {
      defer { photoBusy = false }
      let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
      guard authorization == .authorized || authorization == .limited else {
        notice = "Allow adding photos in Settings to save a snapshot."
        return
      }
      do {
        let turns = [.left, .right, .leftMirrored, .rightMirrored] as [UIImage.Orientation]
        let rawSize = image.cgImage.map { CGSize(width: $0.width, height: $0.height) } ?? image.size
        let size =
          turns.contains(image.imageOrientation)
          ? CGSize(width: rawSize.height, height: rawSize.width) : rawSize
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let normalized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
          image.draw(in: CGRect(origin: .zero, size: size))
        }
        try await PHPhotoLibrary.shared().performChanges {
          PHAssetChangeRequest.creationRequestForAsset(from: normalized)
        }
        notice = "Photo saved"
      } catch { notice = "Couldn't save this photo." }
    }
  }
  private func control(_ message: [String: Any]) {
    switch message["t"] as? String {
    case "event":
      var row = message
      row["id"] = row.removeValue(forKey: "eventId")
      if let data = try? JSONSerialization.data(withJSONObject: row),
        let event = try? JSONDecoder().decode(ParentEvent.self, from: data)
      {
        do {
          try store?.insert([event])
          loadEvents(resetPage: false)
          notify(event)
        } catch { self.error = "Couldn't save a live event." }
      }
    case "stream_waiting":
      guard message["requestId"] as? String == requestId,
        message["monitorId"] as? String == watching?.id
      else { return }
      liveText =
        message["phase"] as? String == "preparing_video"
        ? "Preparing video…" : "Waiting for monitor…"
      armDeadline(seconds: 15)
    case "stream_ready":
      guard watching != nil, message["monitorId"] as? String == watching?.id,
        message["requestId"] as? String == requestId,
        let id = message["streamId"] as? Int, let streamId = UInt16(exactly: id), id > 0
      else { return }
      let changed = stream != streamId
      stream = streamId
      if changed {
        stopTalk()
        speaker.stop()
        lastAudioTimestamp = nil
        lastVideoTimestamp = nil
      }
      if let audio = message["audio"] as? [String: Any], let rate = audio["sampleRate"] as? Int,
        let channels = audio["channels"] as? Int, (8000...48000).contains(rate),
        (1...2).contains(channels)
      {
        let previous = audioFormat
        audioFormat = (rate, channels)
        audioAvailable = true
        lastAudio = Date()
        if changed || previous?.0 != rate || previous?.1 != channels { resumeSpeaker() }
      }
      if let video = message["video"] as? [String: Any], let width = video["width"] as? Int,
        let height = video["height"] as? Int, (1...4096).contains(width),
        (1...4096).contains(height)
      {
        videoAvailable = true
        rotation = video["rotationDegrees"] as? Int ?? 0
        facing = video["facing"] as? String
        cameras = video["availableCameras"] as? [String] ?? []
        decoder.reset()
        image = nil
        lastVideoTimestamp = nil
        liveText = "Waiting for first picture…"
        armDeadline(seconds: 12)
      } else {
        videoAvailable = false
        image = nil
        decoder.reset()
        liveText = audioAvailable ? "Sound only · video unavailable" : "No media available"
        deadline?.cancel()
      }
    case "stream_ended":
      guard message["streamId"] as? Int == stream.map(Int.init) else { return }
      clearMedia()
      liveText = "Stream ended · Retry"
    case "error":
      guard watching != nil, message["requestId"] as? String == requestId else { return }
      liveText = "Monitor unavailable · retrying…"
      armDeadline(seconds: 5)
    case "camera_result":
      guard message["streamId"] as? Int == stream.map(Int.init),
        message["requestId"] as? String == cameraRequest
      else { return }
      cameraPending = false
      if let reason = message["reason"] as? String { notice = "Couldn't switch camera: \(reason)" }
    case "talk_state":
      guard message["streamId"] as? Int == stream.map(Int.init),
        message["requestId"] as? String == talkRequest
      else { return }
      guard message["ready"] as? Bool == true, let id = message["talkId"] as? Int,
        let voiceId = UInt16(exactly: id), talkPending
      else {
        let reason = message["reason"] as? String
        stopTalk()
        if let reason { notice = "Voice stopped: \(reason)" }
        return
      }
      speaker.stop()
      voiceFramesSent = 0
      talkId = voiceId
      talkPending = false
      talking = true
      let mic = AudioCapture()
      microphone = mic
      mic.onWindows = { [weak self] windows in
        Task { @MainActor in
          guard let self, self.talking, self.talkId == voiceId else { return }
          self.sendVoice(windows)
        }
      }
      mic.onFailure = { [weak self] in
        Task { @MainActor in
          self?.stopTalk()
          self?.notice = "Microphone failed. Voice stopped."
        }
      }
      do { try mic.start() } catch {
        stopTalk()
        notice = "Couldn't start microphone."
      }
    default: break
    }
  }
  func sendVoice(_ windows: [[Int16]]) {
    guard talking, foreground, let talkId else { return }
    for window in windows {
      voiceFramesSent += 1
      socket?.send(
        MediaFrame(
          kind: 2, id: talkId,
          timestampUs: UInt64(ProcessInfo.processInfo.systemUptime * 1_000_000),
          payload: pcmData(window)))
    }
  }
  private func media(_ frame: MediaFrame) {
    guard foreground, watching != nil, frame.id == stream else { return }
    if frame.kind == 1 {
      guard videoAvailable else { return }
      guard lastVideoTimestamp == nil || frame.timestampUs > lastVideoTimestamp! else { return }
      lastVideoTimestamp = frame.timestampUs
      decoder.submit(frame.payload, rotation: rotation)
    } else if frame.kind == 2 {
      guard let (_, channels) = audioFormat, !frame.payload.isEmpty, frame.payload.count <= 19200,
        frame.payload.count % (2 * channels) == 0
      else { return }
      guard lastAudioTimestamp == nil || frame.timestampUs > lastAudioTimestamp! else { return }
      lastAudioTimestamp = frame.timestampUs
      lastAudio = Date()
      if !muted && !talking && !talkPending { speaker.play(frame.payload) }
    }
  }
  static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
