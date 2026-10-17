import { execSync } from "node:child_process";
import { readFileSync, statSync } from "node:fs";

// A PEM header on its own is not key material -- documentation and this
// scanner's own source both contain those strings legitimately. Require a
// real base64 body (40+ chars on one line) so we flag actual keys, not prose.
const PEM_BODY = "(?:[\\s\\S]*?^[A-Za-z0-9+/=]{40,}$)";

const CONTENT_PATTERNS = [
  [new RegExp(`-----BEGIN (?:RSA |EC |OPENSSH |)PRIVATE KEY-----${PEM_BODY}`, "m"), "private key"],
  [new RegExp(`-----BEGIN CERTIFICATE-----${PEM_BODY}`, "m"), "certificate"],
  [/\bAIza[0-9A-Za-z_-]{35}\b/, "Google API key"],
  [/\bghp_[0-9A-Za-z]{36}\b/, "GitHub personal access token"],
  [/\bsk-[A-Za-z0-9]{32,}\b/, "API secret key"],
];

const RISKY_EXT = /\.(?:pem|key|crt|p12|pfx|jks|keystore)$/i;
const ALLOWED = new Set([
  ".env.example",
  "tools/scan-secrets.mjs",
  "tools/scan-secrets.test.mjs",
]);

export function scanContent(path, text) {
  if (ALLOWED.has(path)) return [];
  const findings = [];
  if (RISKY_EXT.test(path)) findings.push(`${path}: key material by file extension`);
  for (const [pattern, label] of CONTENT_PATTERNS) {
    if (pattern.test(text)) findings.push(`${path}: ${label}`);
  }
  return findings;
}

export function scanRepo() {
  const files = execSync("git ls-files", { encoding: "utf8" }).split("\n").filter(Boolean);
  const findings = [];
  for (const file of files) {
    let text = "";
    try {
      if (statSync(file).size < 2_000_000) text = readFileSync(file, "utf8");
    } catch {
      continue;
    }
    findings.push(...scanContent(file, text));
  }
  return findings;
}

if (import.meta.filename === process.argv[1]) {
  const findings = scanRepo();
  for (const f of findings) console.error(`SECRET: ${f}`);
  console.error(findings.length === 0 ? "secret scan clean" : `${findings.length} finding(s)`);
  process.exit(findings.length === 0 ? 0 : 1);
}
