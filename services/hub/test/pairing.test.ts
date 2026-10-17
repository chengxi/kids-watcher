import { describe, expect, it } from "vitest";
import { hashToken } from "../src/auth.js";
import { openDb } from "../src/db/open.js";
import { PAIRING_TTL_MS, PairingRepo } from "../src/db/pairing.js";

describe("PairingRepo", () => {
  it("issues a six-digit code", () => {
    const code = new PairingRepo(openDb(":memory:")).issue("monitor", 0);
    expect(code).toMatch(/^\d{6}$/);
  });

  it("stores only the hash of the code", () => {
    const db = openDb(":memory:");
    const code = new PairingRepo(db).issue("monitor", 0);
    const row = db.prepare("SELECT code_hash FROM pairing_codes").get() as { code_hash: string };
    expect(row.code_hash).toBe(hashToken(code));
  });

  it("regenerates when a live code would collide", () => {
    // 10^6 is small enough that two live codes will eventually coincide, and
    // code_hash is the primary key: without this the second issue would throw.
    const queued = ["000001", "000001", "000002"];
    const repo = new PairingRepo(openDb(":memory:"), () => queued.shift() ?? "999999");
    expect(repo.issue("parent", 0)).toBe("000001");
    expect(repo.issue("parent", 0)).toBe("000002");
  });

  it("sweeps expired codes when issuing, so a dead code cannot block its reissue", () => {
    const db = openDb(":memory:");
    const repo = new PairingRepo(db, () => "000001");
    repo.issue("parent", 0);
    // Well past the 5-minute TTL of the first code.
    expect(repo.issue("parent", PAIRING_TTL_MS + 1)).toBe("000001");
    expect(
      (db.prepare("SELECT COUNT(*) AS n FROM pairing_codes").get() as { n: number }).n,
    ).toBe(1);
  });

  it("redeems a valid code once and returns its role", () => {
    const repo = new PairingRepo(openDb(":memory:"));
    const code = repo.issue("parent", 0);
    expect(repo.redeem(code, 1000)).toEqual({ outcome: "redeemed", role: "parent" });
    expect(repo.redeem(code, 2000)).toEqual({ outcome: "invalid" });
  });

  it("refuses a code issued for another role without consuming it", () => {
    const repo = new PairingRepo(openDb(":memory:"));
    const code = repo.issue("monitor", 0);
    expect(repo.redeem(code, 1000, "parent")).toEqual({ outcome: "wrong_role", role: "monitor" });
    // Still usable on the right phone: a mistyped device must not burn the code.
    expect(repo.redeem(code, 2000, "monitor")).toEqual({ outcome: "redeemed", role: "monitor" });
  });

  it("refuses a code past its 5 minute lifetime", () => {
    const repo = new PairingRepo(openDb(":memory:"));
    const code = repo.issue("monitor", 0);
    expect(repo.redeem(code, PAIRING_TTL_MS + 1)).toEqual({ outcome: "invalid" });
  });

  it("refuses a code that was never issued", () => {
    expect(new PairingRepo(openDb(":memory:")).redeem("000000", 0)).toEqual({ outcome: "invalid" });
  });
});
