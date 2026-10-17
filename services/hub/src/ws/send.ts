import type { HubToMonitor, HubToParent } from "@kids-monitor/protocol";
import type { WebSocket } from "ws";

export function send(socket: WebSocket, message: HubToMonitor | HubToParent): void {
  socket.send(JSON.stringify(message));
}
