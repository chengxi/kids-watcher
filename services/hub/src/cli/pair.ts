import { join } from "node:path";
import { pathToFileURL } from "node:url";
import qrcode from "qrcode-terminal";
import { RoleSchema, fingerprintCheck } from "@kids-monitor/protocol";
import { loadConfig } from "../config.js";
import { openDb } from "../db/open.js";
import { PairingRepo } from "../db/pairing.js";
import { resolvePublicAddress } from "../pairingAddress.js";
import { ensureTlsMaterial } from "../tls.js";

export function pairPrintout(args: {
  role: string;
  host: string;
  port: number;
  code: string;
  fingerprint: string;
}): string {
  const grouped = `${args.code.slice(0, 3)} ${args.code.slice(3)}`;
  return [
    `  host:        ${args.host}:${args.port}`,
    // Machine-readable: tools/pair-device.sh greps this line.
    `  code:        ${args.code}`,
    `  type it as:  ${grouped}`,
    `  fingerprint: ${args.fingerprint}`,
    `  check:       ${fingerprintCheck(args.fingerprint)}  <- the app shows these six characters`,
    "",
  ].join("\n");
}

async function main(): Promise<void> {
  const role = RoleSchema.parse(process.argv[2] ?? "parent");
  const config = loadConfig(process.env);

  // Must be the address the *phone* dials, which inside a container is neither
  // the detected interface nor HUB_PORT. Throws rather than printing a QR that
  // cannot work.
  const { host, port } = resolvePublicAddress(process.env, config.port);

  const tls = ensureTlsMaterial(config.dataDir);
  const code = new PairingRepo(openDb(join(config.dataDir, "hub.db"))).issue(role);

  const payload = JSON.stringify({
    v: 1,
    host,
    port,
    fingerprint: tls.fingerprintSha256,
    code,
    role,
  });

  console.log(`\nPairing a ${role} device. This code is single-use and expires in 5 minutes.\n`);
  qrcode.generate(payload, { small: true }, (qr: string) => {
    console.log(qr);
    console.log(pairPrintout({ role, host, port, code, fingerprint: tls.fingerprintSha256 }));
  });
}

// Only run the command when this file is the entry point, so the printout can
// be unit-tested without issuing a code.
if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
