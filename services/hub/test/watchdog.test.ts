import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LIVENESS_TIMEOUT_MS } from "@kids-monitor/protocol";
import { mintToken } from "../src/auth.js";
import { EventRepo } from "../src/db/events.js";
import type { HubDeps } from "../src/deps.js";
import { createDeps } from "../src/server.js";
import { Registry, type Connection } from "../src/ws/registry.js";
import { Watchdog } from "../src/ws/watchdog.js";

let deps: HubDeps;
let registry: Registry;
let watchdog: Watchdog;
let monitorId: string;

beforeEach(() => {
  deps = createDeps(mkdtempSync(join(tmpdir(), "hub-wd-")));
  registry = new Registry();
  watchdog = new Watchdog(deps, registry, 30);
  monitorId = deps.devices.create("monitor", "Nursery", mintToken()).id;
});

const events = () => new EventRepo(deps.db).since(0, 100);

describe("Watchdog", () => {
  it("says nothing about a monitor that has never connected", () => {
    watchdog.sweep(1_000_000);
    expect(events()).toHaveLength(0);
  });

  it("says nothing while the monitor is inside the liveness window", () => {
    deps.devices.touch(monitorId, 1_000_000);
    watchdog.sweep(1_000_000 + LIVENESS_TIMEOUT_MS - 1);
    expect(events()).toHaveLength(0);
  });

  it("emits MONITOR_LOST once the liveness window elapses", () => {
    deps.devices.touch(monitorId, 1_000_000);
    watchdog.sweep(1_000_000 + LIVENESS_TIMEOUT_MS + 1);
    expect(events().map((e) => e.type)).toEqual(["MONITOR_LOST"]);
  });

  it("does not repeat MONITOR_LOST on subsequent sweeps", () => {
    deps.devices.touch(monitorId, 1_000_000);
    watchdog.sweep(1_000_000 + LIVENESS_TIMEOUT_MS + 1);
    watchdog.sweep(1_000_000 + LIVENESS_TIMEOUT_MS + 20_000);
    expect(events()).toHaveLength(1);
  });

  it("arms again after the monitor comes back", () => {
    deps.devices.touch(monitorId, 1_000_000);
    watchdog.sweep(1_000_000 + LIVENESS_TIMEOUT_MS + 1);
    deps.devices.touch(monitorId, 2_000_000);
    watchdog.sweep(2_000_000 + LIVENESS_TIMEOUT_MS + 1);
    expect(events()).toHaveLength(2);
  });

  it("ignores parent devices", () => {
    deps.devices.create("parent", "Mum", mintToken());
    watchdog.sweep(9_999_999);
    expect(events()).toHaveLength(0);
  });

  it("prunes events older than the retention window", () => {
    const nowMs = 100 * 86_400_000;
    const repo = new EventRepo(deps.db);
    repo.append(monitorId, "LOUD_NOISE", nowMs - 31 * 86_400_000, {});
    repo.append(monitorId, "LOUD_NOISE", nowMs - 1 * 86_400_000, {});
    deps.devices.touch(monitorId, nowMs);
    watchdog.sweep(nowMs);
    expect(repo.since(0, 100)).toHaveLength(1);
  });

  it("prunes the acks belonging to pruned events", () => {
    const nowMs = 100 * 86_400_000;
    const repo = new EventRepo(deps.db);
    const parent = deps.devices.create("parent", "Mum", mintToken());
    const old = repo.append(monitorId, "LOUD_NOISE", nowMs - 40 * 86_400_000, {});
    repo.ack(old.id, parent.id, nowMs);
    deps.devices.touch(monitorId, nowMs);
    watchdog.sweep(nowMs);
    const acks = deps.db.prepare("SELECT COUNT(*) AS n FROM event_acks").get() as { n: number };
    expect(acks.n).toBe(0);
  });

  // A peer that vanished never completes a close handshake, so the hub ends
  // the connection itself. Its close handler then releases whatever it held
  // (a parent's viewing, a monitor's stream).
  describe("silent connections", () => {
    const connect = (deviceId: string, role: "monitor" | "parent", lastSeenMs: number) => {
      const terminate = vi.fn();
      const connection: Connection = {
        deviceId,
        role,
        socket: { terminate, close: vi.fn() } as unknown as Connection["socket"],
        lastSeenMs,
        batteryPct: null,
        subscribedMonitors: null,
      };
      registry.add(connection);
      return terminate;
    };

    it("terminates a connection of either role silent past the liveness window", () => {
      const now = 5_000_000;
      const parent = connect("p1", "parent", now - LIVENESS_TIMEOUT_MS - 1);
      const monitor = connect("m1", "monitor", now - LIVENESS_TIMEOUT_MS - 1);
      watchdog.sweep(now);
      expect(parent).toHaveBeenCalledTimes(1);
      expect(monitor).toHaveBeenCalledTimes(1);
    });

    it("leaves a connection inside the liveness window alone", () => {
      const now = 5_000_000;
      const parent = connect("p1", "parent", now - LIVENESS_TIMEOUT_MS);
      watchdog.sweep(now);
      expect(parent).not.toHaveBeenCalled();
    });
  });
});
