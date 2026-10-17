import AVFoundation
import ImageIO
import MonitorCore
import UIKit

final class ParentSpeaker {
  private var engine: AVAudioEngine?, player: AVAudioPlayerNode?, format: AVAudioFormat?
  private let lock = NSLock()
  private var queued = 0, queuedFrames = 0, generation = 0
  private var renderedCount = 0
  var rendered: Int {
    lock.lock()
    defer { lock.unlock() }
    return renderedCount
  }
  func start(rate: Int, channels: Int) throws {
    stop()
    guard (8000...48000).contains(rate), (1...2).contains(channels) else {
      throw MonitorFailure("Unsupported room audio format")
    }
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(.playback, mode: .default)
    try session.setActive(true)
    let engine = AVAudioEngine()
    let player = AVAudioPlayerNode()
    let format = AVAudioFormat(
      commonFormat: .pcmFormatFloat32, sampleRate: Double(rate),
      channels: AVAudioChannelCount(channels), interleaved: false)!
    engine.attach(player)
    engine.connect(player, to: engine.mainMixerNode, format: format)
    engine.prepare()
    try engine.start()
    player.play()
    self.engine = engine
    self.player = player
    self.format = format
  }
  func play(_ data: Data) {
    guard let format, let player, player.isPlaying, !data.isEmpty, data.count <= 19200,
      data.count % (2 * Int(format.channelCount)) == 0
    else { return }
    let frames = data.count / (2 * Int(format.channelCount))
    lock.lock()
    guard queued < 6, queuedFrames + frames <= Int(format.sampleRate / 5) else {
      lock.unlock()
      return
    }
    queued += 1
    queuedFrames += frames
    let current = generation
    lock.unlock()
    guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames))
    else {
      lock.lock()
      queued -= 1
      queuedFrames -= frames
      lock.unlock()
      return
    }
    buffer.frameLength = AVAudioFrameCount(frames)
    let bytes = [UInt8](data)
    for frame in 0..<frames {
      for channel in 0..<Int(format.channelCount) {
        let i = (frame * Int(format.channelCount) + channel) * 2
        buffer.floatChannelData![channel][frame] =
          Float(Int16(bitPattern: UInt16(bytes[i]) | UInt16(bytes[i + 1]) << 8)) / 32768
      }
    }
    player.scheduleBuffer(buffer, completionCallbackType: .dataRendered) { [weak self] _ in
      guard let self else { return }
      self.lock.lock()
      defer { self.lock.unlock() }
      if self.generation == current {
        self.queued = max(0, self.queued - 1)
        self.queuedFrames = max(0, self.queuedFrames - frames)
        self.renderedCount += 1
      }
    }
  }
  func stop() {
    player?.stop()
    engine?.stop()
    player = nil
    engine = nil
    format = nil
    lock.lock()
    generation += 1
    queued = 0
    queuedFrames = 0
    lock.unlock()
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
  }
}
/// Decode at most one frame plus the newest waiting frame; stale JPEGs never build a queue.
final class ParentVideoDecoder {
  private let queue = DispatchQueue(label: "kidsmonitor.parent.jpeg", qos: .userInitiated)
  private let lock = NSLock()
  private var pending: (Data, Int, Int)?, decoding = false, generation = 0
  var onImage: ((UIImage, Int) -> Void)?
  func reset() {
    lock.lock()
    generation += 1
    pending = nil
    lock.unlock()
  }
  func submit(_ data: Data, rotation: Int) {
    guard data.count <= 1_048_576 else { return }
    lock.lock()
    pending = (data, rotation, generation)
    let start = !decoding
    decoding = true
    lock.unlock()
    if start { queue.async { self.drain() } }
  }
  private func drain() {
    while true {
      lock.lock()
      let next = pending
      pending = nil
      if next == nil { decoding = false }
      lock.unlock()
      guard let (data, rotation, current) = next else { return }
      autoreleasepool {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = properties[kCGImagePropertyPixelWidth] as? Int,
          let height = properties[kCGImagePropertyPixelHeight] as? Int,
          (1...4096).contains(width), (1...4096).contains(height),
          let cg = CGImageSourceCreateImageAtIndex(
            source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        else { return }
        let orientation: UIImage.Orientation =
          [0: .up, 90: .right, 180: .down, 270: .left][rotation] ?? .up
        let image = UIImage(cgImage: cg, scale: 1, orientation: orientation)
        DispatchQueue.main.async { [weak self] in
          guard let self else { return }
          self.lock.lock()
          let valid = current == self.generation
          self.lock.unlock()
          if valid { self.onImage?(image, current) }
        }
      }
    }
  }
}
