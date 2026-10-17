import MonitorCore
import XCTest

@testable import KidsMonitor

@MainActor
private final class TestHub: HubTransport {
  var onConnected: (() -> Void)?, onDisconnected: (() -> Void)?, onState: ((String) -> Void)?
  var onControl: (([String: Any]) -> Void)?, onMedia: ((MediaFrame) -> Void)?,
    heartbeat: (() -> [String: Any])?
  var controls: [[String: Any]] = [], media: [MediaFrame] = []
  var didStart: (() -> Void)?
  func start() {
    onConnected?()
    didStart?()
  }
  func stop() { onDisconnected?() }
  func send(_ value: [String: Any]) { controls.append(value) }
  func send(_ frame: MediaFrame) { media.append(frame) }
  func discardMedia() { media.removeAll() }
}
private final class TestAudio: AudioCapturing {
  var onWindows: (([[Int16]]) -> Void)?, onFailure: (() -> Void)?
  var isCapturing = false, speaker = false
  var played: [Data] = []
  func start() throws { isCapturing = true }
  func stop() {
    isCapturing = false
    speaker = false
  }
  func startTalk() throws { speaker = true }
  func play(_ data: Data) -> Bool {
    played.append(data)
    return speaker
  }
  func stopTalk() { speaker = false }
}
private final class TestCamera: CameraCapturing {
  var onFailure: (() -> Void)?
  var pending: CheckedContinuation<CameraFormat?, Never>?
  var frame: ((Data, CameraFormat) -> Void)?
  var facing = "back", starts = 0
  func start(facing: String, onFrame: @escaping (Data, CameraFormat) -> Void) async -> CameraFormat?
  {
    starts += 1
    self.facing = facing
    frame = onFrame
    return await withCheckedContinuation { pending = $0 }
  }
  func cancel() {
    let reply = pending
    pending = nil
    reply?.resume(returning: nil)
  }
  func prove() {
    let reply = pending
    pending = nil
    reply?.resume(
      returning: CameraFormat(width: 640, height: 480, facing: facing, cameras: ["back", "front"]))
  }
}
final class MediaLifecycleTests: XCTestCase {
  @MainActor private func running() async -> (MonitorModel, TestHub, TestAudio, TestCamera) {
    let hub = TestHub()
    let audio = TestAudio()
    let camera = TestCamera()
    let started = expectation(description: "Capture explicitly started")
    hub.didStart = { started.fulfill() }
    let model = MonitorModel(
      loadCredentials: {
        Credentials(
          host: "localhost", port: 8444, fingerprint: String(repeating: "ab", count: 32),
          deviceId: "fixture", token: "test-only", name: "Test iPhone")
      }, camera: camera, audioFactory: { audio }, connectionFactory: { _ in hub },
      microphonePermission: { true })
    model.start()
    await fulfillment(of: [started], timeout: 2)
    return (model, hub, audio, camera)
  }
  @MainActor private func settle() async { for _ in 0..<15 { await Task.yield() } }
  @MainActor func testCameraRequiresProofAndStopCancelsPendingStart() async {
    let (model, hub, audio, camera) = await running()
    defer { model.stop() }
    hub.onControl?(["t": "start_stream", "streamId": 42, "video": true, "audio": true])
    await settle()
    XCTAssertEqual(camera.starts, 1)
    XCTAssertFalse(model.watching)
    XCTAssertFalse(hub.controls.contains { $0["t"] as? String == "stream_started" })
    hub.onControl?(["t": "stop_stream", "streamId": 42])
    await settle()
    XCTAssertNil(camera.pending)
    XCTAssertFalse(model.watching)
    XCTAssertTrue(audio.isCapturing)
    XCTAssertFalse(hub.controls.contains { $0["t"] as? String == "stream_started" })
  }
  @MainActor func testBackgroundReleasesVideoAndVoiceButKeepsSound() async {
    let (model, hub, audio, camera) = await running()
    defer { model.stop() }
    hub.onControl?(["t": "start_stream", "streamId": 42, "video": true, "audio": true])
    await settle()
    camera.prove()
    await settle()
    XCTAssertTrue(model.watching)
    hub.onControl?(["t": "start_talk", "streamId": 42, "talkId": 43])
    XCTAssertTrue(model.talking)
    let good = MediaFrame(kind: 2, id: 43, timestampUs: 0, payload: pcmData([1, -2]))
    hub.onMedia?(good)
    XCTAssertEqual(audio.played.count, 1)
    hub.onMedia?(MediaFrame(kind: 2, id: 99, timestampUs: 0, payload: good.payload))
    XCTAssertEqual(audio.played.count, 1)
    hub.onMedia?(MediaFrame(kind: 2, id: 43, timestampUs: 0, payload: Data([1])))
    XCTAssertEqual(audio.played.count, 1)
    model.scene(.background)
    await settle()
    XCTAssertFalse(model.watching)
    XCTAssertFalse(model.talking)
    XCTAssertFalse(audio.speaker)
    XCTAssertTrue(audio.isCapturing)
    let format = hub.controls.last { $0["t"] as? String == "stream_started" }
    XCTAssertTrue(format?["video"] is NSNull)
    XCTAssertNotNil(format?["audio"] as? [String: Any])
    model.scene(.active)
    await settle()
    XCTAssertEqual(camera.starts, 1)
    XCTAssertFalse(model.watching)
  }
  @MainActor func testCameraSwitchPreservesAudioAndTalkAndRejectsStaleFrames() async {
    let (model, hub, audio, camera) = await running()
    defer { model.stop() }
    hub.onControl?(["t": "start_stream", "streamId": 42, "video": true, "audio": true])
    await settle()
    camera.prove()
    await settle()
    let stale = camera.frame
    hub.onControl?(["t": "start_talk", "streamId": 42, "talkId": 43])
    hub.onControl?(["t": "select_camera", "streamId": 42, "requestId": "switch", "facing": "front"])
    await settle()
    XCTAssertTrue(model.talking)
    XCTAssertTrue(audio.isCapturing)
    XCTAssertEqual(camera.facing, "front")
    stale?(
      Data([1]), CameraFormat(width: 640, height: 480, facing: "back", cameras: ["back", "front"]))
    await settle()
    XCTAssertTrue(hub.media.isEmpty)
    camera.prove()
    await settle()
    XCTAssertTrue(model.watching)
    let reply = hub.controls.last { $0["t"] as? String == "camera_result" }
    XCTAssertTrue(reply?["reason"] is NSNull)
    hub.onDisconnected?()
    await settle()
    XCTAssertFalse(model.watching)
    XCTAssertFalse(model.talking)
    XCTAssertTrue(model.listening)
  }
  @MainActor func testMicrophoneFailureStopsConnectionAndDoesNotRestartOnResume() async {
    let (model, _, audio, _) = await running()
    audio.onFailure?()
    await settle()
    XCTAssertFalse(model.listening)
    XCTAssertFalse(model.connected)
    XCTAssertFalse(audio.isCapturing)
    model.scene(.active)
    XCTAssertFalse(model.listening)
    XCTAssertNotNil(model.error)
  }
  @MainActor func testStopWhilePermissionIsPendingNeverStartsCapture() async {
    let hub = TestHub()
    let audio = TestAudio()
    let camera = TestCamera()
    var permission: CheckedContinuation<Bool, Never>?
    let model = MonitorModel(
      loadCredentials: {
        Credentials(
          host: "localhost", port: 8444, fingerprint: String(repeating: "ab", count: 32),
          deviceId: "fixture", token: "test-only", name: "Test iPhone")
      }, camera: camera, audioFactory: { audio }, connectionFactory: { _ in hub },
      microphonePermission: { await withCheckedContinuation { permission = $0 } })
    model.start()
    await settle()
    XCTAssertNotNil(permission)
    model.stop()
    permission?.resume(returning: true)
    await settle()
    XCTAssertFalse(model.listening)
    XCTAssertFalse(audio.isCapturing)
    XCTAssertTrue(hub.controls.isEmpty)
  }

}
