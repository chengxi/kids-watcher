import Foundation

public struct MonitorFailure: LocalizedError {
  public let message: String
  public init(_ message: String) { self.message = message }
  public var errorDescription: String? { message }
}

public struct PairingPayload: Codable {
  public let v: Int
  public let host: String
  public let port: Int
  public let fingerprint: String
  public let code: String
  public let role: String
  public var baseURL: URL { URL(string: "https://\(host):\(port)")! }
  public static func parse(_ raw: String, expectedRole: String = "monitor") throws -> Self {
    guard let data = raw.data(using: .utf8),
      let payload = try? JSONDecoder().decode(Self.self, from: data)
    else {
      throw MonitorFailure("This QR code is not a Kids Monitor pairing code")
    }
    guard payload.v == 1 else { throw MonitorFailure("Pairing code version is unsupported") }
    try validateAddress(payload.host, payload.port)
    _ = try normalizeFingerprint(payload.fingerprint)
    guard payload.code.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else {
      throw MonitorFailure("The code is six digits")
    }
    guard payload.role == expectedRole else {
      throw MonitorFailure("This app needs a \(expectedRole) pairing code")
    }
    return payload
  }
}

public func validateAddress(_ host: String, _ port: Int) throws {
  guard
    host.range(of: "^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$", options: .regularExpression)
      != nil,
    (1...65535).contains(port)
  else { throw MonitorFailure("Enter a hub address and port, like 192.168.1.100 and 8444") }
}
public func normalizeFingerprint(_ value: String) throws -> String {
  let normalized = value.replacingOccurrences(of: ":", with: "").lowercased()
  guard normalized.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
    throw MonitorFailure("Invalid certificate fingerprint")
  }
  return normalized
}
public func monitorNameError(_ value: String) -> String? {
  let name = value.trimmingCharacters(in: .whitespacesAndNewlines)
  if name.isEmpty { return "Enter a monitor name" }
  // Android and the hub count UTF-16 code units.
  return name.utf16.count > 64 ? "Use 64 characters or fewer" : nil
}
public struct Credentials: Codable {
  public let host: String, fingerprint: String, deviceId: String, token: String, name: String
  public let port: Int
  public init(
    host: String, port: Int, fingerprint: String, deviceId: String, token: String, name: String
  ) {
    self.host = host
    self.port = port
    self.fingerprint = fingerprint
    self.deviceId = deviceId
    self.token = token
    self.name = name
  }
  public var baseURL: URL { URL(string: "https://\(host):\(port)")! }
  public var socketURL: URL { URL(string: "wss://\(host):\(port)/ws?role=monitor")! }
}
public struct MonitorConfig: Codable {
  public var thresholdDb: Double = 12
  public var sustainMs: Int = 1500
  public var cooldownS: Int = 60
  public var noiseFloorDb: Double = -50
  public var motionEnabled: Bool = false
  public var motionThreshold: Double = 12
  public var calibrationWindowMs: Int = 30000
  public init() {}
  public var isValid: Bool {
    (0...60).contains(thresholdDb) && (100...30000).contains(sustainMs)
      && (0...3600).contains(cooldownS)
      && (-120...0).contains(noiseFloorDb) && (0...255).contains(motionThreshold)
      && (5000...120000).contains(calibrationWindowMs)
  }
}
public struct MediaFrame: Equatable {
  public let kind: UInt8, id: UInt16, timestampUs: UInt64, payload: Data
  public init(kind: UInt8, id: UInt16, timestampUs: UInt64, payload: Data) {
    self.kind = kind
    self.id = id
    self.timestampUs = timestampUs
    self.payload = payload
  }
  public var encoded: Data {
    var data = Data([0x4b, kind, UInt8(id >> 8), UInt8(id & 255)])
    for shift in stride(from: 56, through: 0, by: -8) {
      data.append(UInt8((timestampUs >> shift) & 255))
    }
    data.append(payload)
    return data
  }
  public static func decode(_ data: Data) throws -> Self {
    let bytes = [UInt8](data)
    guard bytes.count >= 12, bytes[0] == 0x4b, [1, 2].contains(bytes[1]), bytes[4] < 128 else {
      throw MonitorFailure("Invalid media frame")
    }
    let timestamp = bytes[4..<12].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    return Self(
      kind: bytes[1], id: UInt16(bytes[2]) << 8 | UInt16(bytes[3]), timestampUs: timestamp,
      payload: Data(bytes.dropFirst(12)))
  }
}
public func pcmData(_ samples: [Int16]) -> Data {
  var result = Data(capacity: samples.count * 2)
  for sample in samples {
    let v = UInt16(bitPattern: sample)
    result.append(UInt8(v & 255))
    result.append(UInt8(v >> 8))
  }
  return result
}
