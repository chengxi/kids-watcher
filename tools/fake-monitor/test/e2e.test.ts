import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { MediaKind, decodeMediaFrame } from "@kids-monitor/protocol";
import { createDeps, startHub, type Hub } from "../../../services/hub/src/server.js";
import { FakeMonitor, pairDevice } from "../src/client.js";
import { FAKE_VIDEO, MediaPusher, fixtureJpeg, jpegSize, syntheticPcm } from "../src/media.js";

let hub: Hub;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;

beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "e2e-"))), 0);
});
afterEach(async () => hub.close());

describe("synthetic media", () => {
  it("streams real JPEG files", () => {
    const jpeg = fixtureJpeg(1);
    expect([jpeg[0], jpeg[1]]).toEqual([0xff, 0xd8]); // SOI
    expect([jpeg[jpeg.length - 2], jpeg[jpeg.length - 1]]).toEqual([0xff, 0xd9]); // EOI
  });

  // The old synthetic frame had a valid header and no image, which a real
  // decoder rejects. These must carry the dimensions stream_started advertises.
  it("frames have the advertised dimensions", () => {
    expect(jpegSize(fixtureJpeg(0))).toEqual({ width: FAKE_VIDEO.width, height: FAKE_VIDEO.height });
  });

  it("successive frames differ so a frozen picture is visible", () => {
    expect(fixtureJpeg(1)).not.toEqual(fixtureJpeg(2));
  });

  it("produces the right number of PCM bytes", () => {
    expect(syntheticPcm(16000, 20, 440).length).toBe(640);
  });
});

describe("end to end: pair, alert, watch", () => {
  it("carries a monitor from pairing to a watched stream", async () => {
    // 1. Both devices pair with codes the hub operator issued.
    const monitorCode = hub.deps.pairing.issue("monitor", Date.now());
    const parentCode = hub.deps.pairing.issue("parent", Date.now());
    const monitorCreds = await pairDevice(hub.httpsUrl, monitorCode, "Fake Nursery");
    const parentCreds = await pairDevice(hub.httpsUrl, parentCode, "Fake Parent");

    // 2. The monitor connects and receives its config.
    const monitor = new FakeMonitor({
      wsUrl: hub.wsUrl, token: monitorCreds.token, name: "Fake Nursery",
    });
    await monitor.connect();

    // 3. The parent connects and subscribes to every monitor.
    const parent = new WebSocket(`${hub.wsUrl}/ws?role=parent`, {
      rejectUnauthorized: false,
      headers: { Authorization: `Bearer ${parentCreds.token}` },
    });
    await new Promise<void>((resolve, reject) => {
      parent.once("open", () => resolve());
      parent.once("error", reject);
    });
    parent.send(JSON.stringify({ t: "subscribe", monitorIds: null }));

    const jsonMessages: Record<string, unknown>[] = [];
    const binaryFrames: Buffer[] = [];
    parent.on("message", (data, isBinary) => {
      if (isBinary) binaryFrames.push(data as Buffer);
      else jsonMessages.push(JSON.parse(data.toString()) as Record<string, unknown>);
    });

    // 4. The monitor detects a noise; the parent is told.
    monitor.emitEvent("LOUD_NOISE", { peakDb: -9.5, durationMs: 2200 });
    await new Promise((r) => setTimeout(r, 150));
    expect(jsonMessages.find((m) => m["t"] === "event")?.["type"]).toBe("LOUD_NOISE");

    // 5. The parent asks to watch; the monitor starts pushing frames.
    const pusher = new MediaPusher();
    monitor.on("start_stream", (msg: { streamId: number; video: boolean; audio: boolean }) => {
      monitor.send({
        t: "stream_started",
        streamId: msg.streamId,
        video: msg.video ? FAKE_VIDEO : null,
        audio: msg.audio ? { sampleRate: 16000, channels: 1 } : null,
      });
      pusher.start(monitor, msg.streamId, msg.video, msg.audio);
    });

    parent.send(JSON.stringify({
      t: "request_stream", monitorId: monitorCreds.deviceId, video: true, audio: true,
    }));
    await new Promise((r) => setTimeout(r, 600));

    // 6. Real frames arrived, and they decode.
    expect(jsonMessages.some((m) => m["t"] === "stream_ready")).toBe(true);
    expect(binaryFrames.length).toBeGreaterThan(0);
    const kinds = new Set(binaryFrames.map((f) => decodeMediaFrame(new Uint8Array(f)).kind));
    expect(kinds.has(MediaKind.Jpeg)).toBe(true);
    expect(kinds.has(MediaKind.Pcm16)).toBe(true);

    // 7. The backfill cursor covers what the parent already saw.
    const res = await fetch(`${hub.httpsUrl}/events?since=0`, {
      headers: { Authorization: `Bearer ${parentCreds.token}` },
    });
    const body = (await res.json()) as { events: { type: string }[] };
    expect(body.events.map((e) => e.type)).toContain("LOUD_NOISE");

    pusher.stop();
    parent.close();
    monitor.close();
  }, 15_000);
});
