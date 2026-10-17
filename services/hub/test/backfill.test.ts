import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { mintToken } from "../src/auth.js";
import { EventRepo } from "../src/db/events.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;
let monitorToken: string;
let parentToken: string;
let monitorId: string;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;

beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });

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

function nextJson(ws: WebSocket, t: string): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    const onMessage = (data: Buffer, isBinary: boolean) => {
      if (isBinary) return;
      const msg = JSON.parse(data.toString("utf8")) as Record<string, unknown>;
      if (msg["t"] === t) {
        ws.off("message", onMessage);
        resolve(msg);
      }
    };
    ws.on("message", onMessage);
  });
}

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-bf-"))), 0);
  monitorToken = mintToken();
  parentToken = mintToken();
  monitorId = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  hub.deps.devices.create("parent", "Mum", parentToken);
});
afterEach(async () => hub.close());

describe("event fan-out and backfill", () => {
  it("delivers a monitor event to a connected parent", async () => {
    const parent = await open(parentToken, "parent");
    const monitor = await open(monitorToken, "monitor");
    const received = nextJson(parent, "event");
    monitor.send(JSON.stringify({
      t: "event",
      clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
      type: "LOUD_NOISE",
      tsMs: 1757000000000,
      metadata: { peakDb: -14 },
    }));
    const msg = await received;
    expect(msg["monitorId"]).toBe(monitorId);
    expect(msg["type"]).toBe("LOUD_NOISE");
    expect(typeof msg["eventId"]).toBe("number");
    parent.close();
    monitor.close();
  });

  it("persists events raised while no parent was connected", async () => {
    const monitor = await open(monitorToken, "monitor");
    monitor.send(JSON.stringify({
      t: "event",
      clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
      type: "LOUD_NOISE",
      tsMs: 1757000000000,
      metadata: {},
    }));
    await new Promise((r) => setTimeout(r, 50));
    expect(new EventRepo(hub.deps.db).since(0, 10)).toHaveLength(1);
    monitor.close();
  });

  it("backfills missed events over GET /events?since=", async () => {
    const events = new EventRepo(hub.deps.db);
    const first = events.append(monitorId, "LOUD_NOISE", 1, {});
    events.append(monitorId, "MOTION", 2, {});
    const res = await fetch(`${hub.httpsUrl}/events?since=${first.id}`, {
      headers: { Authorization: `Bearer ${parentToken}` },
    });
    const body = (await res.json()) as { events: { type: string }[] };
    expect(body.events.map((e) => e.type)).toEqual(["MOTION"]);
  });

  it("refuses GET /events without a token", async () => {
    const res = await fetch(`${hub.httpsUrl}/events?since=0`);
    expect(res.status).toBe(401);
  });

  it("reports monitor liveness over GET /monitors", async () => {
    const monitor = await open(monitorToken, "monitor");
    const res = await fetch(`${hub.httpsUrl}/monitors`, {
      headers: { Authorization: `Bearer ${parentToken}` },
    });
    const body = (await res.json()) as { monitors: { id: string; online: boolean }[] };
    expect(body.monitors.find((m) => m.id === monitorId)?.online).toBe(true);
    monitor.close();
  });
});
