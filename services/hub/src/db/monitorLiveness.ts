import type { Database } from "better-sqlite3";
import { EventRepo, type EventRow } from "./events.js";

/** Durable loss markers prevent duplicate loss/recovery events across hub restarts. */
export class MonitorLiveness {
  constructor(private readonly db: Database) {}

  lost(monitorId: string, lastSeenMs: number, atMs: number): EventRow | null {
    return this.db.transaction(() => {
      const previous = this.db.prepare("SELECT reported_lost_seen_ms FROM monitor_liveness WHERE monitor_id=?")
        .get(monitorId) as { reported_lost_seen_ms: number } | undefined;
      if (previous?.reported_lost_seen_ms === lastSeenMs) return null;
      this.db.prepare(`INSERT INTO monitor_liveness(monitor_id,reported_lost_seen_ms) VALUES(?,?)
        ON CONFLICT(monitor_id) DO UPDATE SET reported_lost_seen_ms=excluded.reported_lost_seen_ms`).run(monitorId, lastSeenMs);
      return new EventRepo(this.db).append(monitorId, "MONITOR_LOST", atMs, {});
    })();
  }

  recovered(monitorId: string, atMs: number): EventRow | null {
    return this.db.transaction(() => {
      const removed = this.db.prepare("DELETE FROM monitor_liveness WHERE monitor_id=?").run(monitorId).changes;
      return removed === 0 ? null : new EventRepo(this.db).append(monitorId, "MONITOR_ONLINE", atMs, {});
    })();
  }
}
