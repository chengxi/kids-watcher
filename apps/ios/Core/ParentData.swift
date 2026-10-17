import Foundation

public struct ParentMonitor: Codable, Identifiable, Equatable {
  public let id: String, name: String
  public var online: Bool
  public var lastSeenMs: Int64?
  public var batteryPct: Int?, charging: Bool?, batteryTemperatureC: Double?, speakerVolumePct: Int?
}
public struct ParentEvent: Codable, Identifiable, Equatable {
  public struct Metadata: Codable, Equatable { public var peakDb: Double?, durationMs: Int? }
  public let id: Int64, monitorId: String, type: String, tsMs: Int64
  public let metadata: Metadata
  public var title: String {
    switch type {
    case "LOUD_NOISE": return "Loud sound detected"
    case "MOTION": return "Movement detected"
    case "MONITOR_STARTED": return "Monitoring started"
    case "MONITOR_STOPPED": return "Monitoring stopped"
    case "MONITOR_LOST": return "Monitor offline"
    case "MONITOR_ONLINE": return "Monitor back online"
    default: return "Monitor event"
    }
  }
  public var category: String {
    switch type {
    case "LOUD_NOISE": return "Noise"
    case "MOTION": return "Movement"
    default: return "Monitoring"
    }
  }
}
public struct ParentNoiseSample: Codable, Equatable {
  public var startMs: Int64, endMs: Int64
  public var levelDb: Double, peakDb: Double, thresholdDb: Double?
  public init(startMs: Int64, endMs: Int64, levelDb: Double, peakDb: Double, thresholdDb: Double?) {
    self.startMs = startMs
    self.endMs = endMs
    self.levelDb = levelDb
    self.peakDb = peakDb
    self.thresholdDb = thresholdDb
  }
  public var valid: Bool {
    endMs > startMs && endMs - startMs <= 15000 && (-120...0).contains(peakDb)
      && (-120...0).contains(levelDb)
  }
}
public struct NoiseSpan: Equatable { public var start: Int64, end: Int64 }
public struct TrendPoint: Equatable { public var time: Int64, db: Double }
public struct ParentNoiseChart {
  public let from: Int64, to: Int64
  public let samples: [ParentNoiseSample], coverage: [NoiseSpan], gaps: [NoiseSpan]
  public let trend: [[TrendPoint]]
  public let threshold: Double?
  public let bottom: Double, top: Double
  public init(samples input: [ParentNoiseSample], now: Int64, hours: Int, alertPeaks: [Double] = [])
  {
    to = now
    let from = now - Int64(hours) * 3_600_000
    self.from = from
    var seen = Set<Int64>()
    let samples = input.filter { $0.valid && $0.endMs > from && $0.startMs < now }.sorted {
      $0.startMs < $1.startMs
    }.filter { seen.insert($0.startMs).inserted }
    self.samples = samples
    var spans: [NoiseSpan] = []
    for s in samples {
      let span = NoiseSpan(start: max(from, s.startMs), end: min(now, s.endMs))
      if let last = spans.last, span.start <= last.end {
        spans[spans.count - 1].end = max(last.end, span.end)
      } else {
        spans.append(span)
      }
    }
    coverage = spans
    var missing: [NoiseSpan] = []
    var cursor = from
    for span in spans {
      if span.start > cursor { missing.append(NoiseSpan(start: cursor, end: span.start)) }
      cursor = span.end
    }
    if cursor < now { missing.append(NoiseSpan(start: cursor, end: now)) }
    gaps = missing
    let threshold = samples.last?.thresholdDb
    self.threshold = threshold
    let window = Int64(([1: 30, 3: 60, 12: 180, 24: 360][hours] ?? 60) * 60000)
    var segments: [[ParentNoiseSample]] = []
    for var s in samples {
      if let end = segments.last?.last?.endMs, s.startMs <= end + 250 {
        s.startMs = max(s.startMs, end)
      } else {
        segments.append([])
      }
      if s.endMs > s.startMs { segments[segments.count - 1].append(s) }
    }
    trend = segments.filter { !$0.isEmpty }.map { segment in
      let start = segment.first!.startMs
      let end = segment.last!.endMs
      var times = Array(stride(from: start, to: end, by: Int(max(15000, window / 4))))
      times.append(end)
      return times.compactMap { at in
        var total = 0.0
        var weights = 0.0
        func integral(_ t: Int64) -> Double {
          let x = Double(t - at)
          return x / 2 + Double(window) / (4 * .pi) * sin(2 * .pi * x / Double(window))
        }
        for s in segment {
          let l = max(s.startMs, at - window / 2)
          let r = min(s.endMs, at + window / 2)
          if r > l {
            let w = max(0, integral(r) - integral(l))
            total += s.peakDb * w
            weights += w
          }
        }
        return weights > 0 ? TrendPoint(time: at, db: total / weights) : nil
      }
    }
    let values =
      samples.map(\.peakDb) + alertPeaks.filter { $0.isFinite && (-120...0).contains($0) } + (threshold.map { [$0] } ?? [])
    var low = (values.min() ?? -57) - 3
    var high = (values.max() ?? -3) + 3
    if high - low < 20 {
      let mid = (low + high) / 2
      low = mid - 10
      high = mid + 10
    }
    low = floor(low / 5) * 5
    high = ceil(high / 5) * 5
    if low < -120 {
      high += -120 - low
      low = -120
    }
    let ceiling = max(0, threshold ?? 0)
    if high > ceiling {
      low -= high - ceiling
      high = ceiling
    }
    bottom = max(-120, low)
    top = high
  }
  public var coveredMs: Int64 { coverage.reduce(0) { $0 + $1.end - $1.start } }
}
