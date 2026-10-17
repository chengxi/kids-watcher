import type { Database } from "better-sqlite3";
import { randomUUID } from "node:crypto";
import type { Role } from "@kids-monitor/protocol";
import { hashToken } from "../auth.js";

export interface DeviceRow {
  readonly id: string;
  readonly role: Role;
  readonly name: string;
  readonly createdAtMs: number;
  readonly lastSeenMs: number | null;
  readonly revoked: boolean;
}

interface RawDevice {
  id: string;
  role: Role;
  name: string;
  created_at_ms: number;
  last_seen_ms: number | null;
  revoked: number;
}

function toRow(raw: RawDevice): DeviceRow {
  return {
    id: raw.id,
    role: raw.role,
    name: raw.name,
    createdAtMs: raw.created_at_ms,
    lastSeenMs: raw.last_seen_ms,
    revoked: raw.revoked === 1,
  };
}

export class DeviceRepo {
  constructor(private readonly db: Database) {}

  create(role: Role, name: string, token: string, nowMs: number = Date.now()): DeviceRow {
    const id = randomUUID();
    this.db
      .prepare("INSERT INTO devices (id, role, name, token_hash, created_at_ms) VALUES (?,?,?,?,?)")
      .run(id, role, name, hashToken(token), nowMs);
    const row = this.get(id);
    if (row === null) throw new Error("device vanished immediately after insert");
    return row;
  }

  findByToken(token: string): DeviceRow | null {
    const raw = this.db
      .prepare("SELECT * FROM devices WHERE token_hash = ? AND revoked = 0")
      .get(hashToken(token)) as RawDevice | undefined;
    return raw === undefined ? null : toRow(raw);
  }

  get(id: string): DeviceRow | null {
    const raw = this.db.prepare("SELECT * FROM devices WHERE id = ?").get(id) as RawDevice | undefined;
    return raw === undefined ? null : toRow(raw);
  }

  listByRole(role: Role): DeviceRow[] {
    const raws = this.db
      .prepare("SELECT * FROM devices WHERE role = ? AND revoked = 0 ORDER BY created_at_ms")
      .all(role) as RawDevice[];
    return raws.map(toRow);
  }

  touch(id: string, atMs: number): void {
    this.db.prepare("UPDATE devices SET last_seen_ms = ? WHERE id = ?").run(atMs, id);
  }

  revoke(id: string): void {
    this.db.prepare("UPDATE devices SET revoked = 1 WHERE id = ?").run(id);
  }
}
