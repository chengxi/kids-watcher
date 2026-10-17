import MonitorCore
import XCTest

@testable import KidsMonitor

final class HubIntegrationTests: XCTestCase {
  struct Fixture: Decodable {
    let host: String
    let port: Int
    let fingerprint: String
    let code: String
  }
  func fixture() async throws -> Fixture {
    let request = URLRequest(
      url: URL(string: "http://127.0.0.1:18556/fixture")!, timeoutInterval: 2)
    let data: Data
    do { (data, _) = try await URLSession.shared.data(for: request) } catch {
      throw XCTSkip("Start tools/ios/test-hub.mjs for simulator integration tests")
    }
    return try JSONDecoder().decode(Fixture.self, from: data)
  }
  func testProbePinMismatchAndSingleUsePairingWithRealHub() async throws {
    let fixture = try await fixture()
    let observed = try await HubTrust.probe(host: fixture.host, port: fixture.port)
    XCTAssertEqual(observed, try normalizeFingerprint(fixture.fingerprint))
    do {
      _ = try await HubTrust.pair(
        host: fixture.host, port: fixture.port, fingerprint: String(repeating: "00", count: 32),
        code: fixture.code, name: "iOS pin test")
      XCTFail("A mismatched certificate must be rejected before sending the code")
    } catch { /* Expected; the same single-use code must still work below. */  }
    let paired = try await HubTrust.pair(
      host: fixture.host, port: fixture.port, fingerprint: observed, code: fixture.code,
      name: "iOS pin test")
    XCTAssertEqual(paired.name, "iOS pin test")
    XCTAssertFalse(paired.token.isEmpty)
    do {
      _ = try await HubTrust.pair(
        host: fixture.host, port: fixture.port, fingerprint: observed, code: fixture.code,
        name: "iOS reuse test")
      XCTFail("Single-use pairing code accepted twice")
    } catch { XCTAssertTrue(error.localizedDescription.contains("invalid or expired")) }
  }
  @MainActor func testAuthenticatedSocketConfigHeartbeatAndNoiseReachRealHub() async throws {
    let fixture = try await fixture()
    let credentials = try await HubTrust.pair(
      host: fixture.host, port: fixture.port, fingerprint: fixture.fingerprint, code: fixture.code,
      name: "iOS socket test")
    let connected = expectation(description: "Authenticated config")
    let connection = HubConnection(credentials)
    var configs = 0
    var checkHeartbeat = false
    let acknowledged = expectation(description: "Long-lived socket heartbeat acknowledged")
    connection.onControl = { message in
      if message["t"] as? String == "config" {
        configs += 1
        if configs == 1 { connected.fulfill() }
      }
      if message["t"] as? String == "heartbeat_ack", checkHeartbeat {
        checkHeartbeat = false
        acknowledged.fulfill()
      }
    }
    connection.start()
    defer { connection.stop() }
    await fulfillment(of: [connected], timeout: 10)
    connection.send([
      "t": "heartbeat", "batteryPct": 87, "thermal": "none", "charging": true,
      "batteryTemperatureC": NSNull(), "speakerVolumePct": 80,
    ])
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    connection.send([
      "t": "noise_level",
      "sample": [
        "startMs": now - 20, "endMs": now, "levelDb": -50, "peakDb": -48, "thresholdDb": -38,
      ],
    ])
    // HTTP's bounded resource lifetime must not end the WebSocket after 15s.
    try await Task.sleep(nanoseconds: 20_000_000_000)
    checkHeartbeat = true
    connection.send(["t": "heartbeat", "batteryPct": 87, "thermal": "none"])
    await fulfillment(of: [acknowledged], timeout: 5)
    XCTAssertEqual(configs, 1)
    let (data, _) = try await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:18556/snapshot")!)
    let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
    let monitors = json["monitors"] as! [[String: Any]]
    let monitor = try XCTUnwrap(monitors.first { $0["id"] as? String == credentials.deviceId })
    XCTAssertEqual(monitor["online"] as? Bool, true)
    XCTAssertEqual(monitor["samples"] as? Int, 1)
  }
  @MainActor func testParentVoicePCMReachesMonitorThroughRealHub() async throws {
    let fixture = try await fixture()
    let credentials = try await HubTrust.pair(
      host: fixture.host, port: fixture.port, fingerprint: fixture.fingerprint, code: fixture.code,
      name: "iOS voice receiver test")
    let connected = expectation(description: "Monitor connected")
    let received = expectation(description: "Parent PCM arrived")
    let connection = HubConnection(credentials)
    var expectedTalk: UInt16?
    connection.onControl = { message in
      switch message["t"] as? String {
      case "config": connected.fulfill()
      case "start_stream":
        connection.send([
          "t": "stream_started", "streamId": message["streamId"]!, "video": NSNull(),
          "audio": ["sampleRate": 16000, "channels": 1],
        ])
      case "start_talk":
        expectedTalk = (message["talkId"] as? Int).flatMap(UInt16.init(exactly:))
        connection.send(["t": "talk_started", "talkId": message["talkId"]!])
      default: break
      }
    }
    connection.onMedia = { frame in
      XCTAssertEqual(frame.kind, 2)
      XCTAssertEqual(frame.id, expectedTalk)
      XCTAssertEqual(frame.payload.count, 640)
      XCTAssertEqual(Array(frame.payload.prefix(4)), [0x34, 0x12, 0xfe, 0xff])
      received.fulfill()
    }
    connection.start()
    defer { connection.stop() }
    await fulfillment(of: [connected], timeout: 5)
    _ = try await URLSession.shared.data(
      from: URL(string: "http://127.0.0.1:18556/voice?monitor=\(credentials.deviceId)")!)
    await fulfillment(of: [received], timeout: 5)
  }

}
