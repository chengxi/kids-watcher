import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, describe, expect, it } from "vitest";
import { DeviceRepo } from "../src/db/devices.js";
import { openDb } from "../src/db/open.js";
import { PairingRepo } from "../src/db/pairing.js";
import { MAX_FAILURES, RedemptionLimiter } from "../src/redemptionLimiter.js";
import { buildHttp } from "../src/http.js";
import type { HubDeps } from "../src/deps.js";
import { ensureTlsMaterial } from "../src/tls.js";
import { Registry } from "../src/ws/registry.js";

let deps: HubDeps;

beforeEach(() => {
  const db = openDb(":memory:");
  deps = {
    db,
    devices: new DeviceRepo(db),
    pairing: new PairingRepo(db),
    limiter: new RedemptionLimiter(),
    tls: ensureTlsMaterial(mkdtempSync(join(tmpdir(), "hub-http-"))),
  };
});

describe("POST /pair", () => {
  it("exchanges a valid code for a device id and token", async () => {
    const app = buildHttp(deps, new Registry());
    const code = deps.pairing.issue("monitor", Date.now());
    const res = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code, deviceName: "Nursery" },
    });
    expect(res.statusCode).toBe(201);
    const body = res.json() as { deviceId: string; token: string };
    expect(body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(deps.devices.findByToken(body.token)?.id).toBe(body.deviceId);
  });

  it("takes the role from the code, not the request body", async () => {
    const app = buildHttp(deps, new Registry());
    const code = deps.pairing.issue("parent", Date.now());
    const res = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code, deviceName: "Mum", role: "monitor" },
    });
    const body = res.json() as { token: string };
    expect(deps.devices.findByToken(body.token)?.role).toBe("parent");
  });

  it("rejects an unknown code with 403", async () => {
    const app = buildHttp(deps, new Registry());
    const res = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code: "000000", deviceName: "Nursery" },
    });
    expect(res.statusCode).toBe(403);
  });

  it("rejects a malformed body with 400", async () => {
    const app = buildHttp(deps, new Registry());
    const res = await app.inject({ method: "POST", url: "/pair", payload: { code: "ZZZZZZZZ" } });
    expect(res.statusCode).toBe(400);
  });

  it("serves healthz without authentication", async () => {
    const res = await buildHttp(deps, new Registry()).inject({ method: "GET", url: "/healthz" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ ok: true });
  });
  it("accepts a code typed with a space, as it is printed", async () => {
    const app = buildHttp(deps, new Registry());
    const code = deps.pairing.issue("parent", Date.now());
    const spaced = `${code.slice(0, 3)} ${code.slice(3)}`;
    const res = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code: spaced, deviceName: "Phone", expectRole: "parent" },
    });
    expect(res.statusCode).toBe(201);
  });

  it("rejects a code that is not six digits", async () => {
    const app = buildHttp(deps, new Registry());
    for (const code of ["12345", "1234567", "12345A"]) {
      const res = await app.inject({
        method: "POST",
        url: "/pair",
        payload: { code, deviceName: "Phone" },
      });
      expect(res.statusCode).toBe(400);
    }
  });

  it("refuses a role mismatch with 409 and leaves the code usable", async () => {
    const app = buildHttp(deps, new Registry());
    const code = deps.pairing.issue("monitor", Date.now());

    const wrong = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code, deviceName: "Phone", expectRole: "parent" },
    });
    expect(wrong.statusCode).toBe(409);
    expect(wrong.json()).toMatchObject({ error: "role_mismatch", role: "monitor" });

    const right = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code, deviceName: "Nursery", expectRole: "monitor" },
    });
    expect(right.statusCode).toBe(201);
  });
  it("locks redemption after repeated wrong codes and says how long to wait", async () => {
    const app = buildHttp(deps, new Registry());
    for (let i = 0; i < MAX_FAILURES; i++) {
      const res = await app.inject({
        method: "POST",
        url: "/pair",
        payload: { code: "000000", deviceName: "Guess" },
      });
      expect(res.statusCode).toBe(403);
    }

    const code = deps.pairing.issue("parent", Date.now());
    const locked = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code, deviceName: "Phone", expectRole: "parent" },
    });
    expect(locked.statusCode).toBe(429);
    expect((locked.json() as { retryAfterMs: number }).retryAfterMs).toBeGreaterThan(0);
    expect(locked.headers["retry-after"]).toBeDefined();

    // The lockout must not have consumed the real code.
    deps.limiter.recordSuccess();
    const after = await app.inject({
      method: "POST",
      url: "/pair",
      payload: { code, deviceName: "Phone", expectRole: "parent" },
    });
    expect(after.statusCode).toBe(201);
  });

  it("does not count a role mismatch as a guess", async () => {
    const app = buildHttp(deps, new Registry());
    for (let i = 0; i < MAX_FAILURES + 2; i++) {
      const code = deps.pairing.issue("monitor", Date.now());
      const res = await app.inject({
        method: "POST",
        url: "/pair",
        payload: { code, deviceName: "Phone", expectRole: "parent" },
      });
      expect(res.statusCode).toBe(409);
    }
    expect(deps.limiter.retryAfterMs(Date.now())).toBe(0);
  });
});
