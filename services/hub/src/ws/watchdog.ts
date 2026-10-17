import { HEARTBEAT_INTERVAL_MS, LIVENESS_TIMEOUT_MS } from "@kids-monitor/protocol";
import { EventRepo } from "../db/events.js";
import { MonitorLiveness } from "../db/monitorLiveness.js";
import { NoiseRepo } from "../db/noise.js";
import type { HubDeps } from "../deps.js";
import type { Registry } from "./registry.js";
import { fanOutEvent } from "./server.js";

const DAY_MS = 86_400_000;

export class Watchdog {
  private timer: NodeJS.Timeout | null = null;

  constructor(
    private readonly deps: HubDeps,
    private readonly registry: Registry,
    private readonly retentionDays: number,
  ) {}

  sweep(nowMs: number): void {
    const events = new EventRepo(this.deps.db);

    // Every client heartbeats well inside the window, so a connection silent
    // past it belongs to a phone that left wifi or died without a close frame.
    // terminate(), not close(): nobody is there to finish a close handshake.
    // The socket's close handler then releases what it held - for a parent,
    // its place as a viewer, which is what turns a monitor's camera off.
    for (const connection of this.registry.all()) {
      if (nowMs - connection.lastSeenMs > LIVENESS_TIMEOUT_MS) connection.socket.terminate();
    }

    for (const monitor of this.deps.devices.listByRole("monitor")) {
      if (monitor.lastSeenMs === null) continue;

      const silent = nowMs - monitor.lastSeenMs > LIVENESS_TIMEOUT_MS;
      if (!silent) continue;
      const lost = new MonitorLiveness(this.deps.db).lost(monitor.id, monitor.lastSeenMs, nowMs);
      if (lost !== null) fanOutEvent(this.registry, lost);
    }

    events.prune(nowMs - this.retentionDays * DAY_MS);
    new NoiseRepo(this.deps.db).prune(nowMs - 2 * DAY_MS);
  }

  start(): void {
    if (this.timer !== null) return;
    this.timer = setInterval(() => this.sweep(Date.now()), HEARTBEAT_INTERVAL_MS);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
  }
}
