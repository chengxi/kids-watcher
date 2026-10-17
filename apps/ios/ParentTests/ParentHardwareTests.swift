import MonitorCore
import SwiftUI
import XCTest

@testable import KidsParent

final class ParentHardwareTests: XCTestCase {
  @MainActor func waitUntil(_ phase: String, seconds: Int = 30, _ condition: @escaping () -> Bool)
    async throws
  {
    let end = Date().addingTimeInterval(Double(seconds))
    while Date() < end {
      if condition() { return }
      try await Task.sleep(nanoseconds: 200_000_000)
    }
    throw MonitorFailure("Hardware acceptance timed out: \(phase)")
  }
  @MainActor func testPhysicalHubLiveAudioCameraAndLifecycle() async throws {
    #if targetEnvironment(simulator)
      throw XCTSkip("Requires the paired physical iPhone")
    #else
      let vault = CredentialStore(service: "com.kidsmonitor.parent.ios")
      var credentials = vault.load()
      if credentials == nil {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
          .appendingPathComponent("parent-pairing.json")
        guard let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8)
        else {
          throw XCTSkip(
            "Pair the parent or transfer a fresh parent debug payload before hardware acceptance")
        }
        defer { try? FileManager.default.removeItem(at: url) }
        let payload = try PairingPayload.parse(text, expectedRole: "parent")
        credentials = try await HubTrust.pair(
          host: payload.host, port: payload.port, fingerprint: payload.fingerprint,
          code: payload.code, name: "\(DeviceName.current) Parent", role: "parent")
        try vault.save(credentials!)
      }
      let model = ParentModel(credentials: try XCTUnwrap(credentials))
      defer {
        model.leaveLive()
        model.scene(.background)
      }
      let preferredName = ProcessInfo.processInfo.environment["KIDSMONITOR_TEST_MONITOR"]
      try await waitUntil("hub and monitor list") {
        model.connected && model.currentStatus
          && model.monitors.contains {
            $0.online && (preferredName == nil || $0.name == preferredName)
          }
      }
      let monitor = try XCTUnwrap(
        model.monitors.first {
          $0.online && (preferredName == nil || $0.name == preferredName)
        })
      model.selectedId = monitor.id
      await model.refreshNoise()
      XCTAssertFalse(
        model.noise.isEmpty, "Existing monitor should expose actual numeric measurements")
      let window = UIWindow(frame: UIScreen.main.bounds)
      let controller = UIHostingController(
        rootView: ParentRoot(model: model).preferredColorScheme(.light))
      window.rootViewController = controller
      window.makeKeyAndVisible()
      defer { window.isHidden = true }
      try await Task.sleep(nanoseconds: 500_000_000)
      attach(controller, size: CGSize(width: 390, height: 844), name: "parent-home-portrait")
      model.watch(monitor)
      try await waitUntil("real decoded video and rendered room PCM", seconds: 60) {
        model.image != nil && model.audioRenderedForTests > 5
      }
      XCTAssertTrue(model.audioAvailable)
      attach(
        controller, size: CGSize(width: 390, height: 844), name: "parent-live-portrait", dark: true)
      let previous = model.facing
      if model.cameras.count > 1 {
        model.switchCamera()
        try await waitUntil("camera switch") {
          model.facing != previous && !model.cameraPending && model.image != nil
        }
        model.switchCamera()
        try await waitUntil("camera restoration") {
          model.facing == previous && !model.cameraPending && model.image != nil
        }
      }
      model.toggleTalk()
      try await waitUntil("microphone permission and speaker readiness") { model.talking }
      try await waitUntil("physical microphone PCM sent") { model.voiceFramesSent > 5 }
      model.stopTalk()
      XCTAssertFalse(model.talking)
      model.setMuted()
      XCTAssertTrue(model.muted)
      model.setMuted()
      XCTAssertFalse(model.muted)
      attach(
        controller, size: CGSize(width: 844, height: 390), name: "parent-live-landscape", dark: true
      )
      model.savePhoto()
      try await waitUntil("add-only Photos authorization and snapshot", seconds: 45) {
        !model.photoBusy
      }
      XCTAssertEqual(model.notice, "Photo saved")
      model.notice = nil
      model.scene(.background)
      XCTAssertNil(model.watching)
      XCTAssertNil(model.activeStreamId)
      XCTAssertFalse(model.talking)
      XCTAssertFalse(model.connected)
      model.scene(.active)
      try await waitUntil("foreground reconnect") { model.connected && model.currentStatus }
      XCTAssertNil(model.watching, "Viewing and voice must not auto-resume")
    #endif
  }
  @MainActor private func attach(
    _ controller: UIHostingController<some View>, size: CGSize, name: String, dark: Bool = false
  ) {
    controller.overrideUserInterfaceStyle = dark ? .dark : .light
    controller.view.frame = CGRect(origin: .zero, size: size)
    controller.view.setNeedsLayout()
    controller.view.layoutIfNeeded()
    let image = UIGraphicsImageRenderer(size: size).image { _ in
      controller.view.drawHierarchy(in: controller.view.bounds, afterScreenUpdates: true)
    }
    let attachment = XCTAttachment(image: image)
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
