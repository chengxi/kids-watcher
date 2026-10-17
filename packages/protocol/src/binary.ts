export const FRAME_MAGIC = 0x4b;
export const FRAME_HEADER_BYTES = 12;

export const MediaKind = { Jpeg: 0x01, Pcm16: 0x02 } as const;
export type MediaKind = (typeof MediaKind)[keyof typeof MediaKind];

const KNOWN_KINDS: readonly number[] = Object.values(MediaKind);

export interface MediaFrame {
  readonly kind: MediaKind;
  readonly streamId: number;
  readonly timestampUs: bigint;
  readonly payload: Uint8Array;
}

export class FrameDecodeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FrameDecodeError";
  }
}

export function encodeMediaFrame(frame: MediaFrame): Uint8Array {
  if (!Number.isInteger(frame.streamId) || frame.streamId < 0 || frame.streamId > 0xffff) {
    throw new RangeError(`streamId out of uint16 range: ${frame.streamId}`);
  }
  if (frame.timestampUs < 0n || frame.timestampUs > 2n ** 64n - 1n) {
    throw new RangeError(`timestampUs out of uint64 range: ${frame.timestampUs}`);
  }
  const out = new Uint8Array(FRAME_HEADER_BYTES + frame.payload.length);
  const view = new DataView(out.buffer);
  view.setUint8(0, FRAME_MAGIC);
  view.setUint8(1, frame.kind);
  view.setUint16(2, frame.streamId, false);
  view.setBigUint64(4, frame.timestampUs, false);
  out.set(frame.payload, FRAME_HEADER_BYTES);
  return out;
}

export function decodeMediaFrame(buf: Uint8Array): MediaFrame {
  if (buf.length < FRAME_HEADER_BYTES) {
    throw new FrameDecodeError(`frame shorter than header: ${buf.length} bytes`);
  }
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (view.getUint8(0) !== FRAME_MAGIC) {
    throw new FrameDecodeError(`bad magic byte: 0x${view.getUint8(0).toString(16)}`);
  }
  const kind = view.getUint8(1);
  if (!KNOWN_KINDS.includes(kind)) {
    throw new FrameDecodeError(`unknown media kind: 0x${kind.toString(16)}`);
  }
  return {
    kind: kind as MediaKind,
    streamId: view.getUint16(2, false),
    timestampUs: view.getBigUint64(4, false),
    payload: buf.slice(FRAME_HEADER_BYTES),
  };
}
