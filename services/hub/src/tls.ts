import { X509Certificate } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import selfsigned from "selfsigned";

export interface TlsMaterial {
  readonly certPem: string;
  readonly keyPem: string;
  readonly fingerprintSha256: string;
}

const CERT_FILE = "hub.cert.pem";
const KEY_FILE = "hub.key.pem";

export function ensureTlsMaterial(dataDir: string): TlsMaterial {
  mkdirSync(dataDir, { recursive: true });
  const certPath = join(dataDir, CERT_FILE);
  const keyPath = join(dataDir, KEY_FILE);

  if (!existsSync(certPath) || !existsSync(keyPath)) {
    const generated = selfsigned.generate([{ name: "commonName", value: "kids-monitor-hub" }], {
      keySize: 2048,
      days: 3650,
      algorithm: "sha256",
      extensions: [
        { name: "basicConstraints", cA: false },
        { name: "keyUsage", digitalSignature: true, keyEncipherment: true },
        { name: "extKeyUsage", serverAuth: true },
      ],
    });
    writeFileSync(certPath, generated.cert, { mode: 0o644 });
    writeFileSync(keyPath, generated.private, { mode: 0o600 });
  }

  const certPem = readFileSync(certPath, "utf8");
  const keyPem = readFileSync(keyPath, "utf8");
  return { certPem, keyPem, fingerprintSha256: new X509Certificate(certPem).fingerprint256 };
}
