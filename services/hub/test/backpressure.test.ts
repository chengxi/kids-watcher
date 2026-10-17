import { describe, expect, it } from "vitest";
import { MediaKind } from "@kids-monitor/protocol";
import { MAX_BUFFERED_BYTES, shouldForward } from "../src/ws/backpressure.js";

describe("relay backpressure", () => {
  it("forwards to a viewer who is keeping up", () => {
    expect(shouldForward(MediaKind.Jpeg, 0)).toBe(true);
    expect(shouldForward(MediaKind.Pcm16, 0)).toBe(true);
  });

  it("drops video for a slow viewer before it drops audio", () => {
    const between = MAX_BUFFERED_BYTES[MediaKind.Jpeg] + 1;
    expect(shouldForward(MediaKind.Jpeg, between)).toBe(false);
    expect(shouldForward(MediaKind.Pcm16, between)).toBe(true);
  });

  it("drops audio too once a viewer is far behind", () => {
    expect(shouldForward(MediaKind.Pcm16, MAX_BUFFERED_BYTES[MediaKind.Pcm16] + 1)).toBe(false);
  });

  it("drops a frame of a kind it does not know", () => {
    expect(shouldForward(0x7f, 0)).toBe(false);
  });
});
