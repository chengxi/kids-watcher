import AVFoundation
import MonitorCore
import XCTest

@testable import KidsMonitor

private final class WindowCounter {
  private let lock = NSLock()
  private var value = 0
  func add(_ windows: Int) {
    lock.lock()
    value += windows
    lock.unlock()
  }
  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return value
  }
}

final class AudioHardwareTests: XCTestCase {
  @MainActor func testSpeakerActuallyConsumesPCMWhileMicrophoneRuns() async throws {
    #if targetEnvironment(simulator)
      throw XCTSkip("Requires physical iPhone audio hardware")
    #else
      guard AVAudioSession.sharedInstance().recordPermission == .granted else {
        throw XCTSkip("Grant microphone permission in the monitor before this test")
      }
      let audio = AudioCapture()
      let captured = expectation(description: "Real microphone windows")
      var seen = false
      audio.onWindows = { _ in
        if !seen {
          seen = true
          captured.fulfill()
        }
      }
      try audio.start()
      defer { audio.stop() }
      await fulfillment(of: [captured], timeout: 3)
      XCTAssertEqual(AVAudioSession.sharedInstance().mode, .measurement)
      try audio.startTalk()
      XCTAssertEqual(AVAudioSession.sharedInstance().mode, .default)
      for window in 0..<50 {
        let samples = (0..<320).map { i in
          Int16(sin(Double(window * 320 + i) * 2 * .pi * 440 / 16000) * 4000)
        }
        XCTAssertTrue(audio.play(pcmData(samples)))
        try await Task.sleep(nanoseconds: 20_000_000)
      }
      try await Task.sleep(nanoseconds: 300_000_000)
      let status = audio.playbackStatus
      print(
        "Hardware talk playback: received=\(status.received) rendered=\(status.rendered) dropped=\(status.dropped) outputRate=\(status.outputRate) volume=\(status.volume)"
      )
      XCTAssertTrue(audio.isCapturing)
      XCTAssertGreaterThan(
        status.rendered, 30, "Speaker must consume buffers rather than leave a full silent queue")
      XCTAssertTrue(status.rendering)
      XCTAssertEqual(status.queued, 0)
      XCTAssertLessThan(status.dropped, 5)
      audio.stopTalk()
      XCTAssertEqual(AVAudioSession.sharedInstance().mode, .measurement)
      XCTAssertTrue(audio.isCapturing)
    #endif
  }
  @MainActor func testCameraAndLensSwitchKeepSpeakerRendering() async throws {
    #if targetEnvironment(simulator)
      throw XCTSkip("Requires physical iPhone camera/audio hardware")
    #else
      guard AVAudioSession.sharedInstance().recordPermission == .granted,
        AVCaptureDevice.authorizationStatus(for: .video) == .authorized
      else {
        throw XCTSkip("Grant microphone and camera access before this test")
      }
      let audio = AudioCapture()
      let camera = CameraCapture()
      let windows = WindowCounter()
      audio.onWindows = { windows.add($0.count) }
      try audio.start()
      defer {
        audio.stop()
        camera.cancel()
      }
      let first = await camera.start(facing: "back") { _, _ in }
      XCTAssertNotNil(first)
      XCTAssertEqual(AVAudioSession.sharedInstance().mode, .measurement)
      try audio.startTalk()
      XCTAssertEqual(AVAudioSession.sharedInstance().mode, .default)
      let captureBeforeVoice = windows.count
      for facing in ["back", "front"] {
        if facing == "front" {
          camera.cancel()
          let next = await camera.start(facing: facing) { _, _ in }
          XCTAssertNotNil(next)
          XCTAssertEqual(AVAudioSession.sharedInstance().mode, .default)
        }
        let before = audio.playbackStatus.rendered
        for window in 0..<50 {
          let samples = (0..<320).map { i in
            Int16(sin(Double(window * 320 + i) * 2 * .pi * 440 / 16000) * 4000)
          }
          XCTAssertTrue(audio.play(pcmData(samples)))
          try await Task.sleep(nanoseconds: 20_000_000)
        }
        try await Task.sleep(nanoseconds: 300_000_000)
        let status = audio.playbackStatus
        print(
          "Camera + talk \(facing): rendered=\(status.rendered - before) queued=\(status.queued) volume=\(status.volume)"
        )
        XCTAssertGreaterThan(status.rendered - before, 30)
        XCTAssertTrue(audio.isCapturing)
        XCTAssertEqual(status.queued, 0)
      }
      XCTAssertGreaterThan(windows.count, captureBeforeVoice)
      audio.stopTalk()
      XCTAssertEqual(AVAudioSession.sharedInstance().mode, .measurement)
      let beforeRestore = windows.count
      try await Task.sleep(nanoseconds: 200_000_000)
      XCTAssertGreaterThan(
        windows.count, beforeRestore,
        "Room measurements must continue after restoring measurement mode")
    #endif
  }

}
