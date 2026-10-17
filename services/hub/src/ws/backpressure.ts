import { MediaKind } from "@kids-monitor/protocol";

// Bytes already queued to a viewer beyond which a new frame is dropped rather
// than queued. Live media is only useful live; a viewer on a weak link should
// see fewer frames, not an ever-later picture, and must not grow hub memory.
// Video goes first (a stale picture is worthless); audio is kept longer because
// a gap in sound is what a parent notices.
export const MAX_BUFFERED_BYTES: Readonly<Record<MediaKind, number>> = {
  [MediaKind.Jpeg]: 512 * 1024,
  [MediaKind.Pcm16]: 1024 * 1024,
};

// The largest media frame the relay passes on. A 640x480 JPEG at quality 60 is
// tens of kilobytes; anything near this is not a camera frame, and relaying it
// to every viewer would be the hub amplifying one bad sender.
export const MAX_MEDIA_FRAME_BYTES = 1024 * 1024;

export function shouldForward(kind: number, bufferedAmount: number): boolean {
  const limit = (MAX_BUFFERED_BYTES as Record<number, number | undefined>)[kind];
  return limit !== undefined && bufferedAmount <= limit;
}
