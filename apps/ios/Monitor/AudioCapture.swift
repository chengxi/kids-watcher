import AVFoundation
import MonitorCore

/// Audio conversion and windowing stay on the audio callback, away from UI work.
final class AudioCapture {
  private let engine = AVAudioEngine()
  private let player = AVAudioPlayerNode()
  private let format = AVAudioFormat(
    commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false)!
  private var installed = false
  private var pending: [Int16] = []
  private var pendingOffset = 0
  private let playbackLock = NSLock()
  private var scheduled = 0
  private var playbackGeneration = 0
  private var usingTalkMode = false
  private var receivedBuffers = 0
  private var renderedBuffers = 0
  private var droppedBuffers = 0
  private var lastDiagnostic = 0.0
  struct PlaybackStatus {
    let received: Int, rendered: Int, queued: Int, dropped: Int
    let rendering: Bool, volume: Float, outputRate: Double
  }
  var playbackStatus: PlaybackStatus {
    playbackLock.lock()
    let received = receivedBuffers
    let rendered = renderedBuffers
    let queued = scheduled
    let dropped = droppedBuffers
    playbackLock.unlock()
    return PlaybackStatus(
      received: received, rendered: rendered, queued: queued, dropped: dropped,
      rendering: player.lastRenderTime != nil, volume: AVAudioSession.sharedInstance().outputVolume,
      outputRate: engine.outputNode.outputFormat(forBus: 0).sampleRate)
  }
  var isCapturing: Bool { engine.isRunning }
  var onWindows: (([[Int16]]) -> Void)?
  var onFailure: (() -> Void)?
  func start() throws {
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
    try session.setPreferredSampleRate(16000)
    try session.setPreferredIOBufferDuration(0.02)
    try session.setActive(true)
    let input = engine.inputNode
    let inputFormat = input.outputFormat(forBus: 0)
    guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
      let converter = AVAudioConverter(from: inputFormat, to: format)
    else { throw MonitorFailure("Microphone unavailable") }
    engine.attach(player)
    engine.connect(player, to: engine.mainMixerNode, format: format)
    input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
      guard let self else { return }
      let capacity = AVAudioFrameCount(
        ceil(Double(buffer.frameLength) * 16000 / inputFormat.sampleRate) + 32)
      guard let converted = AVAudioPCMBuffer(pcmFormat: self.format, frameCapacity: capacity) else {
        return
      }
      var supplied = false
      var error: NSError?
      let status = converter.convert(to: converted, error: &error) { _, state in
        if supplied {
          state.pointee = .noDataNow
          return nil
        }
        supplied = true
        state.pointee = .haveData
        return buffer
      }
      guard status != .error, error == nil, let samples = converted.floatChannelData?[0] else {
        self.onFailure?()
        return
      }
      for i in 0..<Int(converted.frameLength) {
        self.pending.append(Int16(max(-32768, min(32767, Double(samples[i]) * 32768))))
      }
      var windows: [[Int16]] = []
      while self.pending.count - self.pendingOffset >= 320 {
        windows.append(Array(self.pending[self.pendingOffset..<self.pendingOffset + 320]))
        self.pendingOffset += 320
      }
      if self.pendingOffset > 0 {
        self.pending.removeFirst(self.pendingOffset)
        self.pendingOffset = 0
      }
      if !windows.isEmpty { self.onWindows?(windows) }
    }
    installed = true
    engine.prepare()
    try engine.start()
  }
  func stop() {
    stopTalk()
    engine.stop()
    if installed {
      engine.inputNode.removeTap(onBus: 0)
      installed = false
    }
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
  }
  func startTalk() throws {
    let session = AVAudioSession.sharedInstance()
    // Measurement disables output dynamics and attenuates speaker playback.
    // Keep it for room measurements, but use normal playback processing for voice.
    try session.setMode(.default)
    do {
      try session.overrideOutputAudioPort(.speaker)
      guard engine.isRunning,
        session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }),
        engine.outputNode.outputFormat(forBus: 0).sampleRate > 0
      else { throw MonitorFailure("Built-in speaker unavailable") }
      usingTalkMode = true
    } catch {
      try? session.overrideOutputAudioPort(.none)
      try? session.setMode(.measurement)
      throw error
    }
    playbackLock.lock()
    receivedBuffers = 0
    renderedBuffers = 0
    droppedBuffers = 0
    lastDiagnostic = 0
    playbackLock.unlock()
    player.play()
    #if DEBUG
      print(
        "KidsMonitorTalk started engine=\(engine.isRunning) mode=\(session.mode.rawValue) route=\(session.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ",")) volume=\(session.outputVolume) outputRate=\(engine.outputNode.outputFormat(forBus: 0).sampleRate) mixerVolume=\(engine.mainMixerNode.outputVolume) playerVolume=\(player.volume)"
      )
      fflush(stdout)
    #endif
  }
  func play(_ data: Data) -> Bool {
    let session = AVAudioSession.sharedInstance()
    guard player.isPlaying,
      session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker })
    else { return false }
    guard !data.isEmpty, data.count <= 3200, data.count % 2 == 0 else { return false }
    playbackLock.lock()
    receivedBuffers += 1
    let report = ProcessInfo.processInfo.systemUptime - lastDiagnostic > 1
    if report { lastDiagnostic = ProcessInfo.processInfo.systemUptime }
    guard scheduled < 3 else {
      droppedBuffers += 1
      playbackLock.unlock()
      if report { reportPlayback() }
      return true
    }  // discard stale voice rather than accumulating delay
    scheduled += 1
    let generation = playbackGeneration
    playbackLock.unlock()
    let count = data.count / 2
    guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count)),
      let samples = buffer.floatChannelData?[0]
    else { return false }
    buffer.frameLength = AVAudioFrameCount(count)
    let bytes = [UInt8](data)
    for i in 0..<count {
      samples[i] =
        Float(Int16(bitPattern: UInt16(bytes[2 * i]) | UInt16(bytes[2 * i + 1]) << 8)) / 32768
    }
    // Bound waiting audio until it is rendered, not until speaker presentation:
    // hardware output latency must not occupy every slot and discard fresh speech.
    player.scheduleBuffer(buffer, completionCallbackType: .dataRendered) { [weak self] _ in
      guard let self else { return }
      self.playbackLock.lock()
      if generation == self.playbackGeneration {
        self.scheduled = max(0, self.scheduled - 1)
        self.renderedBuffers += 1
      }
      self.playbackLock.unlock()
    }
    if report { reportPlayback() }
    return true
  }
  private func reportPlayback() {
    #if DEBUG
      let state = playbackStatus
      print(
        "KidsMonitorTalk PCM received=\(state.received) rendered=\(state.rendered) queued=\(state.queued) dropped=\(state.dropped) rendering=\(state.rendering) volume=\(state.volume)"
      )
      fflush(stdout)
    #endif
  }
  func stopTalk() {
    player.stop()
    playbackLock.lock()
    playbackGeneration += 1
    scheduled = 0
    playbackLock.unlock()
    guard usingTalkMode else { return }
    usingTalkMode = false
    let session = AVAudioSession.sharedInstance()
    try? session.overrideOutputAudioPort(.none)
    do { try session.setMode(.measurement) } catch { onFailure?() }
  }
}
