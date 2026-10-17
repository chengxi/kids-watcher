import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { MonitorToHubSchema, type NoiseSample } from "@kids-monitor/protocol";
import { mintToken } from "../src/auth.js";
import { EventRepo } from "../src/db/events.js";
import { NoiseRepo } from "../src/db/noise.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;
let id: string;
let monitorToken: string;
let parentToken: string;
const day = 86_400_000;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;
beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });
const sample = (startMs: number, thresholdDb: number | null = -38): NoiseSample =>
  ({ startMs, endMs: startMs + 15_000, levelDb: -45, peakDb: -20, thresholdDb });

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-noise-"))), 0);
  monitorToken = mintToken();
  parentToken = mintToken();
  id = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  hub.deps.devices.create("parent", "Parent", parentToken);
});
afterEach(async () => { await hub.close(); hub.deps.db.close(); });

async function history(fromMs?: number) {
  const response = await fetch(`${hub.httpsUrl}/monitors/${id}/noise-history${fromMs === undefined ? "" : `?fromMs=${fromMs}`}`, {
    headers: { Authorization: `Bearer ${parentToken}` },
  });
  return { response, body: await response.json() as { samples: NoiseSample[]; events: { id: number }[]; eventsTruncated: boolean } };
}

describe("noise history", () => {
  it("validates durations and finite digital levels, including calibration without threshold", () => {
    expect(MonitorToHubSchema.safeParse({ t: "noise_level", sample: sample(100, null) }).success).toBe(true);
    for (const invalid of [sample(100, NaN), { ...sample(100), endMs: 100 }, { ...sample(100), endMs: 16000 }, { ...sample(100), peakDb: 5 }]) {
      expect(MonitorToHubSchema.safeParse({ t: "noise_level", sample: invalid }).success).toBe(false);
    }
  });

  it("persists intervals received from the monitor and serves them to its parent", async () => {
    const ws = new WebSocket(`${hub.wsUrl}/ws?role=monitor`, { rejectUnauthorized: false, headers: { Authorization: `Bearer ${monitorToken}` } });
    await new Promise<void>((resolve, reject) => { ws.once("open", resolve); ws.once("error", reject); });
    const acknowledged = new Promise<void>((resolve) => ws.on("message", data => {
      if ((JSON.parse(data.toString()) as { t: string }).t === "heartbeat_ack") resolve();
    }));
    const expected = sample(Date.now() - 15_000);
    ws.send(JSON.stringify({ t: "noise_level", sample: expected }));
    ws.send(JSON.stringify({ t: "heartbeat", batteryPct: 90, thermal: "none" }));
    await acknowledged; // ordering barrier: noise has been handled before the heartbeat
    const closed = new Promise<void>(resolve => ws.once("close", () => resolve()));
    ws.close();
    await closed;
    expect((await history()).body.samples).toEqual([expected]);
  });

  it("returns only real measurements and the whole day's markers on an incremental poll", async () => {
    const now = Date.now();
    const repo = new NoiseRepo(hub.deps.db);
    repo.append(id, sample(now - day - 30_000));
    repo.append(id, sample(now - 120_000));
    repo.append(id, sample(now - 15_000, null));
    const events = new EventRepo(hub.deps.db);
    const early = events.append(id, "LOUD_NOISE", now - 60_000, { peakDb: -20 });
    events.append(id, "MONITOR_LOST", now - day - 1, {});
    const result = await history(now - 30_000);
    expect(result.body.samples).toEqual([sample(now - 15_000, null)]);
    expect(result.body.events.map(event => event.id)).toEqual([early.id]);
    expect(result.body.eventsTruncated).toBe(false);
  });

  it("does not invent levels when the monitor is online but has no audio measurements", async () => {
    expect((await history()).body.samples).toEqual([]);
  });

  it("enforces authentication and validates the requested window", async () => {
    const response = await fetch(`${hub.httpsUrl}/monitors/${id}/noise-history`);
    expect(response.status).toBe(401);
    expect((await history(Date.now() + day)).response.status).toBe(400);
  });

  it("keeps recent measurements through retention pruning and replaces retransmissions", () => {
    const repo = new NoiseRepo(hub.deps.db);
    repo.append(id, sample(100));
    repo.append(id, sample(20_000));
    repo.append(id, { ...sample(20_000), peakDb: -10 });
    repo.prune(16_000);
    expect(repo.between(id, 0, 50_000)).toEqual([{ ...sample(20_000), peakDb: -10 }]);
  });
});
