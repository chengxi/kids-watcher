import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { DEFAULT_MONITOR_CONFIG } from "@kids-monitor/protocol";
import { mintToken } from "../src/auth.js";
import { MonitorConfigRepo } from "../src/db/monitorConfig.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;
let monitorToken: string;
let parentToken: string;
let monitorId: string;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;

beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-cfg-"))), 0);
  monitorToken = mintToken();
  parentToken = mintToken();
  monitorId = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  hub.deps.devices.create("parent", "Mum", parentToken);
});
afterEach(async () => hub.close());

function open(token: string, role: string): Promise<WebSocket> {
  const ws = new WebSocket(`${hub.wsUrl}/ws?role=${role}`, {
    rejectUnauthorized: false,
    headers: { Authorization: `Bearer ${token}` },
  });
  return new Promise((resolve, reject) => {
    ws.once("open", () => resolve(ws));
    ws.once("error", reject);
  });
}

function waitFor(ws: WebSocket, t: string): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    const onMessage = (data: Buffer, isBinary: boolean) => {
      if (isBinary) return;
      const msg = JSON.parse(data.toString("utf8")) as Record<string, unknown>;
      if (msg["t"] === t) { ws.off("message", onMessage); resolve(msg); }
    };
    ws.on("message", onMessage);
  });
}

describe("MonitorConfigRepo", () => {
  it("returns the spec defaults for a monitor never configured", () => {
    expect(new MonitorConfigRepo(hub.deps.db).get(monitorId)).toEqual(DEFAULT_MONITOR_CONFIG);
  });

  it("persists an updated config", () => {
    const repo = new MonitorConfigRepo(hub.deps.db);
    repo.set(monitorId, { ...DEFAULT_MONITOR_CONFIG, thresholdDb: 20 });
    expect(repo.get(monitorId).thresholdDb).toBe(20);
  });

  it("records a calibrated noise floor with its timestamp", () => {
    const repo = new MonitorConfigRepo(hub.deps.db);
    repo.recordCalibration(monitorId, -62.5, 1757000000000);
    expect(repo.get(monitorId).noiseFloorDb).toBe(-62.5);
    expect(repo.calibratedAt(monitorId)).toBe(1757000000000);
  });
});

describe("config over the wire", () => {
  it("pushes the current config as soon as a monitor connects", async () => {
    new MonitorConfigRepo(hub.deps.db).set(monitorId, { ...DEFAULT_MONITOR_CONFIG, thresholdDb: 18 });
    const monitor = await open(monitorToken, "monitor");
    const msg = await waitFor(monitor, "config");
    expect((msg["config"] as { thresholdDb: number }).thresholdDb).toBe(18);
    monitor.close();
  });

  it("pushes a new config to a connected monitor when the parent updates it", async () => {
    const monitor = await open(monitorToken, "monitor");
    await waitFor(monitor, "config");
    const pushed = waitFor(monitor, "config");
    const res = await fetch(`${hub.httpsUrl}/monitors/${monitorId}/config`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${parentToken}`, "content-type": "application/json" },
      body: JSON.stringify({ ...DEFAULT_MONITOR_CONFIG, thresholdDb: 25 }),
    });
    expect(res.status).toBe(200);
    expect(((await pushed)["config"] as { thresholdDb: number }).thresholdDb).toBe(25);
    monitor.close();
  });

  it("rejects a threshold outside the allowed range", async () => {
    const res = await fetch(`${hub.httpsUrl}/monitors/${monitorId}/config`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${parentToken}`, "content-type": "application/json" },
      body: JSON.stringify({ ...DEFAULT_MONITOR_CONFIG, thresholdDb: 900 }),
    });
    expect(res.status).toBe(400);
  });

  it("sends recalibrate to the monitor and stores the result it reports", async () => {
    const monitor = await open(monitorToken, "monitor");
    await waitFor(monitor, "config");
    const asked = waitFor(monitor, "recalibrate");
    const res = await fetch(`${hub.httpsUrl}/monitors/${monitorId}/recalibrate`, {
      method: "POST",
      headers: { Authorization: `Bearer ${parentToken}` },
    });
    expect(res.status).toBe(202);
    expect((await asked)["windowMs"]).toBe(DEFAULT_MONITOR_CONFIG.calibrationWindowMs);

    monitor.send(JSON.stringify({
      t: "calibration_result", noiseFloorDb: -58, spreadDb: 4, sampledMs: 30000,
    }));
    await new Promise((r) => setTimeout(r, 100));
    expect(new MonitorConfigRepo(hub.deps.db).get(monitorId).noiseFloorDb).toBe(-58);
    monitor.close();
  });

  it("returns 409 when recalibrating an offline monitor", async () => {
    const res = await fetch(`${hub.httpsUrl}/monitors/${monitorId}/recalibrate`, {
      method: "POST",
      headers: { Authorization: `Bearer ${parentToken}` },
    });
    expect(res.status).toBe(409);
  });
});
