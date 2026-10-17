import type { Database } from "better-sqlite3";
import type { NoiseSample } from "@kids-monitor/protocol";

export class NoiseRepo {
  constructor(private readonly db: Database) {}

  append(monitorId: string, sample: NoiseSample): void {
    this.db.prepare(`INSERT OR REPLACE INTO noise_samples
      (monitor_id, start_ms, end_ms, level_db, peak_db, threshold_db) VALUES (?,?,?,?,?,?)`)
      .run(monitorId, sample.startMs, sample.endMs, sample.levelDb, sample.peakDb, sample.thresholdDb);
  }

  between(monitorId: string, fromMs: number, toMs: number): NoiseSample[] {
    return this.db.prepare(`SELECT start_ms AS startMs, end_ms AS endMs,
      level_db AS levelDb, peak_db AS peakDb, threshold_db AS thresholdDb
      FROM noise_samples WHERE monitor_id = ? AND end_ms > ? AND start_ms < ?
      ORDER BY start_ms`).all(monitorId, fromMs, toMs) as NoiseSample[];
  }

  prune(beforeMs: number): void {
    this.db.prepare("DELETE FROM noise_samples WHERE end_ms < ?").run(beforeMs);
  }
}
