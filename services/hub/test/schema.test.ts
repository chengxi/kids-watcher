import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";
import { openDb } from "../src/db/open.js";
import Database from "better-sqlite3";
import { MIGRATIONS, migrate } from "../src/db/schema.js";

describe("loadConfig", () => {
  it("uses documented defaults when the environment is empty", () => {
    expect(loadConfig({})).toEqual({
      port: 8443, dataDir: "./hub-data", logLevel: "info", eventRetentionDays: 30,
    });
  });

  it("reads overrides from the environment", () => {
    const cfg = loadConfig({
      HUB_PORT: "9000", HUB_DATA_DIR: "/data", HUB_LOG_LEVEL: "debug",
      HUB_EVENT_RETENTION_DAYS: "7",
    });
    expect(cfg).toEqual({ port: 9000, dataDir: "/data", logLevel: "debug", eventRetentionDays: 7 });
  });

  it("rejects a port that is not a number", () => {
    expect(() => loadConfig({ HUB_PORT: "not-a-port" })).toThrow(/HUB_PORT/);
  });
});

describe("migrations", () => {
  it("creates every table", () => {
    const db = openDb(":memory:");
    const names = db
      .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
      .all()
      .map((r) => (r as { name: string }).name);
    expect(names).toContain("devices");
    expect(names).toContain("events");
    expect(names).toContain("event_acks");
    expect(names).toContain("pairing_codes");
    expect(names).toContain("monitor_config");
    expect(names).toContain("noise_samples");
  });

  it("is idempotent when run twice", () => {
    const db = openDb(":memory:");
    migrate(db);
    const version = db.prepare("SELECT version FROM schema_version").get() as { version: number };
    expect(version.version).toBe(MIGRATIONS.length);
  });

  it("upgrades an existing hub without losing devices or alert history", () => {
    const db = new Database(":memory:");
    db.exec(MIGRATIONS[0]!);
    db.exec("CREATE TABLE schema_version (version INTEGER NOT NULL); INSERT INTO schema_version VALUES (1)");
    db.prepare("INSERT INTO devices (id, role, name, token_hash, created_at_ms) VALUES (?,?,?,?,?)")
      .run("m1", "monitor", "Nursery", "hash", 1);
    db.prepare("INSERT INTO events (monitor_id, type, ts_ms) VALUES (?,?,?)").run("m1", "LOUD_NOISE", 2);
    migrate(db);
    expect(db.prepare("SELECT COUNT(*) AS n FROM devices").get()).toEqual({ n: 1 });
    expect(db.prepare("SELECT COUNT(*) AS n FROM events").get()).toEqual({ n: 1 });
    expect(db.prepare("SELECT COUNT(*) AS n FROM noise_samples").get()).toEqual({ n: 0 });
    db.close();
  });

  it("gives events an auto-incrementing integer id usable as a cursor", () => {
    const db = openDb(":memory:");
    db.prepare("INSERT INTO devices (id, role, name, token_hash, created_at_ms) VALUES (?,?,?,?,?)")
      .run("m1", "monitor", "Nursery", "hash", 1);
    const insert = db.prepare("INSERT INTO events (monitor_id, type, ts_ms, metadata_json) VALUES (?,?,?,?)");
    const first = insert.run("m1", "LOUD_NOISE", 10, "{}").lastInsertRowid;
    const second = insert.run("m1", "LOUD_NOISE", 20, "{}").lastInsertRowid;
    expect(Number(second)).toBe(Number(first) + 1);
  });

  it("refuses an event for an unknown monitor", () => {
    const db = openDb(":memory:");
    expect(() =>
      db.prepare("INSERT INTO events (monitor_id, type, ts_ms, metadata_json) VALUES (?,?,?,?)")
        .run("ghost", "LOUD_NOISE", 10, "{}"),
    ).toThrow(/FOREIGN KEY/);
  });
});
