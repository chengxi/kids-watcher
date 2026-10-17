import CryptoKit
import Foundation
import MonitorCore
import Security

final class CredentialStore {
  private let query: [String: Any]
  init(service: String = "com.kidsmonitor.monitor.ios") { query = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: service, kSecAttrAccount as String: "hub",
  ] }
  func load() -> Credentials? {
    var request = query
    request[kSecReturnData as String] = true
    request[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
      let data = result as? Data
    else { return nil }
    return try? JSONDecoder().decode(Credentials.self, from: data)
  }
  func save(_ credentials: Credentials) throws {
    let data = try JSONEncoder().encode(credentials)
    let update =
      [
        kSecValueData as String: data,
        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
      ] as [String: Any]
    let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
    if status == errSecItemNotFound {
      guard SecItemAdd(query.merging(update) { _, b in b } as CFDictionary, nil) == errSecSuccess
      else { throw MonitorFailure("Couldn't save pairing securely") }
    } else if status != errSecSuccess {
      throw MonitorFailure("Couldn't save pairing securely")
    }
  }
}

/// Pin the DER certificate itself, matching Android, including self-signed LAN certificates.
/// The unauthenticated probe can only GET /healthz; it never receives a code or a token.
final class HubTrust: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
  private let host: String, port: Int, pin: String?
  private(set) var observed: String?
  private init(host: String, port: Int, pin: String?) {
    self.host = host
    self.port = port
    self.pin = pin
  }
  static func pinned(host: String, port: Int, fingerprint: String) throws -> HubTrust {
    try validateAddress(host, port)
    return HubTrust(host: host, port: port, pin: try normalizeFingerprint(fingerprint))
  }
  func session(webSocket: Bool = false) -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 10
    config.timeoutIntervalForResource = webSocket ? .greatestFiniteMagnitude : 15
    config.httpCookieStorage = nil
    config.urlCache = nil
    return URLSession(configuration: config, delegate: self, delegateQueue: nil)
  }
  func urlSession(
    _ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
      challenge.protectionSpace.host.lowercased() == host.lowercased(),
      challenge.protectionSpace.port == port,
      let trust = challenge.protectionSpace.serverTrust,
      let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
      let leaf = certificates.first
    else {
      completionHandler(.cancelAuthenticationChallenge, nil)
      return
    }
    let hash = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map {
      String(format: "%02x", $0)
    }.joined()
    guard pin == nil || pin == hash else {
      completionHandler(.cancelAuthenticationChallenge, nil)
      return
    }
    observed = hash
    completionHandler(.useCredential, URLCredential(trust: trust))
  }
  // Never forward pairing material or authorization through a redirect.
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
  ) { completionHandler(nil) }
  static func probe(host: String, port: Int) async throws -> String {
    try validateAddress(host, port)
    let trust = HubTrust(host: host, port: port, pin: nil)
    let session = trust.session()
    defer { session.invalidateAndCancel() }
    let (data, response) = try await session.data(
      from: URL(string: "https://\(host):\(port)/healthz")!)
    guard (response as? HTTPURLResponse)?.statusCode == 200,
      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      json["ok"] as? Bool == true,
      let fingerprint = trust.observed
    else { throw MonitorFailure("This address did not answer as a Kids Monitor hub") }
    return fingerprint
  }
  static func pair(host: String, port: Int, fingerprint: String, code: String, name: String, role: String = "monitor")
    async throws -> Credentials
  {
    guard monitorNameError(name) == nil else { throw MonitorFailure("Enter a valid monitor name") }
    let trust = try pinned(host: host, port: port, fingerprint: fingerprint)
    let session = trust.session()
    defer { session.invalidateAndCancel() }
    var request = URLRequest(url: URL(string: "https://\(host):\(port)/pair")!)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.httpBody = try JSONSerialization.data(withJSONObject: [
      "code": code, "deviceName": name, "expectRole": role,
    ])
    let (data, response) = try await session.data(for: request)
    guard let response = response as? HTTPURLResponse else {
      throw MonitorFailure("The hub didn't answer")
    }
    guard response.statusCode == 201 else {
      let message: String
      switch response.statusCode {
      case 409: message = "This app needs a \(role) pairing code. The code has not been used."
      case 429: message = "Too many pairing attempts. Wait before trying again."
      case 400, 401, 403, 404:
        message = "Pairing code is invalid or expired. Request a fresh \(role) code."
      default: message = "The hub couldn't complete pairing (\(response.statusCode))"
      }
      throw MonitorFailure(message)
    }
    struct Reply: Decodable {
      let deviceId: String
      let token: String
    }
    let reply = try JSONDecoder().decode(Reply.self, from: data)
    return Credentials(
      host: host, port: port, fingerprint: try normalizeFingerprint(fingerprint),
      deviceId: reply.deviceId, token: reply.token, name: name)
  }
}

@MainActor
final class HubConnection {
  var onConnected: (() -> Void)?
  var onDisconnected: (() -> Void)?
  var onControl: (([String: Any]) -> Void)?
  var onMedia: ((MediaFrame) -> Void)?
  var onState: ((String) -> Void)?
  var heartbeat: (() -> [String: Any])?
  private let credentials: Credentials
  private let role: String
  private var session: URLSession?
  private var socket: URLSessionWebSocketTask?
  private var loop: Task<Void, Never>?, sender: Task<Void, Never>?, pulse: Task<Void, Never>?
  private var controls: [URLSessionWebSocketTask.Message] = []
  private var media: [URLSessionWebSocketTask.Message] = []
  private var lastReceived = Date()
  private var active = false, connected = false
  init(_ credentials: Credentials, role: String = "monitor") { self.credentials = credentials; self.role = role }
  func start() {
    guard !active else { return }
    active = true
    loop = Task { [weak self] in
      guard let self else { return }
      var backoff: UInt64 = 1
      while self.active && !Task.isCancelled {
        self.onState?("Connecting to home hub…")
        do {
          let trust = try HubTrust.pinned(
            host: self.credentials.host, port: self.credentials.port,
            fingerprint: self.credentials.fingerprint)
          let session = trust.session(webSocket: true)
          self.session = session
          var request = URLRequest(url: URL(string: "\(self.credentials.baseURL.absoluteString.replacingOccurrences(of: "https://", with: "wss://"))/ws?role=\(self.role)")!)
          request.setValue("Bearer \(self.credentials.token)", forHTTPHeaderField: "Authorization")
          let ws = session.webSocketTask(with: request)
          self.socket = ws
          ws.maximumMessageSize = 1_048_576
          ws.resume()
          try await ws.send(
            .string(
              Self.json(["t": "hello", "deviceName": self.credentials.name, "appVersion": "0.1.0"]))
          )
          // A parent has no initial config; request an acknowledgement before declaring connection.
          if self.role == "parent" { try await ws.send(.string(Self.json(["t": "heartbeat"]))) }
          // The first hub response proves an authenticated connection.
          let first = try await ws.receive()
          self.connected = true
          self.lastReceived = Date()
          backoff = 1
          self.onState?("Connected via home hub")
          self.onConnected?()
          self.deliver(first)
          self.startPulse(ws)
          while self.active && !Task.isCancelled { self.deliver(try await ws.receive()) }
        } catch { /* Do not log URLs, tokens, payloads or server response bodies. */  }
        self.cleanup()
        guard self.active && !Task.isCancelled else { break }
        self.onState?("Hub disconnected · retrying in \(backoff)s")
        try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
        backoff = min(30, backoff * 2)
      }
    }
  }
  func stop() {
    active = false
    loop?.cancel()
    loop = nil
    cleanup()
    onState?("Stopped")
  }
  private func cleanup() {
    connected = false
    pulse?.cancel()
    pulse = nil
    sender?.cancel()
    sender = nil
    socket?.cancel(with: .goingAway, reason: nil)
    socket = nil
    session?.invalidateAndCancel()
    session = nil
    controls.removeAll()
    media.removeAll()
    onDisconnected?()
  }
  private func startPulse(_ ws: URLSessionWebSocketTask) {
    pulse = Task { [weak self, weak ws] in
      while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 15_000_000_000)
        guard !Task.isCancelled, let self, let ws, self.socket === ws else { return }
        if Date().timeIntervalSince(self.lastReceived) > 45 {
          ws.cancel(with: .goingAway, reason: nil)
          return
        }
        if let heartbeat = self.heartbeat?() { self.send(heartbeat) }
      }
    }
  }
  private func deliver(_ message: URLSessionWebSocketTask.Message) {
    lastReceived = Date()
    switch message {
    case .string(let text):
      guard let data = text.data(using: .utf8),
        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
      else { return }
      onControl?(json)
    case .data(let data): if let frame = try? MediaFrame.decode(data) { onMedia?(frame) }
    @unknown default: break
    }
  }
  static func json(_ value: [String: Any]) -> String {
    String(data: (try? JSONSerialization.data(withJSONObject: value)) ?? Data(), encoding: .utf8)
      ?? "{}"
  }
  func send(_ value: [String: Any]) {
    guard connected else { return }
    guard controls.count < 128 else {
      socket?.cancel(with: .goingAway, reason: nil)
      return
    }
    controls.append(.string(Self.json(value)))
    drain()
  }
  func send(_ frame: MediaFrame) {
    guard connected else { return }
    // At most 100ms audio + one JPEG. Media loss must never delay control.
    if media.count >= 6 { media.removeFirst() }
    if frame.kind == 1 {
      media.removeAll {
        if case .data(let d) = $0 { return d.count > 1 && d[1] == 1 }
        return false
      }
    }
    media.append(.data(frame.encoded))
    drain()
  }
  func discardMedia() { media.removeAll() }
  private func drain() {
    guard sender == nil, let ws = socket else { return }
    sender = Task { [weak self, weak ws] in
      guard let self, let ws else { return }
      while !Task.isCancelled && self.socket === ws {
        guard !self.controls.isEmpty || !self.media.isEmpty else { break }
        let message =
          !self.controls.isEmpty ? self.controls.removeFirst() : self.media.removeFirst()
        do { try await ws.send(message) } catch {
          ws.cancel(with: .goingAway, reason: nil)
          break
        }
      }
      if self.socket === ws { self.sender = nil }
    }
  }
}
