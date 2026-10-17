import Foundation
import MonitorCore
import SQLite3

/// Disk history is partitioned by paired parent; live inserts never advance the recovery cursor.
final class ParentStore {
  private var db: OpaquePointer?
  private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
  init(deviceId: String, directory: URL? = nil) throws {
    let root =
      directory
      ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    let url = root.appendingPathComponent("parent-\(deviceId).sqlite")
    guard sqlite3_open(url.path, &db) == SQLITE_OK else {
      throw MonitorFailure("Couldn't open saved history")
    }
    try exec(
      "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, monitor TEXT NOT NULL, type TEXT NOT NULL, ts INTEGER NOT NULL, payload BLOB NOT NULL); CREATE INDEX IF NOT EXISTS events_filter ON events(monitor,type,ts DESC,id DESC); CREATE TABLE IF NOT EXISTS cursor(id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL); INSERT OR IGNORE INTO cursor VALUES(1,0); CREATE TABLE IF NOT EXISTS noise(monitor TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(monitor,start)); CREATE TABLE IF NOT EXISTS monitors(id INTEGER PRIMARY KEY CHECK(id=1),payload BLOB NOT NULL)"
    )
    try FileManager.default.setAttributes(
      [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
      ofItemAtPath: url.path)
  }
  deinit { sqlite3_close(db) }
  private func exec(_ sql: String) throws {
    guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else {
      throw MonitorFailure("Couldn't update saved history")
    }
  }
  private func statement(_ sql: String) throws -> OpaquePointer {
    var stmt: OpaquePointer?
    guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
      throw MonitorFailure("Couldn't read saved history")
    }
    return stmt
  }
  private func bind(_ value: String, _ index: Int32, _ stmt: OpaquePointer) {
    sqlite3_bind_text(stmt, index, value, -1, transient)
  }
  private func blob<T: Encodable>(_ value: T, _ index: Int32, _ stmt: OpaquePointer) throws {
    let data = try JSONEncoder().encode(value)
    _ = data.withUnsafeBytes {
      sqlite3_bind_blob(stmt, index, $0.baseAddress, Int32(data.count), transient)
    }
  }
  private func decode<T: Decodable>(_ type: T.Type, _ stmt: OpaquePointer, _ col: Int32 = 0) throws
    -> T
  {
    guard let bytes = sqlite3_column_blob(stmt, col) else {
      throw MonitorFailure("Saved history is incomplete")
    }
    return try JSONDecoder().decode(
      type, from: Data(bytes: bytes, count: Int(sqlite3_column_bytes(stmt, col))))
  }
  var recoveryCursor: Int64 {
    get throws {
      let s = try statement("SELECT value FROM cursor WHERE id=1")
      defer { sqlite3_finalize(s) }
      guard sqlite3_step(s) == SQLITE_ROW else {
        throw MonitorFailure("Couldn't read recovery position")
      }
      return sqlite3_column_int64(s, 0)
    }
  }
  func insert(_ events: [ParentEvent], recovery: Int64? = nil) throws {
    try exec("BEGIN IMMEDIATE")
    do {
      let s = try statement("INSERT OR REPLACE INTO events VALUES(?,?,?,?,?)")
      defer { sqlite3_finalize(s) }
      for e in events {
        sqlite3_reset(s)
        sqlite3_clear_bindings(s)
        sqlite3_bind_int64(s, 1, e.id)
        bind(e.monitorId, 2, s)
        bind(e.type, 3, s)
        sqlite3_bind_int64(s, 4, e.tsMs)
        try blob(e, 5, s)
        guard sqlite3_step(s) == SQLITE_DONE else { throw MonitorFailure("Couldn't save an event") }
      }
      if let recovery { try exec("UPDATE cursor SET value=MAX(value,\(recovery)) WHERE id=1") }
      try exec("COMMIT")
    } catch {
      try? exec("ROLLBACK")
      throw error
    }
  }
  func events(monitor: String?, category: String?, offset: Int = 0, limit: Int = 50) throws
    -> [ParentEvent]
  {
    var sql = "SELECT payload FROM events WHERE 1=1"
    if monitor != nil { sql += " AND monitor=?" }
    let types: [String]
    switch category {
    case "Noise": types = ["LOUD_NOISE"]
    case "Movement": types = ["MOTION"]
    case "Monitoring":
      types = ["MONITOR_STARTED", "MONITOR_STOPPED", "MONITOR_LOST", "MONITOR_ONLINE"]
    default: types = []
    }
    if !types.isEmpty { sql += " AND type IN (\(types.map { _ in "?" }.joined(separator: ",")))" }
    sql += " ORDER BY ts DESC,id DESC LIMIT ? OFFSET ?"
    let s = try statement(sql)
    defer { sqlite3_finalize(s) }
    var i: Int32 = 1
    if let monitor {
      bind(monitor, i, s)
      i += 1
    }
    for type in types {
      bind(type, i, s)
      i += 1
    }
    sqlite3_bind_int(s, i, Int32(limit))
    sqlite3_bind_int(s, i + 1, Int32(offset))
    var result: [ParentEvent] = []
    while sqlite3_step(s) == SQLITE_ROW { result.append(try decode(ParentEvent.self, s)) }
    return result
  }
  func saveMonitors(_ monitors: [ParentMonitor]) throws {
    let s = try statement("INSERT OR REPLACE INTO monitors VALUES(1,?)")
    defer { sqlite3_finalize(s) }
    try blob(monitors, 1, s)
    guard sqlite3_step(s) == SQLITE_DONE else { throw MonitorFailure("Couldn't save device names") }
  }
  func monitors() throws -> [ParentMonitor] {
    let s = try statement("SELECT payload FROM monitors WHERE id=1")
    defer { sqlite3_finalize(s) }
    return sqlite3_step(s) == SQLITE_ROW ? try decode([ParentMonitor].self, s) : []
  }
  func saveNoise(_ samples: [ParentNoiseSample], monitor: String, now: Int64) throws {
    try exec("BEGIN IMMEDIATE")
    do {
      let s = try statement("INSERT OR REPLACE INTO noise VALUES(?,?,?,?)")
      defer { sqlite3_finalize(s) }
      for n in samples where n.valid {
        sqlite3_reset(s)
        bind(monitor, 1, s)
        sqlite3_bind_int64(s, 2, n.startMs)
        sqlite3_bind_int64(s, 3, n.endMs)
        try blob(n, 4, s)
        guard sqlite3_step(s) == SQLITE_DONE else {
          throw MonitorFailure("Couldn't save noise history")
        }
      }
      try exec("DELETE FROM noise WHERE end < \(now - 172_800_000)")
      try exec("COMMIT")
    } catch {
      try? exec("ROLLBACK")
      throw error
    }
  }
  func noise(monitor: String, from: Int64) throws -> [ParentNoiseSample] {
    let s = try statement("SELECT payload FROM noise WHERE monitor=? AND end>? ORDER BY start")
    defer { sqlite3_finalize(s) }
    bind(monitor, 1, s)
    sqlite3_bind_int64(s, 2, from)
    var result: [ParentNoiseSample] = []
    while sqlite3_step(s) == SQLITE_ROW { result.append(try decode(ParentNoiseSample.self, s)) }
    return result
  }
}
