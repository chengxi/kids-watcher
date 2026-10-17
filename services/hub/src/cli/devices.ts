import { join } from "node:path";
import { loadConfig } from "../config.js";
import { DeviceRepo } from "../db/devices.js";
import { openDb } from "../db/open.js";

// Usage:
//   node dist/cli/devices.js list
//   node dist/cli/devices.js revoke <device-id>

const config = loadConfig(process.env);
const db = openDb(join(config.dataDir, "hub.db"));
const devices = new DeviceRepo(db);

const command = process.argv[2] ?? "list";

if (command === "list") {
  const rows = [...devices.listByRole("monitor"), ...devices.listByRole("parent")];
  if (rows.length === 0) {
    console.log("no devices paired yet");
  } else {
    console.table(
      rows.map((d) => ({
        id: d.id,
        role: d.role,
        name: d.name,
        lastSeen: d.lastSeenMs === null ? "never" : new Date(d.lastSeenMs).toISOString(),
      })),
    );
  }
} else if (command === "revoke") {
  const id = process.argv[3];
  if (id === undefined) {
    console.error("usage: devices.js revoke <device-id>");
    process.exit(2);
  }
  const device = devices.get(id);
  if (device === null) {
    console.error(`no device with id ${id}`);
    process.exit(1);
  }
  devices.revoke(id);
  console.log(`revoked ${device.role} "${device.name}" (${id})`);
  console.log("its token no longer works; its socket drops on the next connection attempt.");
} else {
  console.error(`unknown command: ${command}\nusage: devices.js [list|revoke <device-id>]`);
  process.exit(2);
}
