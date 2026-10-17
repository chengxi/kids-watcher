import { readFileSync } from "node:fs";
import { MediaKind, encodeMediaFrame } from "@kids-monitor/protocol";
import type { FakeMonitor } from "./client.js";

/** What stream_started advertises. Matches the real monitor's default. */
export const FAKE_VIDEO = { width: 640, height: 480, fps: 10, rotationDegrees: 0 } as const;

// Resolved relative to this module, so it works from both src/ and dist/.
const FIXTURES = new URL("../fixtures/", import.meta.url);
const FRAMES: readonly Uint8Array[] = Array.from(
  { length: 10 },
  (_, i) => new Uint8Array(readFileSync(new URL(`frame-${String(i).padStart(2, "0")}.jpg`, FIXTURES))),
);

export function fixtureJpeg(index: number): Uint8Array {
  return FRAMES[((index % FRAMES.length) + FRAMES.length) % FRAMES.length]!;
}

/** Width and height from a JPEG's start-of-frame segment, or null if there is none. */
export function jpegSize(jpeg: Uint8Array): { width: number; height: number } | null {
  let i = 2; // skip SOI
  while (i + 9 < jpeg.length) {
    if (jpeg[i] !== 0xff) return null;
    const marker = jpeg[i + 1]!;
    const length = (jpeg[i + 2]! << 8) | jpeg[i + 3]!;
    if (marker === 0xc0 || marker === 0xc2) {
      return { height: (jpeg[i + 5]! << 8) | jpeg[i + 6]!, width: (jpeg[i + 7]! << 8) | jpeg[i + 8]! };
    }
    i += 2 + length;
  }
  return null;
}

export function syntheticPcm(sampleRate: number, ms: number, freqHz: number): Uint8Array {
  const sampleCount = Math.round((sampleRate * ms) / 1000);
  const out = new Uint8Array(sampleCount * 2);
  const view = new DataView(out.buffer);
  for (let i = 0; i < sampleCount; i++) {
    const value = Math.round(Math.sin((2 * Math.PI * freqHz * i) / sampleRate) * 12000);
    view.setInt16(i * 2, value, true);
  }
  return out;
}

export class MediaPusher {
  private videoTimer: NodeJS.Timeout | null = null;
  private audioTimer: NodeJS.Timeout | null = null;
  private startedAtMs = 0;
  private seed = 0;

  start(monitor: FakeMonitor, streamId: number, video: boolean, audio: boolean): void {
    this.stop();
    this.startedAtMs = Date.now();

    if (video) {
      this.videoTimer = setInterval(() => {
        monitor.sendBinary(encodeMediaFrame({
          kind: MediaKind.Jpeg,
          streamId,
          timestampUs: BigInt((Date.now() - this.startedAtMs) * 1000),
          payload: fixtureJpeg(this.seed++),
        }));
      }, 1000 / FAKE_VIDEO.fps);
      this.videoTimer.unref();
    }

    if (audio) {
      this.audioTimer = setInterval(() => {
        monitor.sendBinary(encodeMediaFrame({
          kind: MediaKind.Pcm16,
          streamId,
          timestampUs: BigInt((Date.now() - this.startedAtMs) * 1000),
          payload: syntheticPcm(16000, 20, 440),
        }));
      }, 20);
      this.audioTimer.unref();
    }
  }

  stop(): void {
    if (this.videoTimer !== null) clearInterval(this.videoTimer);
    if (this.audioTimer !== null) clearInterval(this.audioTimer);
    this.videoTimer = null;
    this.audioTimer = null;
  }
}
