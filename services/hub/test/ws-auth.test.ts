import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { mintToken } from "../src/auth.js";
import { createDeps, startHub, type Hub } from "../src/server.js";

let hub: Hub;

function connect(token: string | null, role: string): Promise<WebSocket> {
  const ws = new WebSocket(`${hub.wsUrl}/ws?role=${role}`, {
    rejectUnauthorized: false,
    headers: token === null ? {} : { Authorization: `Bearer ${token}` },
  });
  return new Promise((resolve, reject) => {
    ws.once("open", () => resolve(ws));
    ws.once("error", reject);
    ws.once("unexpected-response", (_req, res) => reject(new Error(`http ${res.statusCode}`)));
  });
}

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "hub-ws-"))), 0);
});
afterEach(async () => hub.close());

describe("websocket authentication", () => {
  it("accepts a monitor with a valid token", async () => {
    const token = mintToken();
    const device = hub.deps.devices.create("monitor", "Nursery", token);
    const ws = await connect(token, "monitor");
    expect(hub.registry.isOnline(device.id)).toBe(true);
    ws.close();
  });

  it("rejects a connection with no token", async () => {
    await expect(connect(null, "monitor")).rejects.toThrow(/401/);
  });

  it("rejects an unknown token", async () => {
    await expect(connect(mintToken(), "monitor")).rejects.toThrow(/401/);
  });

  it("rejects a token whose device was revoked", async () => {
    const token = mintToken();
    const device = hub.deps.devices.create("parent", "Dad", token);
    hub.deps.devices.revoke(device.id);
    await expect(connect(token, "parent")).rejects.toThrow(/401/);
  });

  it("rejects a role that does not match the device", async () => {
    const token = mintToken();
    hub.deps.devices.create("monitor", "Nursery", token);
    await expect(connect(token, "parent")).rejects.toThrow(/403/);
  });

  it("answers a heartbeat and records last-seen", async () => {
    const token = mintToken();
    const device = hub.deps.devices.create("monitor", "Nursery", token);
    const ws = await connect(token, "monitor");
    const reply = new Promise<string>((resolve) => {
      ws.on("message", (d) => {
        const msg = JSON.parse(d.toString()) as { t: string };
        if (msg.t === "heartbeat_ack") resolve(d.toString());
      });
    });
    ws.send(JSON.stringify({ t: "heartbeat", batteryPct: 88, thermal: "none" }));
    const msg = JSON.parse(await reply) as { t: string };
    expect(msg.t).toBe("heartbeat_ack");
    expect(hub.deps.devices.get(device.id)?.lastSeenMs).not.toBeNull();
    expect(hub.registry.get(device.id)?.batteryPct).toBe(88);
    ws.close();
  });

  it("replies with an error message rather than closing on a malformed frame", async () => {
    const token = mintToken();
    hub.deps.devices.create("monitor", "Nursery", token);
    const ws = await connect(token, "monitor");
    const reply = new Promise<string>((resolve) => {
      ws.on("message", (d) => {
        const msg = JSON.parse(d.toString()) as { t: string };
        if (msg.t === "error") resolve(d.toString());
      });
    });
    ws.send("{not json");
    const msg = JSON.parse(await reply) as { t: string; code: string };
    expect(msg.code).toBe("bad_message");
    expect(ws.readyState).toBe(WebSocket.OPEN);
    ws.close();
  });

  it("drops the older socket when a device reconnects", async () => {
    const token = mintToken();
    const device = hub.deps.devices.create("monitor", "Nursery", token);
    const first = await connect(token, "monitor");
    const closed = new Promise<void>((resolve) => first.once("close", () => resolve()));
    const second = await connect(token, "monitor");
    await closed;
    expect(hub.registry.isOnline(device.id)).toBe(true);
    expect(hub.registry.get(device.id)?.socket).not.toBe(first);
    second.close();
  });
});
