import Foundation
import XCTest

@testable import MonitorCore

final class ParentCoreTests: XCTestCase {
  func sample(_ start: Int64, _ end: Int64, _ peak: Double = -40) -> ParentNoiseSample {
    ParentNoiseSample(startMs: start, endMs: end, levelDb: -50, peakDb: peak, thresholdDb: -30)
  }
  func testExactCoverageOverlapsAndSchedulingGaps() {
    let chart = ParentNoiseChart(
      samples: [sample(1000, 4000), sample(3000, 5000), sample(5100, 7000)], now: 10000, hours: 1)
    XCTAssertEqual(chart.coveredMs, 5900)
    XCTAssertTrue(chart.gaps.contains(NoiseSpan(start: 5000, end: 5100)))
    XCTAssertEqual(
      chart.trend.count, 1,
      "A tiny scheduling gap shares an averaging window but remains missing coverage")
  }
  func testLongCaptureGapDoesNotShareTrend() {
    let chart = ParentNoiseChart(
      samples: [sample(1000, 5000, -60), sample(6000, 10000, -10)], now: 12000, hours: 3)
    XCTAssertEqual(chart.trend.count, 2)
    XCTAssertTrue(chart.trend[0].allSatisfy { abs($0.db + 60) < 0.001 })
    XCTAssertTrue(chart.trend[1].allSatisfy { abs($0.db + 10) < 0.001 })
    XCTAssertGreaterThanOrEqual(chart.top, -10)
  }
  func testDurationWeightUsesPeakAndRejectsInvalidSamples() {
    let chart = ParentNoiseChart(
      samples: [sample(0, 10000, -60), sample(10000, 11000, -10), sample(12000, 29000, -1)],
      now: 30000, hours: 3)
    XCTAssertEqual(chart.samples.count, 2)
    XCTAssertEqual(chart.trend[0][0].db, -55.455, accuracy: 0.01)
    XCTAssertGreaterThanOrEqual(chart.top - chart.bottom, 20)
  }
  func testParentQRRejectsMonitorRoleAndLeavesDefaultMonitorParsingIntact() throws {
    let base =
      "\"v\":1,\"host\":\"127.0.0.1\",\"port\":8443,\"fingerprint\":\"\(String(repeating:"ab",count:32))\",\"code\":\"123456\""
    XCTAssertThrowsError(
      try PairingPayload.parse("{\(base),\"role\":\"monitor\"}", expectedRole: "parent"))
    XCTAssertEqual(
      try PairingPayload.parse("{\(base),\"role\":\"parent\"}", expectedRole: "parent").role,
      "parent")
    XCTAssertEqual(try PairingPayload.parse("{\(base),\"role\":\"monitor\"}").role, "monitor")
  }
}
