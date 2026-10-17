import type { Role } from "@kids-monitor/protocol";
import type { WebSocket } from "ws";

export interface Connection {
  readonly deviceId: string;
  readonly role: Role;
  readonly socket: WebSocket;
  lastSeenMs: number;
  batteryPct: number | null;
  charging?: boolean | null;
  batteryTemperatureC?: number | null;
  speakerVolumePct?: number | null;
  subscribedMonitors: string[] | null;
}

export class Registry {
  private readonly connections = new Map<string, Connection>();

  add(connection: Connection): void {
    const existing = this.connections.get(connection.deviceId);
    if (existing !== undefined && existing.socket !== connection.socket) {
      existing.socket.close(4000, "replaced by a newer connection");
    }
    this.connections.set(connection.deviceId, connection);
  }

  remove(deviceId: string): void {
    this.connections.delete(deviceId);
  }

  get(deviceId: string): Connection | null {
    return this.connections.get(deviceId) ?? null;
  }

  all(): Connection[] {
    return [...this.connections.values()];
  }

  byRole(role: Role): Connection[] {
    return [...this.connections.values()].filter((c) => c.role === role);
  }

  isOnline(deviceId: string): boolean {
    return this.connections.has(deviceId);
  }
}
