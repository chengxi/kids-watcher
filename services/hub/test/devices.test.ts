import { describe, expect, it } from "vitest";
import { bearerFrom, hashToken, mintToken } from "../src/auth.js";
import { DeviceRepo } from "../src/db/devices.js";
import { openDb } from "../src/db/open.js";

const repo = () => new DeviceRepo(openDb(":memory:"));

describe("auth primitives", () => {
  it("mints 256 bits of entropy as base64url", () => {
    const token = mintToken();
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(mintToken()).not.toBe(token);
  });

  it("hashes to lowercase hex sha-256", () => {
    expect(hashToken("abc")).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });

  it("extracts a bearer token", () => {
    expect(bearerFrom("Bearer xyz")).toBe("xyz");
    expect(bearerFrom("bearer xyz")).toBe("xyz");
    expect(bearerFrom("Basic xyz")).toBeNull();
    expect(bearerFrom(undefined)).toBeNull();
  });
});

describe("DeviceRepo", () => {
  it("stores only the hash, never the token", () => {
    const db = openDb(":memory:");
    const token = mintToken();
    new DeviceRepo(db).create("monitor", "Nursery", token);
    const row = db.prepare("SELECT token_hash FROM devices").get() as { token_hash: string };
    expect(row.token_hash).toBe(hashToken(token));
    expect(row.token_hash).not.toContain(token);
  });

  it("finds a device by its token", () => {
    const r = repo();
    const token = mintToken();
    const created = r.create("monitor", "Nursery", token);
    expect(r.findByToken(token)?.id).toBe(created.id);
  });

  it("returns null for an unknown token", () => {
    expect(repo().findByToken(mintToken())).toBeNull();
  });

  it("returns null for a revoked device", () => {
    const r = repo();
    const token = mintToken();
    const created = r.create("parent", "Dad", token);
    r.revoke(created.id);
    expect(r.findByToken(token)).toBeNull();
  });

  it("lists devices by role", () => {
    const r = repo();
    r.create("monitor", "Nursery", mintToken());
    r.create("parent", "Mum", mintToken());
    r.create("parent", "Dad", mintToken());
    expect(r.listByRole("parent").map((d) => d.name).sort()).toEqual(["Dad", "Mum"]);
  });

  it("records last-seen", () => {
    const r = repo();
    const created = r.create("monitor", "Nursery", mintToken());
    expect(created.lastSeenMs).toBeNull();
    r.touch(created.id, 1757000000000);
    expect(r.get(created.id)?.lastSeenMs).toBe(1757000000000);
  });
});
