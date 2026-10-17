import XCTest

final class ParentUITests: XCTestCase {
  @MainActor func testPhysicalDashboardAndLiveControls() throws {
    let app = XCUIApplication()
    addUIInterruptionMonitor(withDescription: "Permissions") { alert in
      for title in ["Allow", "OK", "Allow Access to All Photos", "Add Photos Only"] {
        if alert.buttons[title].exists {
          alert.buttons[title].tap()
          return true
        }
      }
      return false
    }
    app.launchArguments = ["--debug-pair"]
    app.launch()
    app.tap()
    guard app.buttons["Watch live"].waitForExistence(timeout: 30) else {
      throw XCTSkip("Pair the parent app with a reachable hub before hardware UI acceptance")
    }
    let home = XCTAttachment(screenshot: app.screenshot())
    home.name = "parent-home-portrait"
    home.lifetime = .keepAlways
    add(home)
    app.buttons["Watch live"].tap()
    XCTAssertTrue(app.images["Live picture"].waitForExistence(timeout: 60))
    let live = XCTAttachment(screenshot: app.screenshot())
    live.name = "parent-live-portrait"
    live.lifetime = .keepAlways
    add(live)
    app.buttons["Mute"].tap()
    XCTAssertTrue(app.buttons["Unmute"].exists)
    app.buttons["Unmute"].tap()
    if app.buttons["Switch"].isEnabled {
      app.buttons["Switch"].tap()
      XCTAssertTrue(app.images["Live picture"].waitForExistence(timeout: 20))
    }
    app.buttons["Talk to monitor"].tap()
    app.tap()
    XCTAssertTrue(app.buttons["Stop talking"].waitForExistence(timeout: 15))
    app.buttons["Stop talking"].tap()
    XCUIDevice.shared.orientation = .landscapeLeft
    let landscape = XCTAttachment(screenshot: app.screenshot())
    landscape.name = "parent-live-landscape"
    landscape.lifetime = .keepAlways
    add(landscape)
    XCUIDevice.shared.orientation = .portrait
    app.buttons["Back to home"].tap()
    XCTAssertTrue(app.buttons["Watch live"].waitForExistence(timeout: 10))
    app.buttons["Status"].tap()
    XCTAssertTrue(app.staticTexts["Keep the app open for alerts"].waitForExistence(timeout: 5))
  }
}
