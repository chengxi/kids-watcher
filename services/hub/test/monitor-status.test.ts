import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { MonitorToHubSchema } from "@kids-monitor/protocol";
import { mintToken } from "../src/auth.js";
import { createDeps, startHub, type Hub } from "../src/server.js";
let hub: Hub;
let monitorToken: string;
let parentToken: string;
let id: string;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;
beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });
beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-status-"))), 0);
  monitorToken = mintToken(); parentToken = mintToken();
  id = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  hub.deps.devices.create("parent", "Parent", parentToken);
});
afterEach(async () => { await hub.close(); hub.deps.db.close(); });
async function status() {
  const response = await fetch(`${hub.httpsUrl}/monitors`, { headers: { Authorization: `Bearer ${parentToken}` } });
  return (await response.json() as { monitors: Record<string, unknown>[] }).monitors.find(m => m.id === id)!;
}
async function connect() {
  const ws = new WebSocket(`${hub.wsUrl}/ws?role=monitor`, { rejectUnauthorized: false, headers: { Authorization: `Bearer ${monitorToken}` } });
  await new Promise<void>((resolve, reject) => { ws.once("open", resolve); ws.once("error", reject); });
  return ws;
}
async function heartbeat(ws: WebSocket, extra: Record<string, unknown>) {
  const ack = new Promise<void>(resolve => {
    const onMessage = (data: unknown) => {
      if (JSON.parse(String(data)).t === "heartbeat_ack") { ws.off("message", onMessage); resolve(); }
    };
    ws.on("message", onMessage);
  });
  ws.send(JSON.stringify({ t: "heartbeat", batteryPct: 88, thermal: "none", ...extra }));
  await ack;
}
it("serves fresh monitor information and drops it when the socket closes", async () => {
  const ws = await connect();
  await heartbeat(ws, { charging: true, batteryTemperatureC: 32.5, speakerVolumePct: 80 });
  expect(await status()).toMatchObject({ online: true, batteryPct: 88, charging: true, batteryTemperatureC: 32.5, speakerVolumePct: 80 });
  await heartbeat(ws, { charging: false, batteryTemperatureC: 29, speakerVolumePct: 0 });
  expect(await status()).toMatchObject({ charging: false, batteryTemperatureC: 29, speakerVolumePct: 0 });
  const closed = new Promise<void>(resolve => ws.once("close", () => resolve()));
  ws.close(); await closed;
  await vi.waitFor(() => expect(hub.registry.isOnline(id)).toBe(false));
  expect(await status()).toMatchObject({ online: false, batteryPct: null, charging: null, batteryTemperatureC: null, speakerVolumePct: null });
});
it("accepts old monitor heartbeats and explicit unavailable readings without stale values", async () => {
  const ws = await connect();
  await heartbeat(ws, { charging: true, batteryTemperatureC: 32.5, speakerVolumePct: 80 });
  await heartbeat(ws, {});
  expect(await status()).toMatchObject({ online: true, charging: null, batteryTemperatureC: null, speakerVolumePct: null });
  await heartbeat(ws, { charging: null, batteryTemperatureC: null, speakerVolumePct: null });
  expect(await status()).toMatchObject({ online: true, charging: null, batteryTemperatureC: null, speakerVolumePct: null });
  ws.close();
});
it("rejects invalid monitor readings", () => {
  for (const extra of [{ charging: 1 }, { batteryTemperatureC: NaN }, { batteryTemperatureC: 101 }, { speakerVolumePct: 101 }, { speakerVolumePct: -1 }]) {
    expect(MonitorToHubSchema.safeParse({ t: "heartbeat", batteryPct: 88, thermal: "none", ...extra }).success).toBe(false);
  }
});
