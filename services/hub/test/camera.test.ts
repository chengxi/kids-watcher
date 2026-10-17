import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { ParentToHubSchema, HubToMonitorSchema } from "@kids-monitor/protocol";
import { mintToken } from "../src/auth.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;
let monitor: WebSocket;
let parent: WebSocket;
let other: WebSocket;
let monitorId: string;
let parentId: string;
let streamId: number;
const sockets: WebSocket[] = [];
function message(ws: WebSocket, t: string): Promise<Record<string, any>> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { ws.off("message", receive); reject(new Error(`no ${t}`)); }, 2000);
    function receive(data: Buffer, binary: boolean) {
      if (binary) return;
      const parsed = JSON.parse(data.toString());
      if (parsed.t === t) { clearTimeout(timeout); ws.off("message", receive); resolve(parsed); }
    }
    ws.on("message", receive);
  });
}
async function connect(role: "parent" | "monitor", name: string) {
  const token = mintToken();
  const id = hub.deps.devices.create(role, name, token).id;
  const ws = new WebSocket(`${hub.wsUrl}/ws?role=${role}`, { rejectUnauthorized: false, headers: { Authorization: `Bearer ${token}` } });
  sockets.push(ws);
  await new Promise<void>((resolve, reject) => { ws.once("open", resolve); ws.once("error", reject); });
  return { ws, id };
}
function send(ws: WebSocket, msg: object) { ws.send(JSON.stringify(msg)); }
beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-camera-"))), 0);
  const m = await connect("monitor", "room"); monitor = m.ws; monitorId = m.id;
  const p = await connect("parent", "parent"); parent = p.ws; parentId = p.id;
  other = (await connect("parent", "other")).ws;
  const started = message(monitor, "start_stream");
  send(parent, { t: "request_stream", monitorId, video: true, audio: true });
  streamId = (await started).streamId;
  const ready = message(parent, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10, facing: "back", availableCameras: ["back", "front"] }, audio: { sampleRate: 16000, channels: 1 } });
  await ready;
});
afterEach(async () => { for (const ws of sockets.splice(0)) ws.terminate(); await hub.close(); hub.deps.db.close(); });

function select(ws: WebSocket, requestId: string, facing = "front", id = streamId) {
  send(ws, { t: "select_camera", streamId: id, requestId, facing });
}
async function joinViewer() {
  const joined = message(other, "stream_ready");
  send(other, { t: "request_stream", monitorId, video: true, audio: true });
  await joined;
}

it("authorizes the viewer and broadcasts camera formats to all current and later viewers", async () => {
  await joinViewer();
  const asked = message(monitor, "select_camera"); select(parent, "front");
  expect(await asked).toMatchObject({ streamId, requestId: "front", facing: "front" });
  const updated = message(parent, "stream_ready"); const otherUpdated = message(other, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: { width: 480, height: 640, fps: 10, rotationDegrees: 270,
    facing: "front", availableCameras: ["back", "front"] }, audio: { sampleRate: 16000, channels: 1 } });
  expect((await updated).video).toMatchObject({ facing: "front", rotationDegrees: 270 });
  expect((await otherUpdated).video).toMatchObject({ facing: "front" });
  const result = message(parent, "camera_result");
  send(monitor, { t: "camera_result", streamId, requestId: "front", reason: null });
  expect(await result).toMatchObject({ requestId: "front", reason: null });
  const later = await connect("parent", "later");
  const ready = message(later.ws, "stream_ready");
  send(later.ws, { t: "request_stream", monitorId, video: true, audio: true });
  expect((await ready).video).toMatchObject({ facing: "front" });
  const back = message(monitor, "select_camera"); select(parent, "back", "back");
  expect(await back).toMatchObject({ facing: "back" });
});
it("refuses nonviewers stale streams unsupported lenses and overlapping switches", async () => {
  let reply = message(other, "camera_result"); select(other, "intruder");
  expect((await reply).reason).toMatch(/live view/);
  reply = message(parent, "camera_result"); select(parent, "stale", "front", streamId + 1);
  expect((await reply).reason).toMatch(/live view/);
  await joinViewer();
  const asked = message(monitor, "select_camera"); select(parent, "one"); await asked;
  reply = message(other, "camera_result"); select(other, "two");
  expect((await reply).reason).toMatch(/already in progress/);
  const result = message(parent, "camera_result");
  send(monitor, { t: "camera_result", streamId, requestId: "one", reason: "Camera unavailable" });
  expect((await result).reason).toBe("Camera unavailable");
  const updated = message(parent, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null }); await updated;
  reply = message(parent, "camera_result"); select(parent, "legacy");
  expect((await reply).reason).toMatch(/unavailable/);
});
it("ignores stale results and retains current pending switch", async () => {
  const asked = message(monitor, "select_camera"); select(parent, "current"); await asked;
  send(monitor, { t: "camera_result", streamId: streamId + 1, requestId: "current", reason: null });
  send(monitor, { t: "camera_result", streamId, requestId: "old", reason: null });
  const result = message(parent, "camera_result");
  send(monitor, { t: "camera_result", streamId, requestId: "current", reason: "Restored previous camera" });
  expect(await result).toMatchObject({ requestId: "current", reason: "Restored previous camera" });
  expect(hub.relay.streamFor(monitorId)?.cameraRequest).toBeUndefined();
});
it("keeps audio relay and voice active during a camera switch", async () => {
  const start = message(monitor, "start_talk"); send(parent, { t: "start_talk", streamId, requestId: "voice" });
  const talkId = (await start).talkId;
  const voice = message(parent, "talk_state"); send(monitor, { t: "talk_started", talkId }); await voice;
  const asked = message(monitor, "select_camera"); select(parent, "front"); await asked;
  const updated = message(parent, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: null, audio: { sampleRate: 16000, channels: 1 } });
  expect((await updated).audio).toMatchObject({ sampleRate: 16000 });
  expect(hub.relay.streamFor(monitorId)?.streamId).toBe(streamId);
  const stopped = message(monitor, "stop_talk"); send(parent, { t: "stop_talk", streamId, requestId: "voice" });
  expect(await stopped).toMatchObject({ talkId });
});
it("last viewer leaving ends the camera stream while a switch is pending", async () => {
  const asked = message(monitor, "select_camera"); select(parent, "front"); await asked;
  const stopped = message(monitor, "stop_stream"); send(parent, { t: "stop_stream", streamId });
  expect(await stopped).toMatchObject({ streamId });
  expect(hub.relay.streamFor(monitorId)).toBeNull();
});
it("rejects invalid camera control payloads in both directions", () => {
  for (const schema of [ParentToHubSchema, HubToMonitorSchema]) {
    for (const invalid of [{ facing: "side" }, { streamId: 0 }, { requestId: "" }, { streamId: 65536 }]) {
      expect(schema.safeParse({ t: "select_camera", streamId, requestId: "front", facing: "front", ...invalid }).success).toBe(false);
    }
  }
});

it("bounds a nonresponsive switch and accepts another explicit attempt", async () => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  try {
    const reply = new Promise<Record<string, any>>(resolve => parent.on("message", data => {
      const parsed = JSON.parse(data.toString());
      if (parsed.t === "camera_result" && parsed.requestId === "timeout") resolve(parsed);
    }));
    hub.relay.selectCamera(parentId, streamId, "timeout", "front");
    vi.advanceTimersByTime(30_000);
    expect((await reply).reason).toMatch(/did not finish/);
    expect(hub.relay.streamFor(monitorId)?.cameraRequest).toBeUndefined();
    hub.relay.selectCamera(parentId, streamId, "retry", "front");
    expect(hub.relay.streamFor(monitorId)?.cameraRequest?.requestId).toBe("retry");
  } finally { vi.useRealTimers(); }
});

it("a viewer joining during a switch waits for the new lens without restarting the shared stream", async () => {
  const asked = message(monitor, "select_camera"); select(parent, "front"); await asked;
  const paused = message(parent, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: null, audio: { sampleRate: 16000, channels: 1 } }); await paused;
  const waiting = message(other, "stream_waiting");
  send(other, { t: "request_stream", monitorId, video: true, audio: true, requestId: "joining" });
  expect(await waiting).toMatchObject({ streamId, phase: "preparing_video", requestId: "joining" });
  const ready = message(other, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10, facing: "front",
    availableCameras: ["back", "front"] }, audio: { sampleRate: 16000, channels: 1 } });
  expect(await ready).toMatchObject({ streamId, requestId: "joining", video: { facing: "front" } });
});
