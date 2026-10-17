import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, expect, it } from "vitest";
import { WebSocket } from "ws";
import { encodeMediaFrame, MediaKind } from "@kids-monitor/protocol";
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
function binary(ws: WebSocket): Promise<Buffer> {
  return new Promise(resolve => { const receive = (data: Buffer, isBinary: boolean) => {
    if (isBinary) { ws.off("message", receive); resolve(data); }
  }; ws.on("message", receive); });
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
async function requestTalk(requestId = "voice-1") {
  const asked = message(monitor, "start_talk");
  send(parent, { t: "start_talk", streamId, requestId });
  return await asked as { talkId: number; streamId: number };
}
async function readyTalk(requestId = "voice-1") {
  const talk = await requestTalk(requestId);
  const ready = message(parent, "talk_state");
  send(monitor, { t: "talk_started", talkId: talk.talkId });
  expect(await ready).toMatchObject({ ready: true, talkId: talk.talkId, requestId });
  return talk.talkId;
}
beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-talk-"))), 0);
  const m = await connect("monitor", "room"); monitor = m.ws; monitorId = m.id;
  const p = await connect("parent", "parent"); parent = p.ws; parentId = p.id;
  other = (await connect("parent", "other")).ws;
  const started = message(monitor, "start_stream");
  send(parent, { t: "request_stream", monitorId, video: false, audio: true });
  streamId = (await started).streamId;
  const ready = message(parent, "stream_ready");
  send(monitor, { t: "stream_started", streamId, video: null, audio: { sampleRate: 16000, channels: 1 } });
  await ready;
});
afterEach(async () => { for (const ws of sockets.splice(0)) ws.terminate(); await hub.close(); hub.deps.db.close(); });

it("negotiates speaker readiness and forwards only the authorized parent's current PCM", async () => {
  const { talkId } = await requestTalk();
  const good = Buffer.from(encodeMediaFrame({ kind: MediaKind.Pcm16, streamId: talkId, timestampUs: 0n, payload: new Uint8Array([1, 0]) }));
  // Packets before readiness, another sender, wrong kind/id, and oversized PCM must be dropped.
  parent.send(good);
  const ready = message(parent, "talk_state"); send(monitor, { t: "talk_started", talkId }); await ready;
  other.send(good);
  parent.send(encodeMediaFrame({ kind: MediaKind.Jpeg, streamId: talkId, timestampUs: 0n, payload: new Uint8Array([2, 0]) }));
  parent.send(encodeMediaFrame({ kind: MediaKind.Pcm16, streamId: streamId, timestampUs: 0n, payload: new Uint8Array([3, 0]) }));
  parent.send(encodeMediaFrame({ kind: MediaKind.Pcm16, streamId: talkId, timestampUs: 0n, payload: new Uint8Array(3202) }));
  const received = binary(monitor);
  parent.send(good);
  expect(await received).toEqual(good);
});
it("refuses an authenticated parent that is not watching this monitor", async () => {
  const reply = message(other, "talk_state"); send(other, { t: "start_talk", streamId, requestId: "other" });
  expect(await reply).toMatchObject({ ready: false, talkId: null });
});
it("allows only one speaking parent even when both are viewers", async () => {
  await readyTalk();
  const joined = message(other, "stream_ready"); send(other, { t: "request_stream", monitorId, video: false, audio: true }); await joined;
  const reply = message(other, "talk_state"); send(other, { t: "start_talk", streamId, requestId: "other" });
  expect(await reply).toMatchObject({ ready: false, reason: "Another parent is talking" });
});
it("cancels a pending speaker and ignores late acknowledgements and stale cancellation", async () => {
  const old = await requestTalk("old");
  const stop = message(monitor, "stop_talk"); send(parent, { t: "stop_talk", streamId, requestId: "old" });
  expect(await stop).toMatchObject({ talkId: old.talkId });
  const talkId = await readyTalk("new");
  expect(talkId).not.toBe(old.talkId);
  send(monitor, { t: "talk_started", talkId: old.talkId });
  send(parent, { t: "stop_talk", streamId, requestId: "old" });
  const received = binary(monitor);
  const frame = Buffer.from(encodeMediaFrame({ kind: MediaKind.Pcm16, streamId: talkId, timestampUs: 0n, payload: new Uint8Array([4, 0]) }));
  parent.send(frame); expect(await received).toEqual(frame);
});
it("releases the speaker when the parent disconnects while another viewer stays", async () => {
  const talkId = await readyTalk();
  const joined = message(other, "stream_ready"); send(other, { t: "request_stream", monitorId, video: false, audio: true }); await joined;
  const stop = message(monitor, "stop_talk"); parent.close();
  expect(await stop).toMatchObject({ talkId });
  expect(hub.relay.streamFor(monitorId)?.viewers.has(parentId)).toBe(false);
  expect(hub.relay.streamFor(monitorId)?.viewers.size).toBe(1);
});
it("speaker failure ends voice without ending the watch session", async () => {
  const talkId = await readyTalk();
  const ended = message(parent, "talk_state"); send(monitor, { t: "talk_stopped", talkId, reason: "Speaker unavailable" });
  expect(await ended).toMatchObject({ ready: false, reason: "Speaker unavailable" });
  expect(hub.relay.streamFor(monitorId)?.ready).toBe(true);
});
