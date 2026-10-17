import Foundation
import MonitorCore

final class ParentAPI {
  private let credentials: Credentials
  private let session: URLSession
  init(_ credentials: Credentials) throws {
    self.credentials = credentials
    session = try HubTrust.pinned(
      host: credentials.host, port: credentials.port, fingerprint: credentials.fingerprint
    ).session()
  }
  deinit { session.invalidateAndCancel() }
  func get<T: Decodable>(_ path: String, as: T.Type) async throws -> T {
    var request = URLRequest(
      url: credentials.baseURL.appendingPathComponent(path.components(separatedBy: "?")[0]))
    if path.contains("?") {
      request.url = URL(string: "\(credentials.baseURL.absoluteString)/\(path)")
    }
    request.setValue("Bearer \(credentials.token)", forHTTPHeaderField: "Authorization")
    let (data, response) = try await session.data(for: request)
    guard let http = response as? HTTPURLResponse else {
      throw MonitorFailure("The hub didn't answer")
    }
    guard http.statusCode == 200 else {
      throw MonitorFailure(
        http.statusCode == 401
          ? "Pairing is no longer authorized. Check the hub's device list."
          : "Hub request failed (\(http.statusCode))")
    }
    return try JSONDecoder().decode(T.self, from: data)
  }
  struct Monitors: Decodable { let monitors: [ParentMonitor] }
  struct Events: Decodable { let events: [ParentEvent] }
  struct History: Decodable {
    let windowStartMs: Int64, toMs: Int64
    let samples: [ParentNoiseSample], events: [ParentEvent]
    let eventsTruncated: Bool
  }
}
