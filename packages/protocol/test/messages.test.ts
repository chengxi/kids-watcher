import { describe, expect, it } from "vitest";
import {
  DEFAULT_MONITOR_CONFIG,
  HubToParentSchema,
  MAX_SAMPLE_RATE,
  MAX_VIDEO_DIMENSION,
  MessageParseError,
  MIN_SAMPLE_RATE,
  MonitorToHubSchema,
  ParentToHubSchema,
  parseMessage,
} from "../src/index.js";

describe("control messages", () => {
  it("parses a monitor event", () => {
    const raw = JSON.stringify({
      t: "event",
      clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
      type: "LOUD_NOISE",
      tsMs: 1757000000000,
      metadata: { peakDb: -18.5, durationMs: 2100 },
    });
    const msg = parseMessage(MonitorToHubSchema, raw);
    expect(msg.t).toBe("event");
    if (msg.t !== "event") throw new Error("narrowing failed");
    expect(msg.metadata.peakDb).toBe(-18.5);
  });

  it("defaults event metadata to an empty object", () => {
    const raw = JSON.stringify({
      t: "event",
      clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
      type: "MONITOR_STARTED",
      tsMs: 1757000000000,
    });
    const msg = parseMessage(MonitorToHubSchema, raw);
    if (msg.t !== "event") throw new Error("narrowing failed");
    expect(msg.metadata).toEqual({});
  });

  it("rejects an unknown event type", () => {
    const raw = JSON.stringify({
      t: "event",
      clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
      type: "ALIEN_INVASION",
      tsMs: 1,
    });
    expect(() => parseMessage(MonitorToHubSchema, raw)).toThrow(MessageParseError);
  });

  it("rejects malformed JSON", () => {
    expect(() => parseMessage(MonitorToHubSchema, "{not json")).toThrow(MessageParseError);
  });

  it("rejects a message with no discriminator", () => {
    expect(() => parseMessage(MonitorToHubSchema, "{}")).toThrow(MessageParseError);
  });

  it("rejects a parent message sent on the monitor schema", () => {
    const raw = JSON.stringify({ t: "ack_event", eventId: 3 });
    expect(() => parseMessage(MonitorToHubSchema, raw)).toThrow(MessageParseError);
  });

  it("parses subscribe with an explicit null meaning all monitors", () => {
    const msg = parseMessage(ParentToHubSchema, JSON.stringify({ t: "subscribe", monitorIds: null }));
    if (msg.t !== "subscribe") throw new Error("narrowing failed");
    expect(msg.monitorIds).toBeNull();
  });

  it("parses a hub event fanned out to a parent", () => {
    const raw = JSON.stringify({
      t: "event",
      eventId: 42,
      monitorId: "m1",
      type: "MONITOR_LOST",
      tsMs: 1757000000000,
      metadata: {},
    });
    const msg = parseMessage(HubToParentSchema, raw);
    if (msg.t !== "event") throw new Error("narrowing failed");
    expect(msg.eventId).toBe(42);
  });

  it("ships defaults that match the spec", () => {
    expect(DEFAULT_MONITOR_CONFIG).toEqual({
      thresholdDb: 12,
      sustainMs: 1500,
      cooldownS: 60,
      noiseFloorDb: -50,
      motionEnabled: false,
      motionThreshold: 12,
      calibrationWindowMs: 30000,
    });
  });
});

describe("event metadata accepts both encodings of 'not measured'", () => {
  const base = {
    t: "event",
    clientEventId: "8f1b0a3c-0e7a-4c1e-9a2b-6f6a1d2e3c4d",
    type: "MONITOR_STARTED",
    tsMs: 1,
  };

  it("accepts metadata with the fields absent, as TypeScript clients send it", () => {
    const msg = parseMessage(MonitorToHubSchema, JSON.stringify({ ...base, metadata: {} }));
    if (msg.t !== "event") throw new Error("narrowing failed");
    expect(msg.metadata.peakDb ?? null).toBeNull();
  });

  it("accepts metadata with explicit nulls, as the Kotlin client sends it", () => {
    // Kotlin encodes with explicitNulls, because batteryPct/video/audio in the
    // same direction are nullable and must carry the key. Rejecting null here
    // would make every monitor event from the Android app fail.
    const raw = JSON.stringify({ ...base, metadata: { peakDb: null, durationMs: null } });
    const msg = parseMessage(MonitorToHubSchema, raw);
    if (msg.t !== "event") throw new Error("narrowing failed");
    expect(msg.metadata.peakDb ?? null).toBeNull();
  });

  it("still rejects a non-numeric peak", () => {
    const raw = JSON.stringify({ ...base, metadata: { peakDb: "loud" } });
    expect(() => parseMessage(MonitorToHubSchema, raw)).toThrow();
  });
});

describe("video format rotation", () => {
  const started = (video: unknown) => ({ t: "stream_started", streamId: 1, video, audio: null });

  it("defaults rotationDegrees to 0 when a sender omits it", () => {
    const parsed = MonitorToHubSchema.parse(started({ width: 640, height: 480, fps: 10 }));
    expect(parsed.t === "stream_started" && parsed.video?.rotationDegrees).toBe(0);
  });

  it("accepts the four right angles", () => {
    for (const rotationDegrees of [0, 90, 180, 270]) {
      expect(MonitorToHubSchema.safeParse(
        started({ width: 640, height: 480, fps: 10, rotationDegrees }),
      ).success).toBe(true);
    }
  });

  it("rejects any other rotation", () => {
    expect(MonitorToHubSchema.safeParse(
      started({ width: 640, height: 480, fps: 10, rotationDegrees: 45 }),
    ).success).toBe(false);
  });
});

// A viewer builds an AudioTrack and a Bitmap from these numbers. The hub is the
// one place every stream_started passes through, so it refuses formats no real
// monitor produces rather than relaying them to every parent.
describe("stream format bounds", () => {
  const started = (video: unknown, audio: unknown) => ({ t: "stream_started", streamId: 1, video, audio });
  const ok = (video: unknown, audio: unknown) => MonitorToHubSchema.safeParse(started(video, audio)).success;
  const vga = { width: 640, height: 480, fps: 10 };

  it("accepts the monitor's real formats", () => {
    expect(ok(vga, { sampleRate: 16000, channels: 1 })).toBe(true);
    expect(ok({ width: MAX_VIDEO_DIMENSION, height: MAX_VIDEO_DIMENSION, fps: 10 }, null)).toBe(true);
    expect(ok(null, { sampleRate: MIN_SAMPLE_RATE, channels: 1 })).toBe(true);
    expect(ok(null, { sampleRate: MAX_SAMPLE_RATE, channels: 2 })).toBe(true);
  });

  it("rejects a picture larger than any camera stream", () => {
    expect(ok({ ...vga, width: MAX_VIDEO_DIMENSION + 1 }, null)).toBe(false);
    expect(ok({ ...vga, height: 30000 }, null)).toBe(false);
  });

  it("rejects a sample rate outside what a speaker can play", () => {
    expect(ok(null, { sampleRate: MIN_SAMPLE_RATE - 1, channels: 1 })).toBe(false);
    expect(ok(null, { sampleRate: MAX_SAMPLE_RATE + 1, channels: 1 })).toBe(false);
  });

  it("applies the same bounds to stream_ready sent to parents", () => {
    const ready = (audio: unknown) =>
      HubToParentSchema.safeParse({ t: "stream_ready", streamId: 1, monitorId: "m", video: null, audio }).success;
    expect(ready({ sampleRate: 16000, channels: 1 })).toBe(true);
    expect(ready({ sampleRate: 1, channels: 1 })).toBe(false);
  });
});
