import MonitorCore
import SwiftUI
import XCTest

@testable import KidsMonitor

final class MonitorTests: XCTestCase {
  @MainActor func testLaunchNeverStartsCaptureAndNamingIsExplicit() {
    let model = MonitorModel()
    XCTAssertFalse(model.listening)
    XCTAssertFalse(model.connected)
    XCTAssertFalse(model.watching)
    model.name = " "
    model.continueName()
    XCTAssertEqual(model.setup, "name")
    model.name = " iPhone 13 Pro "
    model.continueName()
    XCTAssertEqual(model.name, "iPhone 13 Pro")
    XCTAssertEqual(model.setup, "qr")
    XCTAssertFalse(model.listening)
    model.stop()
    XCTAssertFalse(model.talking)
  }
  @MainActor func testDefaultDeviceNameCanBeAcceptedWithoutEditing() {
    let model = MonitorModel(loadCredentials: { nil }, defaultName: { "iPhone 13 Pro" })
    XCTAssertEqual(model.name, "iPhone 13 Pro")
    XCTAssertEqual(model.setup, "name")
    XCTAssertNil(model.credentials)
    model.pairQR("An unconfirmed default must not start pairing")
    XCTAssertNil(model.error)
    XCTAssertFalse(model.busy)
    model.continueName()
    XCTAssertEqual(model.setup, "qr")
    XCTAssertEqual(model.name, "iPhone 13 Pro")
    model.setup = "manual"
    XCTAssertEqual(model.name, "iPhone 13 Pro")
    model.setup = "name"
    model.name = "Blue iPhone"
    model.continueName()
    XCTAssertEqual(model.name, "Blue iPhone")
    XCTAssertFalse(model.listening)
  }

  @MainActor func testSavedNameTakesPrecedenceOverModelDefault() {
    let saved = Credentials(
      host: "localhost", port: 8444, fingerprint: String(repeating: "ab", count: 32),
      deviceId: "test", token: "test-only", name: "Blue iPhone")
    let model = MonitorModel(loadCredentials: { saved }, defaultName: { "iPhone 13 Pro" })
    XCTAssertEqual(model.name, "Blue iPhone")
    XCTAssertEqual(model.credentials?.name, "Blue iPhone")
  }

  @MainActor func testManualMismatchReturnsWithoutRedeemingCode() {
    let model = MonitorModel()
    model.setup = "confirm"
    model.code = "123456"
    model.fingerprint = String(repeating: "ab", count: 32)
    model.mismatch()
    XCTAssertEqual(model.setup, "manual")
    XCTAssertEqual(model.code, "123456")
    XCTAssertNil(model.fingerprint)
    XCTAssertFalse(model.busy)
  }
  @MainActor func testDashboardRendersBothLayouts() async throws {
    let model = MonitorModel()
    model.credentials = Credentials(
      host: "localhost", port: 8444, fingerprint: String(repeating: "ab", count: 32),
      deviceId: "test", token: "test-only", name: "iPhone 13 Pro")
    model.battery = 87
    model.charging = true
    for size in [CGSize(width: 390, height: 844), CGSize(width: 844, height: 390)] {
      // ImageRenderer cannot snapshot the UIKit-backed ScrollView. Host it in
      // an actual window so a nonnil but empty image cannot pass visual QA.
      let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
      let window = UIWindow(windowScene: scene)
      window.frame = CGRect(origin: .zero, size: size)
      let host = UIHostingController(
        rootView: MonitorRoot(model: model)
          .frame(width: size.width, height: size.height).environment(\.colorScheme, .dark))
      window.rootViewController = host
      window.makeKeyAndVisible()
      host.view.frame = CGRect(origin: .zero, size: size)
      host.view.setNeedsLayout()
      host.view.layoutIfNeeded()
      try await Task.sleep(nanoseconds: 200_000_000)
      let image = UIGraphicsImageRenderer(size: size).image { _ in
        host.view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
      }
      let pixels = try XCTUnwrap(image.cgImage?.dataProvider?.data) as Data
      XCTAssertGreaterThan(Set(pixels).count, 8, "Dashboard must render actual content")
      let attachment = XCTAttachment(image: image)
      attachment.name = "dashboard-\(Int(size.width))x\(Int(size.height))"
      attachment.lifetime = .keepAlways
      add(attachment)
      window.isHidden = true
      window.rootViewController = nil
    }
  }
}
