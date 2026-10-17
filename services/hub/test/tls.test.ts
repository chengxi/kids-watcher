import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ensureTlsMaterial } from "../src/tls.js";

const tmp = () => mkdtempSync(join(tmpdir(), "hub-tls-"));

describe("ensureTlsMaterial", () => {
  it("generates a certificate and key on first run", () => {
    const material = ensureTlsMaterial(tmp());
    expect(material.certPem).toContain("BEGIN CERTIFICATE");
    expect(material.keyPem).toContain("PRIVATE KEY");
  });

  it("returns a colon-separated uppercase hex sha-256 fingerprint", () => {
    const { fingerprintSha256 } = ensureTlsMaterial(tmp());
    expect(fingerprintSha256).toMatch(/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
  });

  it("is stable across calls for the same directory", () => {
    const dir = tmp();
    expect(ensureTlsMaterial(dir).fingerprintSha256).toBe(ensureTlsMaterial(dir).fingerprintSha256);
  });

  it("generates different material for different directories", () => {
    expect(ensureTlsMaterial(tmp()).fingerprintSha256).not.toBe(
      ensureTlsMaterial(tmp()).fingerprintSha256,
    );
  });

  it("writes the key with owner-only permissions", () => {
    const dir = tmp();
    ensureTlsMaterial(dir);
    const { mode } = statSync(join(dir, "hub.key.pem"));
    expect(mode & 0o077).toBe(0);
  });

  it("persists the certificate to disk", () => {
    const dir = tmp();
    const material = ensureTlsMaterial(dir);
    expect(readFileSync(join(dir, "hub.cert.pem"), "utf8")).toBe(material.certPem);
  });
});
