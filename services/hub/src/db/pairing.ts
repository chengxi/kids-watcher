import type { Database } from "better-sqlite3";
import { randomInt } from "node:crypto";
import { PAIRING_CODE_DIGITS, type Role } from "@kids-monitor/protocol";
import { hashToken } from "../auth.js";

export const PAIRING_TTL_MS = 300_000;

export type RedeemResult =
  | { readonly outcome: "redeemed"; readonly role: Role }
  | { readonly outcome: "wrong_role"; readonly role: Role }
  | { readonly outcome: "invalid" };

const CODE_SPACE = 10 ** PAIRING_CODE_DIGITS;

function generateCode(): string {
  return String(randomInt(0, CODE_SPACE)).padStart(PAIRING_CODE_DIGITS, "0");
}

export class PairingRepo {
  constructor(
    private readonly db: Database,
    private readonly nextCode: () => string = generateCode,
  ) {}

  issue(role: Role, nowMs: number = Date.now()): string {
    // Sweep first: code_hash is the primary key, so a long-dead row would
    // otherwise block its own code from ever being issued again. With 10^6
    // codes that stops being hypothetical.
    this.db.prepare("DELETE FROM pairing_codes WHERE expires_at_ms < ?").run(nowMs);

    for (let attempt = 0; attempt < 20; attempt++) {
      const code = this.nextCode();
      const hash = hashToken(code);
      const taken = this.db.prepare("SELECT 1 FROM pairing_codes WHERE code_hash = ?").get(hash);
      if (taken !== undefined) continue;
      this.db
        .prepare("INSERT INTO pairing_codes (code_hash, role, expires_at_ms) VALUES (?,?,?)")
        .run(hash, role, nowMs + PAIRING_TTL_MS);
      return code;
    }
    throw new Error("could not issue a unique pairing code");
  }

  /**
   * The role always comes from the stored row. `expectRole` is only what the
   * caller believes it typed into; on disagreement the code stays unused so it
   * can be entered on the other phone.
   */
  redeem(code: string, nowMs: number = Date.now(), expectRole: Role | null = null): RedeemResult {
    const hash = hashToken(code);
    const row = this.db
      .prepare("SELECT role, expires_at_ms, used_at_ms FROM pairing_codes WHERE code_hash = ?")
      .get(hash) as { role: Role; expires_at_ms: number; used_at_ms: number | null } | undefined;

    if (row === undefined) return { outcome: "invalid" };
    if (row.used_at_ms !== null) return { outcome: "invalid" };
    if (nowMs > row.expires_at_ms) return { outcome: "invalid" };
    if (expectRole !== null && row.role !== expectRole) {
      return { outcome: "wrong_role", role: row.role };
    }

    this.db.prepare("UPDATE pairing_codes SET used_at_ms = ? WHERE code_hash = ?").run(nowMs, hash);
    return { outcome: "redeemed", role: row.role };
  }
}
