import Fastify, { type FastifyInstance } from "fastify";
import { MonitorConfigSchema, RoleSchema } from "@kids-monitor/protocol";
import { z } from "zod";
import { bearerFrom, mintToken } from "./auth.js";
import type { DeviceRow } from "./db/devices.js";
import { EventRepo } from "./db/events.js";
import { NoiseRepo } from "./db/noise.js";
import { MonitorConfigRepo } from "./db/monitorConfig.js";
import type { HubDeps } from "./deps.js";
import { send } from "./ws/send.js";
import type { Registry } from "./ws/registry.js";

declare module "fastify" {
  interface FastifyRequest {
    device?: DeviceRow;
  }
}

const EventsQuery = z.object({
  since: z.coerce.number().int().nonnegative().default(0),
  limit: z.coerce.number().int().min(1).max(500).default(200),
});

const PairBody = z.object({
  // Length is checked after whitespace is stripped, in the route.
  code: z.string().max(32),
  deviceName: z.string().min(1).max(64),
  // What the caller believes it typed into. The role still comes from the code.
  expectRole: RoleSchema.optional(),
});

const PAIRING_CODE = /^\d{6}$/;
const NoiseQuery = z.object({
  fromMs: z.coerce.number().int().nonnegative().optional(),
});

export function buildHttp(deps: HubDeps, registry: Registry): FastifyInstance {
  const app = Fastify({ logger: false });

  app.get("/healthz", async () => ({ ok: true }));

  app.post("/pair", async (request, reply) => {
    const parsed = PairBody.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: "invalid_body" });

    // The hub prints "123 456"; typing it back with the space must work.
    const code = parsed.data.code.replace(/\s/g, "");
    if (!PAIRING_CODE.test(code)) return reply.code(400).send({ error: "invalid_body" });

    // Six digits are only safe because guessing is slowed down here.
    const nowMs = Date.now();
    const waitMs = deps.limiter.retryAfterMs(nowMs);
    if (waitMs > 0) {
      return reply
        .code(429)
        .header("retry-after", String(Math.ceil(waitMs / 1000)))
        .send({ error: "too_many_attempts", retryAfterMs: waitMs });
    }

    // The role comes from the code, never from the caller.
    const result = deps.pairing.redeem(code, nowMs, parsed.data.expectRole ?? null);
    if (result.outcome === "wrong_role") {
      // A valid code on the wrong phone is a mistake, not a guess, so it does
      // not count toward the lockout. Deliberately not consumed either: the
      // operator walks to the other phone and types the same code there.
      return reply.code(409).send({ error: "role_mismatch", role: result.role });
    }
    if (result.outcome === "invalid") {
      deps.limiter.recordFailure(nowMs);
      return reply.code(403).send({ error: "invalid_code" });
    }
    deps.limiter.recordSuccess();

    const token = mintToken();
    const device = deps.devices.create(result.role, parsed.data.deviceName, token);
    return reply.code(201).send({ deviceId: device.id, token });
  });

  // Everything below this hook requires a device token. /healthz and /pair are
  // the only routes that may be reached without one; /pair authenticates with
  // the single-use pairing code instead.
  app.addHook("preHandler", async (request, reply) => {
    const path = request.url.split("?")[0] ?? "";
    if (path === "/healthz" || path === "/pair") return;
    const token = bearerFrom(request.headers.authorization);
    const device = token === null ? null : deps.devices.findByToken(token);
    if (device === null) return reply.code(401).send({ error: "unauthorized" });
    request.device = device;
  });

  app.get("/events", async (request, reply) => {
    const query = EventsQuery.safeParse(request.query);
    if (!query.success) return reply.code(400).send({ error: "invalid_query" });
    return { events: new EventRepo(deps.db).since(query.data.since, query.data.limit) };
  });

  app.get("/monitors", async () => ({
    monitors: deps.devices.listByRole("monitor").map((m) => ({
      id: m.id,
      name: m.name,
      online: registry.isOnline(m.id),
      lastSeenMs: m.lastSeenMs,
      batteryPct: registry.get(m.id)?.batteryPct ?? null,
      charging: registry.get(m.id)?.charging ?? null,
      batteryTemperatureC: registry.get(m.id)?.batteryTemperatureC ?? null,
      speakerVolumePct: registry.get(m.id)?.speakerVolumePct ?? null,
    })),
  }));

  app.get("/monitors/:id/noise-history", async (request, reply) => {
    const { id } = request.params as { id: string };
    if (deps.devices.get(id)?.role !== "monitor") return reply.code(404).send({ error: "no_such_monitor" });
    const query = NoiseQuery.safeParse(request.query);
    const toMs = Date.now();
    const windowStartMs = toMs - 86_400_000;
    if (!query.success || (query.data.fromMs ?? 0) > toMs) return reply.code(400).send({ error: "invalid_query" });
    const fromMs = Math.max(windowStartMs, query.data.fromMs ?? windowStartMs);
    const events = new EventRepo(deps.db).inWindow(id, windowStartMs, toMs);
    return {
      windowStartMs, toMs,
      samples: new NoiseRepo(deps.db).between(id, fromMs, toMs),
      events: events.slice(0, 5000),
      eventsTruncated: events.length > 5000,
    };
  });

  app.put("/monitors/:id/config", async (request, reply) => {
    const { id } = request.params as { id: string };
    if (deps.devices.get(id)?.role !== "monitor") {
      return reply.code(404).send({ error: "no_such_monitor" });
    }

    const parsed = MonitorConfigSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: "invalid_config" });

    new MonitorConfigRepo(deps.db).set(id, parsed.data);
    const connection = registry.get(id);
    if (connection !== null) send(connection.socket, { t: "config", config: parsed.data });
    return { config: parsed.data };
  });

  app.post("/monitors/:id/recalibrate", async (request, reply) => {
    const { id } = request.params as { id: string };
    if (deps.devices.get(id)?.role !== "monitor") {
      return reply.code(404).send({ error: "no_such_monitor" });
    }

    const connection = registry.get(id);
    if (connection === null) return reply.code(409).send({ error: "monitor_offline" });

    const { calibrationWindowMs } = new MonitorConfigRepo(deps.db).get(id);
    send(connection.socket, { t: "recalibrate", windowMs: calibrationWindowMs });
    return reply.code(202).send({ started: true });
  });

  return app;
}
