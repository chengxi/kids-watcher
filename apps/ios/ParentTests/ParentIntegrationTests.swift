import MonitorCore
import UIKit
import XCTest

@testable import KidsParent

final class ParentIntegrationTests: XCTestCase {
  struct Fixture: Decodable { let host: String, port: Int, fingerprint: String, code: String }
  func pair(_ role: String) async throws -> Credentials {
    let url = URL(
      string: "http://127.0.0.1:18556/fixture\(role == "parent" ? "?role=parent" : "")")!
    let data: Data
    do {
      (data, _) = try await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 2))
    } catch { throw XCTSkip("Start tools/ios/test-hub.mjs for loopback integration") }
    let f = try JSONDecoder().decode(Fixture.self, from: data)
    return try await HubTrust.pair(
      host: f.host, port: f.port, fingerprint: f.fingerprint, code: f.code,
      name: "iOS \(role) integration", role: role)
  }
  @MainActor func waitUntil(
    _ phase: String = "state", seconds: Int = 10, _ condition: @escaping () -> Bool
  ) async throws {
    let end = Date().addingTimeInterval(Double(seconds))
    while Date() < end {
      if condition() { return }
      try await Task.sleep(nanoseconds: 100_000_000)
    }
    XCTFail("Expected \(phase) did not arrive before deadline")
    throw MonitorFailure("Missing \(phase)")
  }
  @MainActor func testParentSharesSocketForHistoryMediaCameraAndVoice() async throws {
    let monitorCredentials = try await pair("monitor")
    let parentCredentials = try await pair("parent")
    let monitor = HubConnection(monitorCredentials)
    let parent = ParentModel(credentials: parentCredentials)
    var monitorConnected = false
    var liveId: UInt16?
    var voiceId: UInt16?
    var voiceReceived = false
    var camera = "back"
    var streamStopped = false
    var ticker: Task<Void, Never>?
    let image = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 48)).image { context in
      UIColor.orange.setFill()
      context.fill(CGRect(x: 0, y: 0, width: 64, height: 48))
    }.jpegData(compressionQuality: 0.6)!
    func announce() {
      monitor.send([
        "t": "stream_started", "streamId": Int(liveId!),
        "video": [
          "width": 64, "height": 48, "fps": 10, "rotationDegrees": 90, "facing": camera,
          "availableCameras": ["back", "front"],
        ], "audio": ["sampleRate": 16000, "channels": 1],
      ])
    }
    monitor.onControl = { message in
      switch message["t"] as? String {
      case "config": monitorConnected = true
      case "start_stream":
        liveId = UInt16(message["streamId"] as! Int)
        announce()
        ticker?.cancel()
        ticker = Task {
          var timestamp: UInt64 = 1000
          while !Task.isCancelled {
            if let liveId {
              monitor.send(MediaFrame(kind: 1, id: liveId, timestampUs: timestamp, payload: image))
              monitor.send(
                MediaFrame(
                  kind: 2, id: liveId, timestampUs: timestamp,
                  payload: pcmData(Array(repeating: 1000, count: 320))))
            }
            timestamp += 20000
            try? await Task.sleep(nanoseconds: 20_000_000)
          }
        }
      case "select_camera":
        camera = message["facing"] as! String
        announce()
        monitor.send([
          "t": "camera_result", "streamId": Int(liveId!), "requestId": message["requestId"]!,
          "reason": NSNull(),
        ])
      case "start_talk":
        voiceId = UInt16(message["talkId"] as! Int)
        monitor.send(["t": "talk_started", "talkId": Int(voiceId!)])
      case "stop_stream":
        streamStopped = true
        ticker?.cancel()
        monitor.send([
          "t": "stream_stopped", "streamId": message["streamId"]!, "reason": "last viewer left",
        ])
      default: break
      }
    }
    monitor.onMedia = { frame in if frame.id == voiceId { voiceReceived = true } }
    monitor.start()
    defer {
      ticker?.cancel()
      parent.leaveLive()
      parent.scene(.background)
      monitor.stop()
    }
    try await waitUntil("monitor connection") { monitorConnected }
    await parent.refresh()
    try await waitUntil("parent connection and device list") {
      parent.connected && parent.monitors.contains { $0.id == monitorCredentials.deviceId }
    }
    monitor.send([
      "t": "event", "clientEventId": UUID().uuidString, "type": "LOUD_NOISE",
      "tsMs": ParentModel.now,
      "metadata": ["peakDb": -20, "durationMs": 2000],
    ])
    monitor.send([
      "t": "noise_level",
      "sample": [
        "startMs": ParentModel.now - 1000, "endMs": ParentModel.now, "levelDb": -50, "peakDb": -45,
        "thresholdDb": -30,
      ],
    ])
    try await waitUntil("live event") {
      parent.events.contains {
        $0.monitorId == monitorCredentials.deviceId && $0.type == "LOUD_NOISE"
      }
    }
    parent.selectedId = monitorCredentials.deviceId
    await parent.refreshNoise()
    XCTAssertFalse(parent.noise.isEmpty)
    parent.watch(try XCTUnwrap(parent.monitors.first { $0.id == monitorCredentials.deviceId }))
    try await waitUntil("picture and audio format") { parent.image != nil && parent.audioAvailable }
    XCTAssertEqual(parent.image?.imageOrientation, .right)
    parent.switchCamera()
    try await waitUntil("front camera") { parent.facing == "front" && !parent.cameraPending }
    // Simulator microphone permission is pre-granted by simctl for this suite.
    parent.toggleTalk()
    try await waitUntil("speaker ready: \(parent.notice ?? "no notice")") { parent.talking }
    parent.sendVoice([Array(repeating: 1234, count: 320)])
    try await waitUntil("voice PCM received") { voiceReceived }
    parent.stopTalk()
    XCTAssertFalse(parent.talking)
    parent.scene(.background)
    try await waitUntil("last viewer released") { streamStopped }
    XCTAssertNil(parent.watching)
    XCTAssertNil(parent.activeStreamId)
    XCTAssertFalse(parent.connected)
  }
}
