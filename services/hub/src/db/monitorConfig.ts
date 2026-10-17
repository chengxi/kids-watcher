import type { Database } from "better-sqlite3";
import {
  DEFAULT_MONITOR_CONFIG,
  MonitorConfigSchema,
  type MonitorConfig,
} from "@kids-monitor/protocol";

export class MonitorConfigRepo {
  constructor(private readonly db: Database) {}

  get(monitorId: string): MonitorConfig {
    const row = this.db
      .prepare("SELECT config_json FROM monitor_config WHERE monitor_id = ?")
      .get(monitorId) as { config_json: string } | undefined;
    if (row === undefined) return DEFAULT_MONITOR_CONFIG;
    return MonitorConfigSchema.parse(JSON.parse(row.config_json));
  }

  set(monitorId: string, config: MonitorConfig): void {
    this.db
      .prepare(
        `INSERT INTO monitor_config (monitor_id, config_json) VALUES (?, ?)
         ON CONFLICT(monitor_id) DO UPDATE SET config_json = excluded.config_json`,
      )
      .run(monitorId, JSON.stringify(MonitorConfigSchema.parse(config)));
  }

  recordCalibration(monitorId: string, noiseFloorDb: number, atMs: number): void {
    this.set(monitorId, { ...this.get(monitorId), noiseFloorDb });
    this.db
      .prepare("UPDATE monitor_config SET calibrated_at_ms = ? WHERE monitor_id = ?")
      .run(atMs, monitorId);
  }

  calibratedAt(monitorId: string): number | null {
    const row = this.db
      .prepare("SELECT calibrated_at_ms FROM monitor_config WHERE monitor_id = ?")
      .get(monitorId) as { calibrated_at_ms: number | null } | undefined;
    return row?.calibrated_at_ms ?? null;
  }
}
