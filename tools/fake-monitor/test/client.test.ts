import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { DEFAULT_MONITOR_CONFIG } from "@kids-monitor/protocol";
import { mintToken } from "../../../services/hub/src/auth.js";
import { EventRepo } from "../../../services/hub/src/db/events.js";
import { MonitorConfigRepo } from "../../../services/hub/src/db/monitorConfig.js";
import { createDeps, startHub, type Hub } from "../../../services/hub/src/server.js";
import { FakeMonitor, pairDevice } from "../src/client.js";

let hub: Hub;
let token: string;
let monitorId: string;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;

beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "fake-mon-"))), 0);
  token = mintToken();
  monitorId = hub.deps.devices.create("monitor", "Fake Nursery", token).id;
});
afterEach(async () => hub.close());

function newParentToken(): string {
  const parentToken = mintToken();
  hub.deps.devices.create("parent", "Test Parent", parentToken);
  return parentToken;
}

describe("pairDevice", () => {
  it("exchanges a code for a token", async () => {
    const code = hub.deps.pairing.issue("monitor", Date.now());
    const result = await pairDevice(hub.httpsUrl, code, "Fake Nursery");
    expect(hub.deps.devices.findByToken(result.token)?.id).toBe(result.deviceId);
  });

  it("throws a readable error on a bad code", async () => {
    await expect(pairDevice(hub.httpsUrl, "000000", "Fake")).rejects.toThrow(/invalid_code/);
  });
});

describe("FakeMonitor", () => {
  it("receives the pushed config on connect", async () => {
    new MonitorConfigRepo(hub.deps.db).set(monitorId, { ...DEFAULT_MONITOR_CONFIG, thresholdDb: 30 });
    const monitor = new FakeMonitor({ wsUrl: hub.wsUrl, token, name: "Fake Nursery" });
    await monitor.connect();
    await new Promise((r) => setTimeout(r, 100));
    expect(monitor.config.thresholdDb).toBe(30);
    monitor.close();
  });

  it("emits an event the hub persists", async () => {
    const monitor = new FakeMonitor({ wsUrl: hub.wsUrl, token, name: "Fake Nursery" });
    await monitor.connect();
    monitor.emitEvent("LOUD_NOISE", { peakDb: -11, durationMs: 1700 });
    await new Promise((r) => setTimeout(r, 100));
    const stored = new EventRepo(hub.deps.db).since(0, 10);
    expect(stored).toHaveLength(1);
    expect(stored[0]?.metadata.peakDb).toBe(-11);
    monitor.close();
  });

  it("answers a recalibrate request with a calibration result", async () => {
    const monitor = new FakeMonitor({ wsUrl: hub.wsUrl, token, name: "Fake Nursery" });
    await monitor.connect();
    await new Promise((r) => setTimeout(r, 100));

    const res = await fetch(`${hub.httpsUrl}/monitors/${monitorId}/recalibrate`, {
      method: "POST",
      headers: { Authorization: `Bearer ${newParentToken()}` },
    });
    expect(res.status).toBe(202);
    await new Promise((r) => setTimeout(r, 200));
    expect(new MonitorConfigRepo(hub.deps.db).calibratedAt(monitorId)).not.toBeNull();
    monitor.close();
  });

  it("stays registered as online after connecting", async () => {
    const monitor = new FakeMonitor({ wsUrl: hub.wsUrl, token, name: "Fake Nursery" });
    await monitor.connect();
    await new Promise((r) => setTimeout(r, 100));
    expect(hub.registry.isOnline(monitorId)).toBe(true);
    monitor.close();
  });
});
