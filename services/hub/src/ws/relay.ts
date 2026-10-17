import { FRAME_HEADER_BYTES, FRAME_MAGIC } from "@kids-monitor/protocol";
import type { AudioFormat, VideoFormat, CameraFacing } from "@kids-monitor/protocol";
import type { Registry } from "./registry.js";
import { MAX_MEDIA_FRAME_BYTES, shouldForward } from "./backpressure.js";
import { send } from "./send.js";

export interface Stream {
  readonly streamId: number;
  readonly monitorId: string;
  readonly viewers: Set<string>;
  video: VideoFormat | null;
  audio: AudioFormat | null;
  ready: boolean;
  preparing: boolean;
  readonly requestIds: Map<string, string | null>;
  cameraRequest?: { parentId: string; requestId: string; timeout: ReturnType<typeof setTimeout>; waitingViewers: Set<string> };
}

interface Talk {
  readonly streamId: number;
  readonly talkId: number;
  readonly monitorId: string;
  readonly parentId: string;
  readonly requestId: string;
  ready: boolean;
  timeout: ReturnType<typeof setTimeout> | null;
}

// One stream per monitor, shared by every viewer. Encoding a second stream on a
// six-year-old phone is not worth it, so a second parent joins the first.
export class Relay {
  private readonly streams = new Map<string, Stream>();
  private nextStreamId = 1;
  private readonly talks = new Map<string, Talk>();

  private allocateId(): number {
    while (true) {
      const id = this.nextStreamId;
      this.nextStreamId = (id % 65535) + 1;
      if ([...this.streams.values()].some(s => s.streamId === id) ||
          [...this.talks.values()].some(t => t.talkId === id)) continue;
      return id;
    }
  }

  constructor(private readonly registry: Registry) {}

  streamFor(monitorId: string): Stream | null {
    return this.streams.get(monitorId) ?? null;
  }

  monitorForStream(streamId: number): string | null {
    for (const [monitorId, stream] of this.streams) {
      if (stream.streamId === streamId) return monitorId;
    }
    return null;
  }

  request(
    monitorId: string,
    parentId: string,
    video: boolean,
    audio: boolean,
    requestId: string | null = null,
  ): "started" | "joined" | "offline" {
    const monitor = this.registry.get(monitorId);
    // Only a monitor streams. A parent's id must not route start_stream to a parent.
    if (monitor === null || monitor.role !== "monitor") return "offline";

    const existing = this.streams.get(monitorId);
    if (existing !== undefined) {
      existing.viewers.add(parentId);
      existing.requestIds.set(parentId, requestId);
      if (existing.cameraRequest) {
        existing.cameraRequest.waitingViewers.add(parentId);
        this.sendWaiting(existing, parentId);
        return "joined";
      }
      const parent = this.registry.get(parentId);
      if (existing.ready && parent !== null) {
        send(parent.socket, {
          t: "stream_ready",
          streamId: existing.streamId,
          monitorId,
          requestId,
          video: existing.video,
          audio: existing.audio,
        });
      }
      if (!existing.ready) this.sendWaiting(existing, parentId);
      return "joined";
    }

    const streamId = this.allocateId();
    this.streams.set(monitorId, {
      streamId,
      monitorId,
      viewers: new Set([parentId]),
      video: null,
      audio: null,
      ready: false,
      preparing: false,
      requestIds: new Map([[parentId, requestId]]),
    });
    this.sendWaiting(this.streams.get(monitorId)!, parentId);
    send(monitor.socket, { t: "start_stream", streamId, video, audio });
    return "started";
  }

  private sendWaiting(stream: Stream, parentId: string): void {
    const parent = this.registry.get(parentId);
    if (parent === null) return;
    send(parent.socket, { t: "stream_waiting", streamId: stream.streamId,
      monitorId: stream.monitorId, requestId: stream.requestIds.get(parentId) ?? null,
      phase: stream.preparing ? "preparing_video" : "contacting_monitor" });
  }

  onStreamPreparing(monitorId: string, streamId: number): void {
    const stream = this.streams.get(monitorId);
    if (stream === undefined || stream.streamId !== streamId || stream.ready) return;
    stream.preparing = true;
    for (const parentId of stream.viewers) this.sendWaiting(stream, parentId);
  }

  cancelRequest(monitorId: string, parentId: string, requestId: string | null): void {
    const stream = this.streams.get(monitorId);
    if (stream === undefined || stream.requestIds.get(parentId) !== requestId) return;
    this.release(monitorId, parentId);
  }

  onStreamStarted(
    monitorId: string,
    streamId: number,
    video: VideoFormat | null,
    audio: AudioFormat | null,
  ): void {
    const stream = this.streams.get(monitorId);
    // A reply to a stream that has since been replaced says nothing about this one.
    if (stream === undefined || stream.streamId !== streamId) return;
    stream.video = video;
    // Reannouncements update rotation for current viewers and parents joining later.
    stream.audio = audio;
    stream.ready = true;
    for (const viewerId of stream.viewers) {
      if (video === null && stream.cameraRequest?.waitingViewers.has(viewerId)) {
        this.sendWaiting(stream, viewerId);
        continue;
      }
      stream.cameraRequest?.waitingViewers.delete(viewerId);
      const parent = this.registry.get(viewerId);
      if (parent === null) continue;
      send(parent.socket, { t: "stream_ready", streamId: stream.streamId, monitorId, requestId: stream.requestIds.get(viewerId) ?? null, video, audio });
    }
  }

  /**
   * [streamId] null means "whatever is running": the monitor's socket closed,
   * which ends any stream. A monitor's own stream_stopped names its stream, and
   * a stale one must not end the stream that replaced it while its camera runs.
   */
  onStreamStopped(monitorId: string, streamId: number | null, reason: string): void {
    const stream = this.streams.get(monitorId);
    if (stream === undefined) return;
    if (streamId !== null && stream.streamId !== streamId) return;
    this.endTalk(monitorId, reason);
    if (stream.cameraRequest) clearTimeout(stream.cameraRequest.timeout);
    this.streams.delete(monitorId);
    for (const viewerId of stream.viewers) {
      const parent = this.registry.get(viewerId);
      if (parent === null) continue;
      send(parent.socket, { t: "stream_ended", streamId: stream.streamId, reason });
    }
  }

  release(monitorId: string, parentId: string): void {
    const stream = this.streams.get(monitorId);
    if (stream === undefined) return;
    if (this.talks.get(monitorId)?.parentId === parentId) this.endTalk(monitorId, "Talk stopped");
    stream.viewers.delete(parentId);
    stream.cameraRequest?.waitingViewers.delete(parentId);
    stream.requestIds.delete(parentId);
    if (stream.viewers.size > 0) return;

    const monitor = this.registry.get(monitorId);
    if (monitor !== null) send(monitor.socket, { t: "stop_stream", streamId: stream.streamId });
    if (stream.cameraRequest) clearTimeout(stream.cameraRequest.timeout);
    this.streams.delete(monitorId);
  }

  dropViewer(parentId: string): void {
    for (const monitorId of [...this.streams.keys()]) this.release(monitorId, parentId);
  }

  selectCamera(parentId: string, streamId: number, requestId: string, facing: CameraFacing): void {
    const monitorId = this.monitorForStream(streamId);
    const stream = monitorId === null ? undefined : this.streams.get(monitorId);
    const parent = this.registry.get(parentId);
    const refuse = (reason: string) => {
      if (parent?.role === "parent") send(parent.socket, { t: "camera_result", streamId, requestId, reason });
    };
    if (!stream?.ready || !stream.viewers.has(parentId) || parent?.role !== "parent") {
      refuse("Open a live view before switching cameras"); return;
    }
    if (stream.cameraRequest) { refuse("A camera switch is already in progress"); return; }
    if (!stream.video?.availableCameras?.includes(facing) || !stream.video.facing) {
      refuse("Camera switching is unavailable. Update the monitor app if needed."); return;
    }
    const monitor = this.registry.get(stream.monitorId);
    if (monitor?.role !== "monitor") { refuse("Monitor disconnected"); return; }
    const timeout = setTimeout(() => {
      if (stream.cameraRequest?.requestId !== requestId) return;
      this.finishCameraWaiters(stream);
      delete stream.cameraRequest;
      refuse("The monitor did not finish switching cameras. Retry video.");
    }, 30_000);
    timeout.unref();
    stream.preparing = true;
    stream.cameraRequest = { parentId, requestId, timeout, waitingViewers: new Set() };
    send(monitor.socket, { t: "select_camera", streamId, requestId, facing });
  }

  private finishCameraWaiters(stream: Stream): void {
    for (const viewerId of stream.cameraRequest?.waitingViewers ?? []) {
      const parent = this.registry.get(viewerId);
      if (parent?.role === "parent" && stream.viewers.has(viewerId)) send(parent.socket, {
        t: "stream_ready", streamId: stream.streamId, monitorId: stream.monitorId,
        requestId: stream.requestIds.get(viewerId) ?? null, video: stream.video, audio: stream.audio,
      });
    }
  }

  onCameraResult(monitorId: string, streamId: number, requestId: string, reason: string | null): void {
    const stream = this.streams.get(monitorId);
    if (!stream || stream.streamId !== streamId || stream.cameraRequest?.requestId !== requestId) return;
    const pending = stream.cameraRequest;
    clearTimeout(pending.timeout);
    this.finishCameraWaiters(stream);
    delete stream.cameraRequest;
    const parent = this.registry.get(pending.parentId);
    if (parent?.role === "parent" && stream.viewers.has(pending.parentId)) {
      send(parent.socket, { t: "camera_result", streamId, requestId, reason });
    }
  }

  startTalk(parentId: string, streamId: number, requestId: string): void {
    const monitorId = this.monitorForStream(streamId);
    const stream = monitorId === null ? undefined : this.streams.get(monitorId);
    const parent = this.registry.get(parentId);
    const refuse = (reason: string) => {
      if (parent !== null) send(parent.socket, { t: "talk_state", streamId, requestId, talkId: null, ready: false, reason });
    };
    if (stream === undefined || !stream.ready || !stream.viewers.has(parentId) || parent?.role !== "parent") {
      refuse("Open a live view before talking");
      return;
    }
    const monitor = this.registry.get(stream.monitorId);
    if (monitor?.role !== "monitor") { refuse("Monitor disconnected"); return; }
    const old = this.talks.get(stream.monitorId);
    if (old !== undefined) {
      if (old.parentId !== parentId) { refuse("Another parent is talking"); return; }
      if (old.requestId === requestId) return;
      this.endTalk(stream.monitorId, "Talk replaced");
    }
    const talk: Talk = { streamId, talkId: this.allocateId(), monitorId: stream.monitorId,
      parentId, requestId, ready: false, timeout: null };
    this.talks.set(stream.monitorId, talk);
    talk.timeout = setTimeout(() => this.endTalk(stream.monitorId, "Monitor speaker did not respond. Update the monitor app and retry."), 5_000);
    talk.timeout.unref();
    send(monitor.socket, { t: "start_talk", streamId, talkId: talk.talkId });
  }

  stopTalk(parentId: string, streamId: number, requestId: string): void {
    const monitorId = this.monitorForStream(streamId);
    if (monitorId === null) return;
    const talk = this.talks.get(monitorId);
    if (talk?.parentId === parentId && talk.requestId === requestId) this.endTalk(monitorId, "Talk stopped");
  }

  onTalkStarted(monitorId: string, talkId: number): void {
    const talk = this.talks.get(monitorId);
    if (talk === undefined || talk.talkId !== talkId) return;
    if (talk.timeout !== null) clearTimeout(talk.timeout);
    talk.timeout = null;
    talk.ready = true;
    const parent = this.registry.get(talk.parentId);
    if (parent !== null) send(parent.socket, { t: "talk_state", streamId: talk.streamId,
      requestId: talk.requestId, talkId, ready: true, reason: null });
  }

  onTalkStopped(monitorId: string, talkId: number, reason: string): void {
    if (this.talks.get(monitorId)?.talkId === talkId) this.endTalk(monitorId, reason);
  }

  private endTalk(monitorId: string, reason: string): void {
    const talk = this.talks.get(monitorId);
    if (talk === undefined) return;
    this.talks.delete(monitorId);
    if (talk.timeout !== null) clearTimeout(talk.timeout);
    const monitor = this.registry.get(monitorId);
    if (monitor !== null) send(monitor.socket, { t: "stop_talk", talkId: talk.talkId });
    const parent = this.registry.get(talk.parentId);
    if (parent !== null) send(parent.socket, { t: "talk_state", streamId: talk.streamId,
      requestId: talk.requestId, talkId: talk.talkId, ready: false, reason });
  }

  /** Only the selected speaker's PCM goes upstream; no parent video or stale audio. */
  forwardTalkFrame(parentId: string, frame: Buffer): void {
    if (frame.length <= FRAME_HEADER_BYTES || frame.length > FRAME_HEADER_BYTES + 3200 ||
        frame[0] !== FRAME_MAGIC || frame[1] !== 2 || (frame.length - FRAME_HEADER_BYTES) % 2 !== 0) return;
    const talkId = frame.readUInt16BE(2);
    const talk = [...this.talks.values()].find(t => t.parentId === parentId && t.talkId === talkId && t.ready);
    if (talk === undefined) return;
    const monitor = this.registry.get(talk.monitorId);
    if (monitor?.role !== "monitor" || monitor.socket.bufferedAmount > 16 * 1024) return;
    monitor.socket.send(frame, { binary: true });
  }

  forwardFrame(monitorId: string, frame: Buffer): void {
    const stream = this.streams.get(monitorId);
    if (stream === undefined) return;
    // Read the header in place rather than decoding: the payload is opaque to
    // the hub and copying it per viewer per frame is wasted work.
    if (frame.length < FRAME_HEADER_BYTES || frame[0] !== FRAME_MAGIC) return;
    if (frame.length > FRAME_HEADER_BYTES + MAX_MEDIA_FRAME_BYTES) return;
    if (frame.readUInt16BE(2) !== stream.streamId) return;
    const kind = frame[1]!;

    for (const viewerId of stream.viewers) {
      const parent = this.registry.get(viewerId);
      if (parent === null) continue;
      if (!shouldForward(kind, parent.socket.bufferedAmount)) continue;
      parent.socket.send(frame, { binary: true });
    }
  }
}
