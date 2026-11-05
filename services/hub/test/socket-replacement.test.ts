import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MediaKind, encodeMediaFrame } from "@kids-monitor/protocol";
import { WebSocket } from "ws";
import { EventRepo } from "../src/db/events.js";
import { mintToken } from "../src/auth.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;
let monitorToken: string;
let parentToken: string;
let otherParentToken: string;
let monitorId: string;
let parentId: string;

const clients: WebSocket[] = [];
const retainedServerSockets: WebSocket[] = [];
const waiterCleanups = new Set<() => void>();

function open(token: string, role: string): Promise<WebSocket> {
  const ws = new WebSocket(`${hub.wsUrl}/ws?role=${role}`, {
    rejectUnauthorized: false,
    headers: { Authorization: `Bearer ${token}` },
  });
  clients.push(ws);
  return new Promise((resolve, reject) => {
    ws.once("open", () => resolve(ws));
    ws.once("error", reject);
  });
}

function waitFor(ws: WebSocket, type: string): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    let timeout: ReturnType<typeof setTimeout>;
    const cleanup = () => {
      clearTimeout(timeout);
      ws.off("message", onMessage);
      waiterCleanups.delete(cleanup);
    };
    const onMessage = (data: Buffer, binary: boolean) => {
      if (binary) return;
      const message = JSON.parse(data.toString("utf8")) as Record<string, unknown>;
      if (message["t"] !== type) return;
      cleanup();
      resolve(message);
    };
    timeout = setTimeout(() => {
      cleanup();
      reject(new Error(`timed out waiting for ${type}`));
    }, 2_000);
    waiterCleanups.add(cleanup);
    ws.on("message", onMessage);
  });
}

function waitForBinary(ws: WebSocket): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    let timeout: ReturnType<typeof setTimeout>;
    const cleanup = () => {
      clearTimeout(timeout);
      ws.off("message", onMessage);
      waiterCleanups.delete(cleanup);
    };
    const onMessage = (data: Buffer, binary: boolean) => {
      if (!binary) return;
      cleanup();
      resolve(data);
    };
    timeout = setTimeout(() => {
      cleanup();
      reject(new Error("timed out waiting for binary media"));
    }, 2_000);
    waiterCleanups.add(cleanup);
    ws.on("message", onMessage);
  });
}

function delayCloseOfCurrent(deviceId: string): WebSocket {
  const oldSocket = hub.registry.get(deviceId)?.socket;
  if (oldSocket === undefined || oldSocket === null) throw new Error("missing old server socket");
  retainedServerSockets.push(oldSocket);
  vi.spyOn(oldSocket, "close").mockImplementation(() => {});
  return oldSocket;
}

function deliverLateClose(socket: WebSocket): void {
  socket.emit("close", 4000, Buffer.from("replaced by a newer connection"));
}

async function establishStream(monitor: WebSocket, parent: WebSocket): Promise<number> {
  const starting = waitFor(monitor, "start_stream");
  parent.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
  const { streamId } = await starting;
  const ready = waitFor(parent, "stream_ready");
  monitor.send(JSON.stringify({
    t: "stream_started", streamId,
    video: { width: 640, height: 480, fps: 10 }, audio: null,
  }));
  await ready;
  return streamId as number;
}

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-replacement-"))), 0);
  monitorToken = mintToken();
  parentToken = mintToken();
  otherParentToken = mintToken();
  monitorId = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  parentId = hub.deps.devices.create("parent", "Parent", parentToken).id;
  hub.deps.devices.create("parent", "Other parent", otherParentToken);
});

afterEach(async () => {
  for (const cleanup of [...waiterCleanups]) cleanup();
  vi.restoreAllMocks();
  for (const socket of [...clients, ...retainedServerSockets]) socket.terminate();
  clients.length = 0;
  retainedServerSockets.length = 0;
  await hub.close();
});

describe("socket replacement ownership", () => {
  it("retires a replaced parent's old viewer once and keeps the fresh stream after late callbacks", async () => {
    const monitor = await open(monitorToken, "monitor");
    const oldParent = await open(parentToken, "parent");
    const firstStreamId = await establishStream(monitor, oldParent);
    const talkStarting = waitFor(monitor, "start_talk");
    oldParent.send(JSON.stringify({ t: "start_talk", streamId: firstStreamId, requestId: "replace-talk" }));
    const { talkId } = await talkStarting;
    const talkReady = waitFor(oldParent, "talk_state");
    monitor.send(JSON.stringify({ t: "talk_started", talkId }));
    await talkReady;
    let stopTalkCount = 0;
    monitor.on("message", (data: Buffer, binary: boolean) => {
      if (!binary && (JSON.parse(data.toString("utf8")) as { t: string }).t === "stop_talk") {
        stopTalkCount++;
      }
    });
    const oldServerSocket = delayCloseOfCurrent(parentId);
    const dropViewer = vi.spyOn(hub.relay, "dropViewer");

    const talkStopped = waitFor(monitor, "stop_talk");
    const replacement = await open(parentToken, "parent");
    expect(hub.registry.get(parentId)?.socket).not.toBe(oldServerSocket);
    expect(dropViewer).toHaveBeenCalledTimes(1);
    expect((await talkStopped)["talkId"]).toBe(talkId);
    expect(stopTalkCount).toBe(1);

    const starting = waitFor(monitor, "start_stream");
    replacement.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const { streamId } = await starting;
    expect(streamId).not.toBe(firstStreamId);
    const ready = waitFor(replacement, "stream_ready");
    monitor.send(JSON.stringify({
      t: "stream_started", streamId,
      video: { width: 640, height: 480, fps: 10 }, audio: null,
    }));
    await ready;

    const cleanupCallsAtBoundary = dropViewer.mock.calls.length;
    deliverLateClose(oldServerSocket);
    deliverLateClose(oldServerSocket);
    expect(dropViewer).toHaveBeenCalledTimes(cleanupCallsAtBoundary);
    expect(stopTalkCount).toBe(1);

    const expectedMedia = Buffer.from(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId: streamId as number, timestampUs: 1n,
      payload: new Uint8Array([1, 2, 3]),
    }));
    const media = waitForBinary(replacement);
    monitor.send(expectedMedia, { binary: true });
    expect(await media).toEqual(expectedMedia);

    const lastSeenSentinel = 1_800_000_000_000;
    hub.deps.devices.touch(parentId, lastSeenSentinel);
    const request = vi.spyOn(hub.relay, "request");
    const forwardTalkFrame = vi.spyOn(hub.relay, "forwardTalkFrame");
    const eventsBefore = new EventRepo(hub.deps.db).since(0, 100).length;
    oldServerSocket.emit("message", Buffer.from(JSON.stringify({
      t: "request_stream", monitorId, video: true, audio: false,
    })), false);
    oldServerSocket.emit("message", Buffer.from(encodeMediaFrame({
      kind: MediaKind.Pcm16, streamId: talkId, timestampUs: 5n,
      payload: new Uint8Array([1, 0]),
    })), true);
    expect(request).not.toHaveBeenCalled();
    expect(forwardTalkFrame).not.toHaveBeenCalled();
    expect(hub.deps.devices.get(parentId)?.lastSeenMs).toBe(lastSeenSentinel);
    expect(new EventRepo(hub.deps.db).since(0, 100)).toHaveLength(eventsBefore);

    const stopped = waitFor(monitor, "stop_stream");
    replacement.close();
    await stopped;
    expect(hub.registry.get(parentId)).toBeNull();
  });

  it("keeps a shared stream when a parent is replaced and the other viewer remains", async () => {
    const monitor = await open(monitorToken, "monitor");
    const oldParent = await open(parentToken, "parent");
    const otherParent = await open(otherParentToken, "parent");
    const streamId = await establishStream(monitor, oldParent);
    const joined = waitFor(otherParent, "stream_ready");
    otherParent.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    await joined;

    const oldServerSocket = delayCloseOfCurrent(parentId);
    const dropViewer = vi.spyOn(hub.relay, "dropViewer");
    const replacement = await open(parentToken, "parent");
    expect(dropViewer).toHaveBeenCalledTimes(1);
    const rejoined = waitFor(replacement, "stream_ready");
    replacement.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    expect((await rejoined)["streamId"]).toBe(streamId);
    deliverLateClose(oldServerSocket);

    const expectedMedia = Buffer.from(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId, timestampUs: 2n, payload: new Uint8Array([4, 5]),
    }));
    const otherMedia = waitForBinary(otherParent);
    const media = waitForBinary(replacement);
    monitor.send(expectedMedia, { binary: true });
    expect(await media).toEqual(expectedMedia);
    expect(await otherMedia).toEqual(expectedMedia);
  });

  it("ends a replaced monitor stream once and ignores its late close and messages", async () => {
    const oldMonitor = await open(monitorToken, "monitor");
    const parent = await open(parentToken, "parent");
    const firstStreamId = await establishStream(oldMonitor, parent);
    const oldServerSocket = delayCloseOfCurrent(monitorId);
    const stopStream = vi.spyOn(hub.relay, "onStreamStopped");

    const replacementMonitor = await open(monitorToken, "monitor");
    expect(hub.registry.get(monitorId)?.socket).not.toBe(oldServerSocket);
    expect(stopStream).toHaveBeenCalledTimes(1);

    const starting = waitFor(replacementMonitor, "start_stream");
    parent.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const { streamId } = await starting;
    expect(streamId).not.toBe(firstStreamId);
    const ready = waitFor(parent, "stream_ready");
    replacementMonitor.send(JSON.stringify({
      t: "stream_started", streamId,
      video: { width: 640, height: 480, fps: 10 }, audio: null,
    }));
    await ready;

    const cleanupCallsAtBoundary = stopStream.mock.calls.length;
    deliverLateClose(oldServerSocket);
    expect(stopStream).toHaveBeenCalledTimes(cleanupCallsAtBoundary);

    const expectedMedia = Buffer.from(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId: streamId as number, timestampUs: 3n,
      payload: new Uint8Array([6, 7, 8]),
    }));
    const media = waitForBinary(parent);
    replacementMonitor.send(expectedMedia, { binary: true });
    expect(await media).toEqual(expectedMedia);

    const lastSeenSentinel = 1_800_000_000_001;
    hub.deps.devices.touch(monitorId, lastSeenSentinel);
    const onStreamStarted = vi.spyOn(hub.relay, "onStreamStarted");
    const forwardFrame = vi.spyOn(hub.relay, "forwardFrame");
    const eventsBefore = new EventRepo(hub.deps.db).since(0, 100).length;
    oldServerSocket.emit("message", Buffer.from(JSON.stringify({
      t: "stream_started", streamId, video: null, audio: null,
    })), false);
    oldServerSocket.emit("message", Buffer.from(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId: streamId as number, timestampUs: 4n,
      payload: new Uint8Array([9]),
    })), true);
    oldServerSocket.emit("message", Buffer.from(JSON.stringify({
      t: "event", clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
      type: "LOUD_NOISE", tsMs: Date.now(), metadata: {},
    })), false);
    expect(onStreamStarted).not.toHaveBeenCalled();
    expect(forwardFrame).not.toHaveBeenCalled();
    expect(hub.deps.devices.get(monitorId)?.lastSeenMs).toBe(lastSeenSentinel);
    expect(new EventRepo(hub.deps.db).since(0, 100)).toHaveLength(eventsBefore);
  });
});
