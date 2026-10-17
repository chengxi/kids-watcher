import type { Database } from "better-sqlite3";
import { EventMetadataSchema, type EventMetadata, type EventType } from "@kids-monitor/protocol";

export interface EventRow {
  readonly id: number;
  readonly monitorId: string;
  readonly type: EventType;
  readonly tsMs: number;
  readonly metadata: EventMetadata;
}

interface RawEvent {
  id: number;
  monitor_id: string;
  type: EventType;
  ts_ms: number;
  metadata_json: string;
}

function toRow(raw: RawEvent): EventRow {
  return {
    id: raw.id,
    monitorId: raw.monitor_id,
    type: raw.type,
    tsMs: raw.ts_ms,
    metadata: EventMetadataSchema.parse(JSON.parse(raw.metadata_json)),
  };
}

export class EventRepo {
  constructor(private readonly db: Database) {}

  append(monitorId: string, type: EventType, tsMs: number, metadata: EventMetadata): EventRow {
    const info = this.db
      .prepare("INSERT INTO events (monitor_id, type, ts_ms, metadata_json) VALUES (?,?,?,?)")
      .run(monitorId, type, tsMs, JSON.stringify(metadata));
    const raw = this.db
      .prepare("SELECT * FROM events WHERE id = ?")
      .get(Number(info.lastInsertRowid)) as RawEvent;
    return toRow(raw);
  }

  since(cursor: number, limit: number): EventRow[] {
    const raws = this.db
      .prepare("SELECT * FROM events WHERE id > ? ORDER BY id LIMIT ?")
      .all(cursor, limit) as RawEvent[];
    return raws.map(toRow);
  }

  inWindow(monitorId: string, fromMs: number, toMs: number, limit = 5001): EventRow[] {
    return (this.db.prepare(`SELECT * FROM events WHERE monitor_id = ? AND ts_ms >= ?
      AND ts_ms <= ? ORDER BY ts_ms DESC, id DESC LIMIT ?`)
      .all(monitorId, fromMs, toMs, limit) as RawEvent[]).map(toRow);
  }

  ack(eventId: number, deviceId: string, atMs: number): void {
    this.db
      .prepare("INSERT OR IGNORE INTO event_acks (event_id, device_id, acked_at_ms) VALUES (?,?,?)")
      .run(eventId, deviceId, atMs);
  }

  unackedCount(deviceId: string): number {
    const row = this.db
      .prepare(
        `SELECT COUNT(*) AS n FROM events e
         WHERE NOT EXISTS (SELECT 1 FROM event_acks a WHERE a.event_id = e.id AND a.device_id = ?)`,
      )
      .get(deviceId) as { n: number };
    return row.n;
  }

  // Acks go first: event_acks.event_id is a foreign key into events.
  prune(olderThanMs: number): number {
    this.db
      .prepare(
        "DELETE FROM event_acks WHERE event_id IN (SELECT id FROM events WHERE ts_ms < ?)",
      )
      .run(olderThanMs);
    return this.db.prepare("DELETE FROM events WHERE ts_ms < ?").run(olderThanMs).changes;
  }
}
