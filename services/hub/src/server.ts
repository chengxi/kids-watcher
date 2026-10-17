import { createServer, type Server } from "node:https";
import { join } from "node:path";
import { DeviceRepo } from "./db/devices.js";
import { openDb } from "./db/open.js";
import { PairingRepo } from "./db/pairing.js";
import { RedemptionLimiter } from "./redemptionLimiter.js";
import type { HubDeps } from "./deps.js";
import { buildHttp } from "./http.js";
import { ensureTlsMaterial } from "./tls.js";
import { Relay } from "./ws/relay.js";
import { Registry } from "./ws/registry.js";
import { attachWs } from "./ws/server.js";
import { Watchdog } from "./ws/watchdog.js";

export interface Hub {
  readonly httpsUrl: string;
  readonly wsUrl: string;
  readonly registry: Registry;
  /**
   * Exposed so tests can drive a liveness sweep directly. Waiting out the real
   * 45-second timeout would make every test that involves MONITOR_LOST slow
   * enough that people stop running them.
   */
  readonly watchdog: Watchdog;
  /** Exposed so tests can see stream state the wire does not show. */
  readonly relay: Relay;
  readonly deps: HubDeps;
  close(): Promise<void>;
}

export function createDeps(dataDir: string): HubDeps {
  const db = openDb(join(dataDir, "hub.db"));
  return {
    db,
    devices: new DeviceRepo(db),
    pairing: new PairingRepo(db),
    limiter: new RedemptionLimiter(),
    tls: ensureTlsMaterial(dataDir),
  };
}

export async function startHub(
  deps: HubDeps,
  port: number,
  eventRetentionDays = 30,
): Promise<Hub> {
  const registry = new Registry();
  const app = buildHttp(deps, registry);
  await app.ready();

  const server: Server = createServer(
    { cert: deps.tls.certPem, key: deps.tls.keyPem },
    (req, res) => app.server.emit("request", req, res),
  );

  const relay = new Relay(registry);
  attachWs(server, deps, registry, relay);

  const watchdog = new Watchdog(deps, registry, eventRetentionDays);
  watchdog.start();

  await new Promise<void>((resolve) => server.listen(port, resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("server did not bind a port");
  const bound = address.port;

  return {
    httpsUrl: `https://127.0.0.1:${bound}`,
    wsUrl: `wss://127.0.0.1:${bound}`,
    registry,
    watchdog,
    relay,
    deps,
    close: async () => {
      watchdog.stop();
      await new Promise<void>((resolve) => server.close(() => resolve()));
      await app.close();
    },
  };
}
