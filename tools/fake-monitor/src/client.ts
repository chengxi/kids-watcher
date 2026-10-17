import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import {
  DEFAULT_MONITOR_CONFIG,
  HEARTBEAT_INTERVAL_MS,
  HubToMonitorSchema,
  parseMessage,
  type EventMetadata,
  type EventType,
  type MonitorConfig,
  type MonitorToHub,
} from "@kids-monitor/protocol";
import { WebSocket } from "ws";

export interface FakeMonitorOptions {
  readonly wsUrl: string;
  readonly token: string;
  readonly name: string;
}

export async function pairDevice(
  httpsUrl: string,
  code: string,
  deviceName: string,
): Promise<{ deviceId: string; token: string }> {
  const res = await fetch(`${httpsUrl}/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName }),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(`pairing failed (${res.status}): ${body.error ?? "unknown"}`);
  }
  return (await res.json()) as { deviceId: string; token: string };
}

export class FakeMonitor extends EventEmitter {
  private socket: WebSocket | null = null;
  private heartbeat: NodeJS.Timeout | null = null;
  config: MonitorConfig = DEFAULT_MONITOR_CONFIG;

  constructor(private readonly options: FakeMonitorOptions) {
    super();
  }

  async connect(): Promise<void> {
    const socket = new WebSocket(`${this.options.wsUrl}/ws?role=monitor`, {
      rejectUnauthorized: false,
      headers: { Authorization: `Bearer ${this.options.token}` },
    });
    this.socket = socket;

    await new Promise<void>((resolve, reject) => {
      socket.once("open", () => resolve());
      socket.once("error", reject);
    });

    this.send({ t: "hello", deviceName: this.options.name, appVersion: "fake-0.1.0" });
    socket.on("message", (data, isBinary) => {
      if (isBinary) return;
      this.receive(data.toString());
    });

    this.heartbeat = setInterval(() => {
      this.send({ t: "heartbeat", batteryPct: 76, thermal: "none" });
    }, HEARTBEAT_INTERVAL_MS);
    this.heartbeat.unref();
  }

  private receive(raw: string): void {
    let msg;
    try {
      msg = parseMessage(HubToMonitorSchema, raw);
    } catch {
      return;
    }
    if (msg.t === "config") {
      this.config = msg.config;
      this.emit("config", msg.config);
    } else if (msg.t === "recalibrate") {
      const windowMs = msg.windowMs;
      // A real monitor samples the room for windowMs. This one answers promptly
      // with a plausible floor so tests do not wait 30 seconds.
      setTimeout(() => {
        this.send({
          t: "calibration_result",
          noiseFloorDb: -57.5,
          spreadDb: 3.2,
          sampledMs: windowMs,
        });
      }, 20);
      this.emit("recalibrate", windowMs);
    } else if (msg.t === "start_stream") {
      this.emit("start_stream", msg);
    } else if (msg.t === "stop_stream") {
      this.emit("stop_stream", msg);
    }
  }

  emitEvent(type: EventType, metadata: EventMetadata = {}): void {
    this.send({ t: "event", clientEventId: randomUUID(), type, tsMs: Date.now(), metadata });
  }

  send(message: MonitorToHub): void {
    this.socket?.send(JSON.stringify(message));
  }

  sendBinary(frame: Uint8Array): void {
    this.socket?.send(frame, { binary: true });
  }

  close(): void {
    if (this.heartbeat !== null) clearInterval(this.heartbeat);
    this.heartbeat = null;
    this.socket?.close();
    this.socket = null;
  }
}
