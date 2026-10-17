import { describe, expect, it } from "vitest";
import { FINGERPRINT_CHECK_BYTES, PAIRING_CODE_DIGITS, fingerprintCheck } from "../src/pairing.js";

const FP =
  "D8:52:26:69:99:BF:2B:29:52:E9:A3:81:B6:4D:56:E6:" +
  "01:A4:47:43:B6:84:05:03:FF:E0:11:5A:27:92:27:E3";

describe("pairing constants", () => {
  it("fixes the code length and the check length", () => {
    expect(PAIRING_CODE_DIGITS).toBe(6);
    expect(FINGERPRINT_CHECK_BYTES).toBe(3);
  });
});

describe("fingerprintCheck", () => {
  it("returns the first three bytes, uppercase and colon-separated", () => {
    expect(fingerprintCheck(FP)).toBe("D8:52:26");
  });

  it("accepts the same fingerprint written without colons or in lowercase", () => {
    expect(fingerprintCheck(FP.replace(/:/g, "").toLowerCase())).toBe("D8:52:26");
  });

  it("refuses anything that is not a SHA-256 fingerprint", () => {
    expect(() => fingerprintCheck("D8:52:26")).toThrow(/SHA-256/);
  });
});
