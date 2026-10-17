import type { Database } from "better-sqlite3";

export const MIGRATIONS: readonly string[] = [
  `
  CREATE TABLE devices (
    id            TEXT PRIMARY KEY,
    role          TEXT NOT NULL CHECK (role IN ('monitor','parent')),
    name          TEXT NOT NULL,
    token_hash    TEXT NOT NULL UNIQUE,
    created_at_ms INTEGER NOT NULL,
    last_seen_ms  INTEGER,
    revoked       INTEGER NOT NULL DEFAULT 0
  );

  CREATE TABLE events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    monitor_id    TEXT NOT NULL REFERENCES devices(id),
    type          TEXT NOT NULL,
    ts_ms         INTEGER NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}'
  );
  CREATE INDEX events_monitor_idx ON events(monitor_id, id);

  CREATE TABLE event_acks (
    event_id    INTEGER NOT NULL REFERENCES events(id),
    device_id   TEXT NOT NULL REFERENCES devices(id),
    acked_at_ms INTEGER NOT NULL,
    PRIMARY KEY (event_id, device_id)
  );

  CREATE TABLE pairing_codes (
    code_hash     TEXT PRIMARY KEY,
    role          TEXT NOT NULL CHECK (role IN ('monitor','parent')),
    expires_at_ms INTEGER NOT NULL,
    used_at_ms    INTEGER
  );

  CREATE TABLE monitor_config (
    monitor_id       TEXT PRIMARY KEY REFERENCES devices(id),
    config_json      TEXT NOT NULL,
    calibrated_at_ms INTEGER
  );
  `,
  `
  CREATE TABLE noise_samples (
    monitor_id TEXT NOT NULL REFERENCES devices(id),
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL,
    level_db REAL NOT NULL,
    peak_db REAL NOT NULL,
    threshold_db REAL,
    PRIMARY KEY (monitor_id, start_ms)
  );
  CREATE INDEX noise_samples_end_idx ON noise_samples(end_ms);
  `,
  `
  CREATE TABLE monitor_liveness (
    monitor_id TEXT PRIMARY KEY REFERENCES devices(id),
    reported_lost_seen_ms INTEGER NOT NULL
  );
  -- Preserve an already-reported outage when upgrading the running hub.
  INSERT INTO monitor_liveness(monitor_id,reported_lost_seen_ms)
    SELECT d.id,d.last_seen_ms FROM devices d
    WHERE d.role='monitor' AND d.last_seen_ms IS NOT NULL AND d.revoked=0
    AND (SELECT e.ts_ms FROM events e WHERE e.monitor_id=d.id AND e.type='MONITOR_LOST'
         ORDER BY e.id DESC LIMIT 1) >= d.last_seen_ms;
  `,
];

export function migrate(db: Database): void {
  db.exec("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
  const row = db.prepare("SELECT version FROM schema_version").get() as { version: number } | undefined;
  let current = row?.version ?? 0;
  if (row === undefined) db.prepare("INSERT INTO schema_version (version) VALUES (0)").run();

  for (let i = current; i < MIGRATIONS.length; i++) {
    const sql = MIGRATIONS[i];
    if (sql === undefined) throw new Error(`missing migration ${i}`);
    db.transaction(() => {
      db.exec(sql);
      db.prepare("UPDATE schema_version SET version = ?").run(i + 1);
    })();
    current = i + 1;
  }
}
