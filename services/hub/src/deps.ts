import type { Database } from "better-sqlite3";
import type { DeviceRepo } from "./db/devices.js";
import type { PairingRepo } from "./db/pairing.js";
import type { RedemptionLimiter } from "./redemptionLimiter.js";
import type { TlsMaterial } from "./tls.js";

// HubDeps lives here rather than in http.ts because http.ts imports from ws/,
// and ws/ needs HubDeps. A shared leaf module is what stops that being a cycle.
export interface HubDeps {
  readonly db: Database;
  readonly devices: DeviceRepo;
  readonly pairing: PairingRepo;
  readonly limiter: RedemptionLimiter;
  readonly tls: TlsMaterial;
}
