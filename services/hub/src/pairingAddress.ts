import { networkInterfaces } from "node:os";

export interface PublicAddress {
  readonly host: string;
  readonly port: number;
}

/**
 * The address a phone must dial to reach this hub.
 *
 * Auto-detection is wrong inside a container: `networkInterfaces()` reports the
 * Docker bridge address (172.x.x.x) and `HUB_PORT` is the *in-container* port,
 * not the published one. A QR built from those values sends the phone to an
 * address that exists only inside the container, and pairing simply times out.
 *
 * So an explicit `HUB_PUBLIC_HOST` / `HUB_PUBLIC_PORT` always wins. Detection
 * is only a convenience for running the hub directly with `node`.
 */
export function resolvePublicAddress(
  env: NodeJS.ProcessEnv,
  fallbackPort: number,
  detect: () => string | null = detectLanAddress,
): PublicAddress {
  const rawPort = env.HUB_PUBLIC_PORT;
  let port = fallbackPort;
  if (rawPort !== undefined && rawPort !== "") {
    const parsed = Number(rawPort);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
      throw new Error(`HUB_PUBLIC_PORT must be an integer between 1 and 65535, got ${rawPort}`);
    }
    port = parsed;
  }

  const explicitHost = env.HUB_PUBLIC_HOST;
  if (explicitHost !== undefined && explicitHost.trim() !== "") {
    return { host: explicitHost.trim(), port };
  }

  const detected = detect();
  if (detected === null) {
    throw new Error(
      "could not work out a reachable address for this hub. Set HUB_PUBLIC_HOST " +
        "(and HUB_PUBLIC_PORT if the published port differs from HUB_PORT).",
    );
  }
  return { host: detected, port };
}

/** Returns null rather than a private container address it cannot vouch for. */
export function detectLanAddress(): string | null {
  const candidates: string[] = [];
  for (const addrs of Object.values(networkInterfaces())) {
    for (const addr of addrs ?? []) {
      if (addr.family === "IPv4" && !addr.internal) candidates.push(addr.address);
    }
  }
  // Docker's default bridge networks live in 172.16.0.0/12. An address there is
  // almost certainly container-internal and useless to a phone on the wifi.
  const usable = candidates.filter((a) => !isDockerBridge(a));
  return usable[0] ?? null;
}

function isDockerBridge(address: string): boolean {
  const parts = address.split(".").map(Number);
  const first = parts[0];
  const second = parts[1];
  if (first === undefined || second === undefined) return false;
  return first === 172 && second >= 16 && second <= 31;
}
