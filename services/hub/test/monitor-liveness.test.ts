import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import Database from "better-sqlite3";
import { afterEach, beforeEach, expect, it } from "vitest";
import { LIVENESS_TIMEOUT_MS } from "@kids-monitor/protocol";
import { WebSocket } from "ws";
import { mintToken } from "../src/auth.js";
import { DeviceRepo } from "../src/db/devices.js";
import { EventRepo } from "../src/db/events.js";
import { MonitorLiveness } from "../src/db/monitorLiveness.js";
import { migrate, MIGRATIONS } from "../src/db/schema.js";
import { createDeps, startHub, type Hub } from "../src/server.js";
import { Watchdog } from "../src/ws/watchdog.js";
let hub: Hub;
let monitorToken: string;
let parentToken: string;
let id: string;
beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-liveness-"))), 0);
  monitorToken = mintToken(); parentToken = mintToken();
  id = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  hub.deps.devices.create("parent", "Parent", parentToken);
});
afterEach(async () => { await hub.close(); hub.deps.db.close(); });
async function connect(role: "parent" | "monitor") {
  const ws = new WebSocket(`${hub.wsUrl}/ws?role=${role}`, { rejectUnauthorized: false, headers: { Authorization: `Bearer ${role === "parent" ? parentToken : monitorToken}` } });
  await new Promise<void>((resolve, reject) => { ws.once("open", resolve); ws.once("error", reject); });
  return ws;
}
async function disconnect(ws: WebSocket) {
  const closed = new Promise<void>(resolve => ws.once("close", () => resolve()));
  ws.close(); await closed;
}
const events = () => new EventRepo(hub.deps.db).since(0, 100);
it("records no recovery for first connection or a short reconnect", async () => {
  await disconnect(await connect("monitor"));
  await disconnect(await connect("monitor"));
  expect(events()).toEqual([]);
});
it("emits one recovery to subscribed parents after a reported loss and survives watchdog restart", async () => {
  const parent = await connect("parent");
  const received: Record<string, unknown>[] = [];
  parent.on("message", data => received.push(JSON.parse(String(data))));
  const seen = Date.now() - LIVENESS_TIMEOUT_MS - 1000;
  hub.deps.devices.touch(id, seen);
  new Watchdog(hub.deps, hub.registry, 30).sweep(Date.now());
  new Watchdog(hub.deps, hub.registry, 30).sweep(Date.now());
  expect(events().map(e => e.type)).toEqual(["MONITOR_LOST"]);
  await disconnect(await connect("monitor"));
  await disconnect(await connect("monitor"));
  await new Promise<void>(resolve => setImmediate(resolve));
  expect(events().map(e => e.type)).toEqual(["MONITOR_LOST", "MONITOR_ONLINE"]);
  expect(received.filter(e => e.t === "event").map(e => e.type)).toEqual(["MONITOR_LOST", "MONITOR_ONLINE"]);
  expect(hub.deps.db.prepare("SELECT * FROM monitor_liveness").all()).toEqual([]);
  await disconnect(parent);
});
it("recovers a long hub outage even when reconnect precedes the watchdog sweep", async () => {
  const previous = Date.now() - LIVENESS_TIMEOUT_MS - 1000;
  hub.deps.devices.touch(id, previous);
  await disconnect(await connect("monitor"));
  expect(events().map(e => e.type)).toEqual(["MONITOR_LOST", "MONITOR_ONLINE"]);
  expect(events()[0]?.tsMs).toBe(previous + LIVENESS_TIMEOUT_MS);
});
it("migration retains pending outages without treating an old outage as current", () => {
  const db = new Database(":memory:");
  db.exec(MIGRATIONS.slice(0, 2).join("\n"));
  db.exec("CREATE TABLE schema_version(version INTEGER NOT NULL); INSERT INTO schema_version VALUES(2)");
  const devices = new DeviceRepo(db); const repo = new EventRepo(db);
  const offline = devices.create("monitor", "Offline", mintToken()).id;
  const healthy = devices.create("monitor", "Healthy", mintToken()).id;
  devices.touch(offline, 1000); devices.touch(healthy, 3000);
  repo.append(offline, "MONITOR_LOST", 2000, {}); repo.append(healthy, "MONITOR_LOST", 2000, {});
  migrate(db);
  expect(new MonitorLiveness(db).lost(offline, 1000, 4000)).toBeNull();
  expect(new MonitorLiveness(db).recovered(offline, 5000)?.type).toBe("MONITOR_ONLINE");
  expect(new MonitorLiveness(db).recovered(healthy, 5000)).toBeNull();
  expect(new MonitorLiveness(db).recovered(offline, 6000)).toBeNull();
  db.close();
});

it("does not let a monitor manufacture connection events", async () => {
  const ws = await connect("monitor");
  const errors: Record<string, unknown>[] = [];
  ws.on("message", data => errors.push(JSON.parse(String(data))));
  ws.send(JSON.stringify({ t: "event", type: "MONITOR_ONLINE", tsMs: Date.now(), metadata: {} }));
  await new Promise<void>(resolve => ws.on("message", data => { if (JSON.parse(String(data)).t === "error") resolve(); }));
  expect(events()).toEqual([]);
  expect(errors.some(e => e.t === "error")).toBe(true);
  await disconnect(ws);
});
