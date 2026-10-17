import type { Server } from "node:https";
import {
  LIVENESS_TIMEOUT_MS,
  MonitorToHubSchema,
  ParentToHubSchema,
  parseMessage,
  RoleSchema,
} from "@kids-monitor/protocol";
import { WebSocketServer } from "ws";
import { bearerFrom } from "../auth.js";
import { MonitorLiveness } from "../db/monitorLiveness.js";
import { EventRepo, type EventRow } from "../db/events.js";
import { NoiseRepo } from "../db/noise.js";
import { MonitorConfigRepo } from "../db/monitorConfig.js";
import type { HubDeps } from "../deps.js";
import type { Relay } from "./relay.js";
import { Registry, type Connection } from "./registry.js";
import { send } from "./send.js";

export function fanOutEvent(registry: Registry, event: EventRow): void {
  for (const parent of registry.byRole("parent")) {
    const wanted =
      parent.subscribedMonitors === null || parent.subscribedMonitors.includes(event.monitorId);
    if (!wanted) continue;
    send(parent.socket, {
      t: "event",
      eventId: event.id,
      monitorId: event.monitorId,
      type: event.type,
      tsMs: event.tsMs,
      metadata: event.metadata,
    });
  }
}

// Largest single WebSocket message the hub accepts, of either kind. ws's own
// default is 100 MiB, which lets any paired device make the hub buffer that
// much per message. ws closes an oversized sender with 1009.
export const MAX_WS_MESSAGE_BYTES = 2 * 1024 * 1024;

export function attachWs(
  server: Server,
  deps: HubDeps,
  registry: Registry,
  relay: Relay,
): WebSocketServer {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_WS_MESSAGE_BYTES });

  server.on("upgrade", (request, socket, head) => {
    // socket.end() flushes the response before closing. socket.write() followed
    // by destroy() races: destroy can win, and the client then sees a bare
    // "socket disconnected" instead of the status code saying why it was
    // refused. That turns "your token was revoked" into "the network broke".
    const refuse = (status: string) => {
      socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\n\r\n`);
    };

    const url = new URL(request.url ?? "/", "https://placeholder");
    if (url.pathname !== "/ws") {
      refuse("404 Not Found");
      return;
    }

    const token = bearerFrom(request.headers.authorization);
    const device = token === null ? null : deps.devices.findByToken(token);
    if (device === null) {
      refuse("401 Unauthorized");
      return;
    }

    const requestedRole = RoleSchema.safeParse(url.searchParams.get("role"));
    if (!requestedRole.success || requestedRole.data !== device.role) {
      refuse("403 Forbidden");
      return;
    }

    wss.handleUpgrade(request, socket, head, (ws) => {
      const connection: Connection = {
        deviceId: device.id,
        role: device.role,
        socket: ws,
        lastSeenMs: Date.now(),
        batteryPct: null,
        subscribedMonitors: null,
      };
      if (device.role === "monitor") {
        const previousSeen = deps.devices.get(device.id)?.lastSeenMs;
        // A long hub outage may end before its first watchdog sweep. Preserve
        // that outage instead of silently losing the offline/online pair.
        if (previousSeen != null && connection.lastSeenMs - previousSeen > LIVENESS_TIMEOUT_MS) {
          const lost = new MonitorLiveness(deps.db).lost(device.id, previousSeen, previousSeen + LIVENESS_TIMEOUT_MS);
          if (lost !== null) fanOutEvent(registry, lost);
        }
      }
      registry.add(connection);
      deps.devices.touch(device.id, connection.lastSeenMs);
      if (device.role === "monitor") {
        const recovered = new MonitorLiveness(deps.db).recovered(device.id, connection.lastSeenMs);
        if (recovered !== null) fanOutEvent(registry, recovered);
      }

      // A monitor learns its detection thresholds before it does anything else.
      if (device.role === "monitor") {
        send(ws, { t: "config", config: new MonitorConfigRepo(deps.db).get(device.id) });
      }

      // ws emits 'error' for a protocol violation from the peer (an oversized
      // or malformed frame) and then closes the socket. An EventEmitter
      // 'error' with no listener throws, which would take the whole hub down
      // for one misbehaving device. The close handler below does the cleanup.
      ws.on("error", () => {});
      ws.on("close", () => {
        if (registry.get(device.id)?.socket === ws) registry.remove(device.id);
        // A viewer that walks out of wifi range must not leave the camera running.
        // The watchdog terminates a silent socket, so a phone that simply
        // vanished also ends up here rather than holding the camera for the
        // minutes TCP takes to notice.
        if (device.role === "parent") relay.dropViewer(device.id);
        if (device.role === "monitor") relay.onStreamStopped(device.id, null, "monitor disconnected");
      });
      ws.on("message", (data, isBinary) => {
        handleMessage(deps, registry, relay, connection, data as Buffer, isBinary);
      });
    });
  });

  return wss;
}

function handleMessage(
  deps: HubDeps,
  registry: Registry,
  relay: Relay,
  connection: Connection,
  data: Buffer,
  isBinary: boolean,
): void {
  const nowMs = Date.now();
  connection.lastSeenMs = nowMs;
  deps.devices.touch(connection.deviceId, nowMs);

  if (isBinary) {
    if (connection.role === "monitor") relay.forwardFrame(connection.deviceId, data);
    else relay.forwardTalkFrame(connection.deviceId, data);
    return;
  }

  try {
    if (connection.role === "monitor") {
      const msg = parseMessage(MonitorToHubSchema, data.toString("utf8"));
      if (msg.t === "talk_started") { relay.onTalkStarted(connection.deviceId, msg.talkId); return; }
      if (msg.t === "talk_stopped") { relay.onTalkStopped(connection.deviceId, msg.talkId, msg.reason); return; }
      if (msg.t === "noise_level") {
        // A faulty phone clock must not create a future strip of apparent coverage.
        if (msg.sample.endMs > nowMs + 30_000 || msg.sample.endMs < nowMs - 86_400_000 * 2) {
          throw new Error("noise sample clock is outside the accepted window");
        }
        new NoiseRepo(deps.db).append(connection.deviceId, msg.sample);
        return;
      }
      if (msg.t === "heartbeat") {
        connection.batteryPct = msg.batteryPct;
        connection.charging = msg.charging ?? null;
        connection.batteryTemperatureC = msg.batteryTemperatureC ?? null;
        connection.speakerVolumePct = msg.speakerVolumePct ?? null;
        send(connection.socket, { t: "heartbeat_ack", serverTsMs: nowMs });
        return;
      }
      if (msg.t === "event") {
        if (msg.type === "MONITOR_LOST" || msg.type === "MONITOR_ONLINE") {
          throw new Error("connection events are generated by the hub");
        }
        const row = new EventRepo(deps.db).append(
          connection.deviceId,
          msg.type,
          msg.tsMs,
          msg.metadata,
        );
        fanOutEvent(registry, row);
        return;
      }
      if (msg.t === "calibration_result") {
        new MonitorConfigRepo(deps.db).recordCalibration(
          connection.deviceId,
          msg.noiseFloorDb,
          nowMs,
        );
        return;
      }
      if (msg.t === "stream_preparing") {
        relay.onStreamPreparing(connection.deviceId, msg.streamId);
        return;
      }
      if (msg.t === "camera_result") { relay.onCameraResult(connection.deviceId, msg.streamId, msg.requestId, msg.reason); return; }
      if (msg.t === "stream_started") {
        relay.onStreamStarted(connection.deviceId, msg.streamId, msg.video, msg.audio);
        return;
      }
      if (msg.t === "stream_stopped") {
        relay.onStreamStopped(connection.deviceId, msg.streamId, msg.reason);
      }
      return;
    }

    const msg = parseMessage(ParentToHubSchema, data.toString("utf8"));
    if (msg.t === "select_camera") { relay.selectCamera(connection.deviceId, msg.streamId, msg.requestId, msg.facing); return; }
    if (msg.t === "start_talk") { relay.startTalk(connection.deviceId, msg.streamId, msg.requestId); return; }
    if (msg.t === "stop_talk") { relay.stopTalk(connection.deviceId, msg.streamId, msg.requestId); return; }
    if (msg.t === "heartbeat") {
      send(connection.socket, { t: "heartbeat_ack", serverTsMs: nowMs });
    } else if (msg.t === "subscribe") {
      connection.subscribedMonitors = msg.monitorIds;
    } else if (msg.t === "ack_event") {
      new EventRepo(deps.db).ack(msg.eventId, connection.deviceId, nowMs);
    } else if (msg.t === "request_stream") {
      const outcome = relay.request(msg.monitorId, connection.deviceId, msg.video, msg.audio, msg.requestId ?? null);
      if (outcome === "offline") {
        send(connection.socket, {
          t: "error",
          code: "monitor_offline",
          message: "monitor is not connected",
          monitorId: msg.monitorId,
          requestId: msg.requestId ?? null,
        });
      }
    } else if (msg.t === "cancel_stream_request") {
      relay.cancelRequest(msg.monitorId, connection.deviceId, msg.requestId ?? null);
    } else if (msg.t === "stop_stream") {
      const monitorId = relay.monitorForStream(msg.streamId);
      if (monitorId !== null) relay.release(monitorId, connection.deviceId);
    }
  } catch (error) {
    send(connection.socket, {
      t: "error",
      code: "bad_message",
      message: error instanceof Error ? error.message : "unparseable message",
    });
  }
}
