import MonitorCore
import XCTest

@testable import KidsParent

final class ParentStoreTests: XCTestCase {
  private func event(_ id: Int64, _ monitor: String = "one", _ type: String = "LOUD_NOISE") throws
    -> ParentEvent
  {
    let json: [String: Any] = [
      "id": id, "monitorId": monitor, "type": type, "tsMs": id * 1000, "metadata": ["peakDb": -30],
    ]
    return try JSONDecoder().decode(
      ParentEvent.self, from: JSONSerialization.data(withJSONObject: json))
  }
  func testLiveEventsNeverSkipRecoveryAndCursorSurvivesReopen() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    var store: ParentStore? = try ParentStore(deviceId: "test", directory: directory)
    try store!.insert([event(1000)])
    XCTAssertEqual(try store!.recoveryCursor, 0)
    try store!.insert([event(1), event(2)], recovery: 2)
    store = nil
    store = try ParentStore(deviceId: "test", directory: directory)
    XCTAssertEqual(try store!.recoveryCursor, 2)
    XCTAssertEqual(try store!.events(monitor: nil, category: nil).map(\.id), [1000, 2, 1])
    try store!.insert([event(1)], recovery: 1)
    XCTAssertEqual(try store!.recoveryCursor, 2)
  }
  func testFiltersApplyBeforePagingAndRetainOfflineHistory() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = try ParentStore(deviceId: "test", directory: directory)
    var events: [ParentEvent] = []
    for i in 1...175 {
      events.append(
        try event(Int64(i), i % 2 == 0 ? "one" : "two", i % 3 == 0 ? "MOTION" : "LOUD_NOISE"))
    }
    try store.insert(events)
    let page = try store.events(monitor: "one", category: "Noise", offset: 2, limit: 10)
    XCTAssertEqual(page.count, 10)
    XCTAssertTrue(page.allSatisfy { $0.monitorId == "one" && $0.type == "LOUD_NOISE" })
    XCTAssertEqual(page.first?.id, 166)
    XCTAssertEqual(try store.events(monitor: nil, category: nil, offset: 150, limit: 50).count, 25)
  }
}
