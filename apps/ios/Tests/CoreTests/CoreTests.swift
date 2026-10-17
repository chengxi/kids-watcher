import XCTest

@testable import MonitorCore

final class CoreTests: XCTestCase {
  func testFrameMatchesAndroidAndTypeScriptByteOrder() throws {
    let frame = MediaFrame(
      kind: 2, id: 0x1234, timestampUs: 0x0102_0304_0506_0708, payload: pcmData([0x1234, -2]))
    XCTAssertEqual(
      [UInt8](frame.encoded), [0x4b, 2, 0x12, 0x34, 1, 2, 3, 4, 5, 6, 7, 8, 0x34, 0x12, 0xfe, 0xff])
    XCTAssertEqual(try MediaFrame.decode(frame.encoded), frame)
  }
  func testRejectsMalformedMedia() {
    for bytes: [UInt8] in [
      [], [0x4b, 2], [0, 2, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0], [0x4b, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0],
      [0x4b, 2, 0, 1, 128, 0, 0, 0, 0, 0, 0, 0],
    ] {
      XCTAssertThrowsError(try MediaFrame.decode(Data(bytes)))
    }
  }
  func testPairingBoundariesAndRole() throws {
    let payload: [String: Any] = [
      "v": 1, "host": "192.168.1.100", "port": 8444,
      "fingerprint": String(repeating: "ab", count: 32), "code": "123456", "role": "monitor",
    ]
    func raw(_ data: [String: Any]) throws -> String {
      String(data: try JSONSerialization.data(withJSONObject: data), encoding: .utf8)!
    }
    XCTAssertEqual(
      try PairingPayload.parse(raw(payload)).baseURL.absoluteString, "https://192.168.1.100:8444")
    for (key, value): (String, Any) in [
      ("role", "parent"), ("v", 2), ("port", 0), ("host", "example.com/path"),
      ("host", "user@host"), ("fingerprint", "ab"), ("code", "abcdef"),
    ] {
      var bad = payload
      bad[key] = value
      XCTAssertThrowsError(try PairingPayload.parse(raw(bad)))
    }
    XCTAssertEqual(
      try normalizeFingerprint(String(repeating: "AB:", count: 31) + "AB"),
      String(repeating: "ab", count: 32))
  }
  func testModelDefaultUsesDeviceNameAndSafeFallback() {
    XCTAssertEqual(
      defaultMonitorName(modelIdentifier: "iPhone14,2", fallback: "iPhone"), "iPhone 13 Pro")
    XCTAssertEqual(
      defaultMonitorName(modelIdentifier: "future-model", fallback: "iPhone"), "iPhone")
    XCTAssertEqual(defaultMonitorName(modelIdentifier: "future-model", fallback: ""), "Monitor")
    XCTAssertEqual(
      defaultMonitorName(
        modelIdentifier: "future-model", fallback: String(repeating: "x", count: 65)), "Monitor")
  }

  func testDeviceNamesUseHubUTF16Limit() {
    XCTAssertNotNil(monitorNameError(" \n"))
    XCTAssertNil(monitorNameError(" iPhone 13 Pro "))
    XCTAssertNil(monitorNameError(String(repeating: "📱", count: 32)))
    XCTAssertNotNil(monitorNameError(String(repeating: "📱", count: 33)))
  }
  func testRmsUsesSignedPCM16() {
    XCTAssertEqual(rmsDb([]), -120)
    XCTAssertEqual(rmsDb([0, 0]), -120)
    XCTAssertEqual(rmsDb([16384, -16384]), -6.020599913, accuracy: 0.000001)
    XCTAssertEqual(rmsDb([-32768]), 0)
  }
  func testSustainCooldownAndAdaptiveFloorMatchAndroid() {
    let detector = NoiseDetector()
    for now in stride(from: Int64(0), through: 1480, by: 20) {
      XCTAssertNil(detector.window(-20, at: now))
    }
    let first = detector.window(-15, at: 1500)
    XCTAssertEqual(first?.durationMs, 1500)
    XCTAssertEqual(first?.peakDb, -15)
    XCTAssertEqual(detector.floor, -50)  // loud episodes cannot pull the floor up
    XCTAssertNil(detector.window(-20, at: 3020))
    XCTAssertNil(detector.window(-20, at: 61480))
    XCTAssertNotNil(detector.window(-20, at: 61500))
    _ = detector.window(-60, at: 61520)
    XCTAssertEqual(detector.floor, -50 + (1 - exp(-20.0 / 60000)) * -10, accuracy: 1e-10)
  }
  func testBelowThresholdResetsSustain() {
    let detector = NoiseDetector()
    XCTAssertNil(detector.window(-20, at: 0))
    XCTAssertNil(detector.window(-20, at: 1000))
    XCTAssertNil(detector.window(-60, at: 1020))
    XCTAssertNil(detector.window(-20, at: 1040))
    XCTAssertNil(detector.window(-20, at: 2500))
    XCTAssertNotNil(detector.window(-20, at: 2540))
  }
  func testCalibrationMedianResistsTransientOutlier() {
    let calibration = Calibrator(start: 0, duration: 5000)
    for (i, value) in [-55.0, -54, -53, -52, -10].enumerated() {
      XCTAssertNil(calibration.window(value, at: Int64(i * 1000)))
    }
    let result = calibration.window(-54, at: 5000)
    XCTAssertEqual(result?.floor, -54)
    XCTAssertEqual(result?.spread, 1)
    XCTAssertEqual(result?.sampledMs, 5000)
  }
  func testHistoryMeanEnergyAndNoInventedSilence() {
    let history = NoiseHistory()
    XCTAssertNil(history.window(-20, at: 1020, threshold: -38))
    XCTAssertNil(history.window(-40, at: 1040, threshold: -38))
    let sample = history.window(-60, at: 5000, threshold: -38)
    XCTAssertEqual(sample?.startMs, 1000)
    XCTAssertEqual(sample?.endMs, 1040)
    XCTAssertEqual(sample?.peakDb, -20)
    XCTAssertEqual(sample!.levelDb, 10 * log10((0.01 + 0.0001) / 2), accuracy: 1e-10)
    let second = history.finish()
    XCTAssertEqual(second?.startMs, 4980)
    XCTAssertEqual(second?.endMs, 5000)
    XCTAssertNil(history.finish())
  }
  func testHistorySplitsCalibrationAndBoundsIntervals() {
    let history = NoiseHistory()
    XCTAssertNil(history.window(-50, at: 20, threshold: nil))
    let calibration = history.window(-50, at: 40, threshold: -38)
    XCTAssertNil(calibration?.thresholdDb)
    XCTAssertEqual(calibration?.endMs, 20)
    var completed: NoiseSample?
    for now in stride(from: Int64(60), through: 15060, by: 20) {
      if let sample = history.window(-50, at: now, threshold: -38) { completed = sample }
    }
    XCTAssertNotNil(completed)
    XCTAssertLessThanOrEqual(completed!.endMs - completed!.startMs, 15000)
  }
}
