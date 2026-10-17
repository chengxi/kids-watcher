import { EventEmitter } from "node:events";
import {
  HEARTBEAT_INTERVAL_MS,
  HubToParentSchema,
  parseMessage,
  type EventMetadata,
  type EventType,
  type HubToParent,
  type ParentToHub,
} from "@kids-monitor/protocol";
import { WebSocket } from "ws";

export interface ReceivedEvent {
  readonly eventId: number;
  readonly monitorId: string;
  readonly type: EventType;
  readonly tsMs: number;
  readonly metadata: EventMetadata;
}

export interface FakeParentOptions {
  readonly wsUrl: string;
  readonly token: string;
  readonly onEvent: (event: ReceivedEvent) => void;
}

/**
 * Stands in for the parent app.
 *
 * Its value is isolation: if this sees an event and the real app does not, the
 * problem is in the app rather than the monitor or the hub.
 */
export class FakeParent extends EventEmitter {
  private socket: WebSocket | null = null;
  private heartbeat: NodeJS.Timeout | null = null;
  private streamId: number | null = null;
  mediaFrames = 0;

  constructor(private readonly options: FakeParentOptions) {
    super();
  }

  async connect(): Promise<void> {
    const socket = new WebSocket(`${this.options.wsUrl}/ws?role=parent`, {
      rejectUnauthorized: false,
      headers: { Authorization: `Bearer ${this.options.token}` },
    });
    this.socket = socket;

    await new Promise<void>((resolve, reject) => {
      socket.once("open", () => resolve());
      socket.once("error", reject);
      // A revoked token is refused at the HTTP upgrade, which arrives here and
      // not as an "error". Without this the caller would hang instead of
      // learning the device is no longer allowed.
      socket.once("unexpected-response", (_req, res) =>
        reject(new Error(`hub refused the connection: HTTP ${res.statusCode}`)),
      );
    });

    this.send({ t: "hello", deviceName: "fake-parent", appVersion: "0.1.0" });
    this.send({ t: "subscribe", monitorIds: null });

    socket.on("message", (data, isBinary) => {
      if (isBinary) {
        this.mediaFrames++;
        return;
      }
      this.receive(data.toString());
    });

    this.heartbeat = setInterval(() => this.send({ t: "heartbeat" }), HEARTBEAT_INTERVAL_MS);
    this.heartbeat.unref();
  }

  private receive(raw: string): void {
    let msg: HubToParent;
    try {
      msg = parseMessage(HubToParentSchema, raw);
    } catch {
      return;
    }
    if (msg.t === "event") {
      const event: ReceivedEvent = {
        eventId: msg.eventId,
        monitorId: msg.monitorId,
        type: msg.type,
        tsMs: msg.tsMs,
        metadata: msg.metadata,
      };
      this.options.onEvent(event);
      this.emit("event", event);
      this.send({ t: "ack_event", eventId: msg.eventId });
    } else if (msg.t === "monitor_status") {
      this.emit("monitor_status", msg);
    } else if (msg.t === "stream_ready") {
      this.streamId = msg.streamId;
    }
  }

  watch(monitorId: string): void {
    this.send({ t: "request_stream", monitorId, video: true, audio: true });
  }

  stopWatching(): void {
    if (this.streamId !== null) this.send({ t: "stop_stream", streamId: this.streamId });
    this.streamId = null;
  }

  send(message: ParentToHub): void {
    this.socket?.send(JSON.stringify(message));
  }

  close(): void {
    if (this.heartbeat !== null) clearInterval(this.heartbeat);
    this.heartbeat = null;
    this.socket?.close();
    this.socket = null;
  }
}
