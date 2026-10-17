import { describe, expect, it } from "vitest";
import {
  FRAME_HEADER_BYTES,
  FrameDecodeError,
  MediaKind,
  decodeMediaFrame,
  encodeMediaFrame,
} from "../src/binary.js";

const frame = {
  kind: MediaKind.Jpeg,
  streamId: 7,
  timestampUs: 1_234_567_890n,
  payload: new Uint8Array([1, 2, 3, 4]),
};

describe("media frame codec", () => {
  it("round-trips a frame", () => {
    expect(decodeMediaFrame(encodeMediaFrame(frame))).toEqual(frame);
  });

  it("writes a 12-byte header before the payload", () => {
    const encoded = encodeMediaFrame(frame);
    expect(encoded.length).toBe(FRAME_HEADER_BYTES + 4);
    expect(encoded[0]).toBe(0x4b);
    expect(encoded[1]).toBe(MediaKind.Jpeg);
  });

  it("round-trips an empty payload", () => {
    const empty = { ...frame, payload: new Uint8Array(0) };
    expect(decodeMediaFrame(encodeMediaFrame(empty))).toEqual(empty);
  });

  it("round-trips the largest representable timestamp", () => {
    const big = { ...frame, timestampUs: 2n ** 64n - 1n };
    expect(decodeMediaFrame(encodeMediaFrame(big)).timestampUs).toBe(2n ** 64n - 1n);
  });

  it("rejects a bad magic byte", () => {
    const bad = encodeMediaFrame(frame);
    bad[0] = 0x00;
    expect(() => decodeMediaFrame(bad)).toThrow(FrameDecodeError);
  });

  it("rejects a buffer shorter than the header", () => {
    expect(() => decodeMediaFrame(new Uint8Array(11))).toThrow(FrameDecodeError);
  });

  it("rejects an unknown media kind", () => {
    const bad = encodeMediaFrame(frame);
    bad[1] = 0x99;
    expect(() => decodeMediaFrame(bad)).toThrow(FrameDecodeError);
  });

  it("rejects a streamId outside uint16", () => {
    expect(() => encodeMediaFrame({ ...frame, streamId: 70_000 })).toThrow(RangeError);
  });
});
