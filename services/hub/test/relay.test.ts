import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import {
  LIVENESS_TIMEOUT_MS,
  MediaKind,
  decodeMediaFrame,
  encodeMediaFrame,
} from "@kids-monitor/protocol";
import { MAX_MEDIA_FRAME_BYTES } from "../src/ws/backpressure.js";
import { MAX_WS_MESSAGE_BYTES } from "../src/ws/server.js";
import { mintToken } from "../src/auth.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;
let monitorToken: string;
let mumToken: string;
let dadToken: string;
let monitorId: string;
let mumId: string;
let dadId: string;

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

function waitForBinary(ws: WebSocket): Promise<Buffer> {
  return new Promise((resolve) => {
    const onMessage = (data: Buffer, isBinary: boolean) => {
      if (!isBinary) return;
      ws.off("message", onMessage);
      resolve(data);
    };
    ws.on("message", onMessage);
  });
}

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-relay-"))), 0);
  monitorToken = mintToken();
  mumToken = mintToken();
  dadToken = mintToken();
  monitorId = hub.deps.devices.create("monitor", "Nursery", monitorToken).id;
  mumId = hub.deps.devices.create("parent", "Mum", mumToken).id;
  dadId = hub.deps.devices.create("parent", "Dad", dadToken).id;
});
afterEach(async () => hub.close());

describe("media relay", () => {
  it("asks the monitor to start when the first parent requests a stream", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const asked = waitFor(monitor, "start_stream");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true }));
    const msg = await asked;
    expect(msg["video"]).toBe(true);
    expect(typeof msg["streamId"]).toBe("number");
    monitor.close(); mum.close();
  });

  it("does not ask the monitor twice when a second parent joins", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const dad = await open(dadToken, "parent");

    let startCount = 0;
    monitor.on("message", (d: Buffer, isBinary: boolean) => {
      if (!isBinary && (JSON.parse(d.toString()) as { t: string }).t === "start_stream") startCount++;
    });

    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true }));
    await new Promise((r) => setTimeout(r, 100));
    dad.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true }));
    await new Promise((r) => setTimeout(r, 100));

    expect(startCount).toBe(1);
    monitor.close(); mum.close(); dad.close();
  });

  it("relays changed rotation and retains it for a parent joining later", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const asked = waitFor(monitor, "start_stream");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true }));
    const { streamId } = await asked;
    const video = { width: 640, height: 480, fps: 10, rotationDegrees: 90 };
    const audio = { sampleRate: 16000, channels: 1 };
    const ready = waitFor(mum, "stream_ready");
    monitor.send(JSON.stringify({ t: "stream_started", streamId, video, audio }));
    await ready;
    const rotated = { ...video, rotationDegrees: 180 };
    const updated = waitFor(mum, "stream_ready");
    monitor.send(JSON.stringify({ t: "stream_started", streamId, video: rotated, audio }));
    expect(await updated).toMatchObject({ streamId, video: rotated, audio });

    const dad = await open(dadToken, "parent");
    const joined = waitFor(dad, "stream_ready");
    dad.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true }));
    expect(await joined).toMatchObject({ streamId, video: rotated, audio });
    monitor.close(); mum.close(); dad.close();
  });

  it("forwards a binary frame to every viewer unchanged", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const streamId = start["streamId"] as number;

    monitor.send(JSON.stringify({
      t: "stream_started", streamId,
      video: { width: 640, height: 480, fps: 5 }, audio: null,
    }));
    await waitFor(mum, "stream_ready");

    const received = waitForBinary(mum);
    monitor.send(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId, timestampUs: 123n, payload: new Uint8Array([9, 8, 7]),
    }), { binary: true });

    const decoded = decodeMediaFrame(new Uint8Array(await received));
    expect(decoded.payload).toEqual(new Uint8Array([9, 8, 7]));
    expect(decoded.streamId).toBe(streamId);
    monitor.close(); mum.close();
  });

  // After a stream is stopped and restarted, a monitor can still flush frames
  // stamped with the old id. A viewer must never see them.
  it("does not forward a frame stamped with another stream id", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const streamId = start["streamId"] as number;
    monitor.send(JSON.stringify({
      t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null,
    }));
    await waitFor(mum, "stream_ready");

    const received = waitForBinary(mum);
    monitor.send(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId: (streamId % 65535) + 1, timestampUs: 1n, payload: new Uint8Array([1]),
    }), { binary: true });
    monitor.send(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId, timestampUs: 2n, payload: new Uint8Array([2]),
    }), { binary: true });

    const first = decodeMediaFrame(new Uint8Array(await received));
    expect(first.streamId).toBe(streamId);
    expect(first.payload).toEqual(new Uint8Array([2]));
    monitor.close(); mum.close();
  });

  it("does not forward bytes that are not a media frame", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const streamId = start["streamId"] as number;
    monitor.send(JSON.stringify({
      t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null,
    }));
    await waitFor(mum, "stream_ready");

    const received = waitForBinary(mum);
    monitor.send(new Uint8Array([0, 1, 2]), { binary: true });
    monitor.send(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId, timestampUs: 3n, payload: new Uint8Array([3]),
    }), { binary: true });

    expect(decodeMediaFrame(new Uint8Array(await received)).payload).toEqual(new Uint8Array([3]));
    monitor.close(); mum.close();
  });

  it("tells the monitor to stop when the last viewer leaves", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const stopped = waitFor(monitor, "stop_stream");
    mum.send(JSON.stringify({ t: "stop_stream", streamId: start["streamId"] }));
    expect((await stopped)["streamId"]).toBe(start["streamId"]);
    monitor.close(); mum.close();
  });

  it("keeps streaming when one of two viewers leaves", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const dad = await open(dadToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    dad.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    await new Promise((r) => setTimeout(r, 100));

    let stopSeen = false;
    monitor.on("message", (d: Buffer, isBinary: boolean) => {
      if (!isBinary && (JSON.parse(d.toString()) as { t: string }).t === "stop_stream") stopSeen = true;
    });
    mum.send(JSON.stringify({ t: "stop_stream", streamId: start["streamId"] }));
    await new Promise((r) => setTimeout(r, 150));

    expect(stopSeen).toBe(false);
    monitor.close(); mum.close(); dad.close();
  });

  it("stops the stream when the last viewer's socket drops", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const stopped = waitFor(monitor, "stop_stream");
    mum.close();
    expect((await stopped)["streamId"]).toBe(start["streamId"]);
    monitor.close();
  });

  it("tells the parent the monitor is offline rather than hanging", async () => {
    const mum = await open(mumToken, "parent");
    const err = waitFor(mum, "error");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    expect((await err)["code"]).toBe("monitor_offline");
    mum.close();
  });

  // A parent whose phone leaves wifi range or dies never sends a close frame.
  // Waiting for TCP to notice takes many minutes, and the monitor's camera runs
  // the whole time. Silence past the liveness window ends the connection.
  it("stops the stream when the last viewer goes silent without closing its socket", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    await new Promise((r) => setTimeout(r, 50));

    const stopped = waitFor(monitor, "stop_stream");
    hub.registry.get(mumId)!.lastSeenMs = Date.now() - LIVENESS_TIMEOUT_MS - 1;
    hub.watchdog.sweep(Date.now());

    expect((await stopped)["streamId"]).toBe(start["streamId"]);
    expect(hub.registry.isOnline(mumId)).toBe(false);
    expect(hub.registry.isOnline(monitorId)).toBe(true);
    monitor.close(); mum.close();
  });

  it("keeps a viewer that is inside the liveness window", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    await new Promise((r) => setTimeout(r, 50));
    hub.registry.get(mumId)!.lastSeenMs = Date.now() - LIVENESS_TIMEOUT_MS + 5_000;
    hub.watchdog.sweep(Date.now());
    await new Promise((r) => setTimeout(r, 100));
    expect(hub.registry.isOnline(mumId)).toBe(true);
    monitor.close(); mum.close();
  });

  it("refuses a message larger than the hub's limit by closing that connection", async () => {
    const monitor = await open(monitorToken, "monitor");
    const closed = new Promise<number>((resolve) => monitor.once("close", (code) => resolve(code)));
    monitor.send(new Uint8Array(MAX_WS_MESSAGE_BYTES + 1), { binary: true });
    expect(await closed).toBe(1009);
  });

  // Only a monitor can stream. A parent naming another parent's id must not
  // make the hub send start_stream down a parent's socket.
  it("treats a request naming a parent as a request for an offline monitor", async () => {
    const mum = await open(mumToken, "parent");
    const dad = await open(dadToken, "parent");
    let dadSawStart = false;
    dad.on("message", (d: Buffer, isBinary: boolean) => {
      if (!isBinary && (JSON.parse(d.toString()) as { t: string }).t === "start_stream") dadSawStart = true;
    });
    const err = waitFor(mum, "error");
    mum.send(JSON.stringify({ t: "request_stream", monitorId: dadId, video: true, audio: false }));
    expect((await err)["code"]).toBe("monitor_offline");
    await new Promise((r) => setTimeout(r, 100));
    expect(dadSawStart).toBe(false);
    expect(hub.relay.streamFor(dadId)).toBeNull();
    mum.close(); dad.close();
  });

  // A stream_stopped the monitor sent for a stream that has already been
  // replaced must not end the current one while its camera is running.
  it("ignores stream_stopped for a stream id that is not the current one", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const streamId = start["streamId"] as number;

    let ended = false;
    mum.on("message", (d: Buffer, isBinary: boolean) => {
      if (!isBinary && (JSON.parse(d.toString()) as { t: string }).t === "stream_ended") ended = true;
    });
    monitor.send(JSON.stringify({ t: "stream_stopped", streamId: (streamId % 65535) + 1, reason: "old" }));
    await new Promise((r) => setTimeout(r, 100));

    expect(ended).toBe(false);
    expect(hub.relay.streamFor(monitorId)?.streamId).toBe(streamId);
    monitor.close(); mum.close();
  });

  it("ignores stream_started for a stream id that is not the current one", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const streamId = start["streamId"] as number;

    const ready = waitFor(mum, "stream_ready");
    monitor.send(JSON.stringify({
      t: "stream_started", streamId: (streamId % 65535) + 1,
      video: { width: 320, height: 240, fps: 10 }, audio: null,
    }));
    monitor.send(JSON.stringify({
      t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null,
    }));

    expect(((await ready)["video"] as { width: number }).width).toBe(640);
    monitor.close(); mum.close();
  });

  it("does not forward a media frame larger than the relay's frame limit", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: false }));
    const start = await waitFor(monitor, "start_stream");
    const streamId = start["streamId"] as number;
    monitor.send(JSON.stringify({
      t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null,
    }));
    await waitFor(mum, "stream_ready");

    const received = waitForBinary(mum);
    monitor.send(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId, timestampUs: 1n, payload: new Uint8Array(MAX_MEDIA_FRAME_BYTES + 1),
    }), { binary: true });
    monitor.send(encodeMediaFrame({
      kind: MediaKind.Jpeg, streamId, timestampUs: 2n, payload: new Uint8Array([2]),
    }), { binary: true });

    expect(decodeMediaFrame(new Uint8Array(await received)).payload).toEqual(new Uint8Array([2]));
    monitor.close(); mum.close();
  });
  it("reports hub receipt before monitor acknowledgement and correlates every viewer's attempt", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const waiting = waitFor(mum, "stream_waiting");
    const asked = waitFor(monitor, "start_stream");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true, requestId: "mum-1" }));
    const { streamId } = await asked;
    expect(await waiting).toMatchObject({ streamId, requestId: "mum-1", phase: "contacting_monitor" });
    const preparing = waitFor(mum, "stream_waiting");
    monitor.send(JSON.stringify({ t: "stream_preparing", streamId }));
    expect(await preparing).toMatchObject({ requestId: "mum-1", phase: "preparing_video" });
    const dad = await open(dadToken, "parent");
    const joined = waitFor(dad, "stream_waiting");
    dad.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true, requestId: "dad-1" }));
    expect(await joined).toMatchObject({ streamId, requestId: "dad-1", phase: "preparing_video" });
    const mumReady = waitFor(mum, "stream_ready");
    const dadReady = waitFor(dad, "stream_ready");
    monitor.send(JSON.stringify({ t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null }));
    expect(await mumReady).toMatchObject({ requestId: "mum-1" });
    expect(await dadReady).toMatchObject({ requestId: "dad-1" });
    monitor.close(); mum.close(); dad.close();
  });

  it("cancels pending camera startup before ready and ignores the old reply during a retry", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const asked = waitFor(monitor, "start_stream");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true, requestId: "first" }));
    const first = (await asked)["streamId"] as number;
    const stopped = waitFor(monitor, "stop_stream");
    mum.send(JSON.stringify({ t: "cancel_stream_request", monitorId, requestId: "first" }));
    expect(await stopped).toMatchObject({ streamId: first });
    const askedAgain = waitFor(monitor, "start_stream");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true, requestId: "second" }));
    const second = (await askedAgain)["streamId"] as number;
    expect(second).not.toBe(first);
    mum.send(JSON.stringify({ t: "cancel_stream_request", monitorId, requestId: "first" }));
    monitor.send(JSON.stringify({ t: "stream_preparing", streamId: first }));
    monitor.send(JSON.stringify({ t: "stream_started", streamId: first, video: null, audio: null }));
    const ready = waitFor(mum, "stream_ready");
    monitor.send(JSON.stringify({ t: "stream_started", streamId: second, video: { width: 640, height: 480, fps: 10 }, audio: null }));
    expect(await ready).toMatchObject({ streamId: second, requestId: "second" });
    expect(hub.relay.streamFor(monitorId)?.viewers.has(mumId)).toBe(true);
    monitor.close(); mum.close();
  });

  it("cancelling one pending viewer preserves the other viewer and the camera", async () => {
    const monitor = await open(monitorToken, "monitor");
    const mum = await open(mumToken, "parent");
    const dad = await open(dadToken, "parent");
    const asked = waitFor(monitor, "start_stream");
    mum.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true, requestId: "mum-1" }));
    const { streamId } = await asked;
    const joined = waitFor(dad, "stream_waiting");
    dad.send(JSON.stringify({ t: "request_stream", monitorId, video: true, audio: true, requestId: "dad-1" }));
    await joined;
    mum.send(JSON.stringify({ t: "cancel_stream_request", monitorId, requestId: "mum-1" }));
    const ready = waitFor(dad, "stream_ready");
    monitor.send(JSON.stringify({ t: "stream_started", streamId, video: { width: 640, height: 480, fps: 10 }, audio: null }));
    await ready;
    expect(hub.relay.streamFor(monitorId)?.viewers).toEqual(new Set([dadId]));
    monitor.close(); mum.close(); dad.close();
  });

});
