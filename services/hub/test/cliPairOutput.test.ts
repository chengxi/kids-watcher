import { describe, expect, it } from "vitest";
import { pairPrintout } from "../src/cli/pair.js";

const FP =
  "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
  "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3";

describe("pairPrintout", () => {
  const out = pairPrintout({
    role: "parent",
    host: "192.168.1.100",
    port: 8444,
    code: "123456",
    fingerprint: FP,
  });

  it("keeps a machine-readable code line, which tools/pair-device.sh greps", () => {
    const line = out.split("\n").find((l) => l.trim().startsWith("code:"));
    expect(line?.trim().split(/\s+/)[1]).toBe("123456");
  });

  it("shows the code grouped for typing", () => {
    expect(out).toContain("123 456");
  });

  it("shows the six characters the app will ask about", () => {
    expect(out).toContain("D8:52:26");
  });

  it("still shows the full fingerprint and the address", () => {
    expect(out).toContain(FP);
    expect(out).toContain("192.168.1.100:8444");
  });
});
