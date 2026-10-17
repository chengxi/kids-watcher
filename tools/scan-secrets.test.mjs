import { describe, expect, it } from "vitest";
import { scanContent } from "./scan-secrets.mjs";

describe("scanContent", () => {
  it("returns nothing for ordinary source", () => {
    expect(scanContent("src/a.ts", "export const x = 1;\n")).toEqual([]);
  });

  it("flags a PEM private key with a real body", () => {
    const body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDb3Xk2vQz9Kk1a";
    const text = `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----\n`;
    expect(scanContent("hub/key.txt", text)).toEqual([
      "hub/key.txt: private key",
    ]);
  });

  it("does not flag a bare PEM header quoted in documentation", () => {
    const doc = "Keys look like this:\n\n    -----BEGIN CERTIFICATE-----\n\nNever commit one.";
    expect(scanContent("docs/guides/setup-hub.md", doc)).toEqual([]);
  });

  it("flags a file whose extension always holds key material", () => {
    expect(scanContent("certs/hub.pem", "")).toEqual([
      "certs/hub.pem: key material by file extension",
    ]);
  });

  it("does not flag the example env file", () => {
    expect(scanContent(".env.example", "HUB_PORT=8443\n")).toEqual([]);
  });
});
