import Foundation

public let silenceDb = -120.0
public func rmsDb(_ samples: [Int16]) -> Double {
  guard !samples.isEmpty else { return silenceDb }
  let energy = samples.reduce(0.0) { $0 + pow(Double($1) / 32768, 2) } / Double(samples.count)
  return energy > 0 ? max(silenceDb, 10 * log10(energy)) : silenceDb
}
public struct Detection {
  public let peakDb: Double
  public let durationMs: Int
}
public final class NoiseDetector {
  public private(set) var config: MonitorConfig
  public private(set) var floor: Double
  private var above: Int64?, lastEvent: Int64?
  private var peak = silenceDb
  public init(_ config: MonitorConfig = MonitorConfig()) {
    self.config = config
    floor = config.noiseFloorDb
  }
  public func update(_ config: MonitorConfig) {
    self.config = config
    floor = config.noiseFloorDb
    reset()
  }
  public func setFloor(_ value: Double) { floor = value }
  private func reset() {
    above = nil
    peak = silenceDb
  }
  public func window(_ level: Double, at now: Int64) -> Detection? {
    if level <= floor + config.thresholdDb {
      floor += (1 - exp(-20.0 / 60000)) * (level - floor)
      reset()
      return nil
    }
    peak = max(peak, level)
    if above == nil { above = now }
    let elapsed = Int(now - above!)
    guard elapsed >= config.sustainMs,
      lastEvent == nil || now - lastEvent! >= Int64(config.cooldownS * 1000)
    else { return nil }
    let detection = Detection(peakDb: peak, durationMs: elapsed)
    lastEvent = now
    reset()
    return detection
  }
}
public struct CalibrationOutcome {
  public let floor: Double, spread: Double
  public let sampledMs: Int
}
public final class Calibrator {
  private let start: Int64, duration: Int
  private var samples: [Double] = []
  public init(start: Int64, duration: Int) {
    self.start = start
    self.duration = duration
  }
  public func window(_ value: Double, at now: Int64) -> CalibrationOutcome? {
    samples.append(value)
    guard now - start >= duration else { return nil }
    let sorted = samples.sorted()
    func percentile(_ p: Double) -> Double { sorted[Int(Double(sorted.count - 1) * p)] }
    return CalibrationOutcome(
      floor: percentile(0.5), spread: min(60, percentile(0.75) - percentile(0.25)),
      sampledMs: Int(now - start))
  }
}
public struct NoiseSample: Codable {
  public let startMs: Int64, endMs: Int64
  public let levelDb: Double, peakDb: Double, thresholdDb: Double?
  public var json: [String: Any] {
    [
      "startMs": startMs, "endMs": endMs, "levelDb": levelDb, "peakDb": peakDb,
      "thresholdDb": thresholdDb as Any? ?? NSNull(),
    ]
  }
}
public final class NoiseHistory {
  private var start: Int64?, end: Int64 = 0
  private var energy = 0.0, duration = 0.0, peak = silenceDb
  private var threshold: Double?
  public init() {}
  public func window(_ level: Double, at now: Int64, threshold next: Double?) -> NoiseSample? {
    let windowStart = now - 20
    let flush =
      start != nil
      && (windowStart - end > 250 || windowStart < end - 250 || now - start! > 15000
        || (threshold == nil) != (next == nil))
    let completed = flush ? finish() : nil
    if start == nil { start = max(0, windowStart) }
    let measured = min(0, max(silenceDb, level))
    energy += pow(10, measured / 10) * 20
    duration += 20
    peak = max(peak, measured)
    end = now
    threshold = next
    return completed
  }
  public func finish() -> NoiseSample? {
    defer {
      start = nil
      energy = 0
      duration = 0
      peak = silenceDb
    }
    guard let start, end > start, end - start <= 15000, duration > 0 else { return nil }
    return NoiseSample(
      startMs: start, endMs: end, levelDb: max(silenceDb, min(0, 10 * log10(energy / duration))),
      peakDb: peak, thresholdDb: threshold)
  }
}
