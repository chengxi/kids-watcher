import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { MediaKind, encodeMediaFrame } from "@kids-monitor/protocol";
import { createDeps, startHub, type Hub } from "../../../services/hub/src/server.js";
import { FakeMonitor, pairDevice } from "../../fake-monitor/src/client.js";
import { FAKE_VIDEO, fixtureJpeg } from "../../fake-monitor/src/media.js";
import { FakeParent } from "../src/client.js";

let hub: Hub;
const originalTls = process.env.NODE_TLS_REJECT_UNAUTHORIZED;

beforeAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"; });
afterAll(() => { process.env.NODE_TLS_REJECT_UNAUTHORIZED = originalTls; });

beforeEach(async () => {
  hub = await startHub(createDeps(mkdtempSync(join(tmpdir(), "fp-"))), 0);
});
afterEach(async () => hub.close());

async function pairParent(name = "Fake Parent") {
  return pairDevice(hub.httpsUrl, hub.deps.pairing.issue("parent", Date.now()), name);
}
async function pairMonitor(name = "Fake Nursery") {
  return pairDevice(hub.httpsUrl, hub.deps.pairing.issue("monitor", Date.now()), name);
}

describe("fake parent", () => {
  it("receives an event raised by a monitor", async () => {
    const monitorCreds = await pairMonitor();
    const parentCreds = await pairParent();

    const seen: { type: string; metadata: { peakDb?: number | null } }[] = [];
    const parent = new FakeParent({
      wsUrl: hub.wsUrl, token: parentCreds.token, onEvent: (e) => seen.push(e),
    });
    await parent.connect();

    const monitor = new FakeMonitor({
      wsUrl: hub.wsUrl, token: monitorCreds.token, name: "Fake Nursery",
    });
    await monitor.connect();
    monitor.emitEvent("LOUD_NOISE", { peakDb: -9.5, durationMs: 2200 });
    await new Promise((r) => setTimeout(r, 400));

    expect(seen.map((e) => e.type)).toContain("LOUD_NOISE");
    expect(seen[0]?.metadata.peakDb).toBe(-9.5);
    monitor.close();
    parent.close();
  });

  it("watches a stream and counts its binary frames", async () => {
    const monitorCreds = await pairMonitor();
    const parentCreds = await pairParent();
    const monitor = new FakeMonitor({ wsUrl: hub.wsUrl, token: monitorCreds.token, name: "Fake Nursery" });
    await monitor.connect();
    monitor.on("start_stream", (msg: { streamId: number }) => {
      monitor.send({ t: "stream_started", streamId: msg.streamId, video: FAKE_VIDEO, audio: null });
      const frame = encodeMediaFrame({ kind: MediaKind.Jpeg, streamId: msg.streamId, timestampUs: 1n, payload: fixtureJpeg(0) });
      for (let i = 0; i < 3; i++) monitor.sendBinary(frame);
    });

    const parent = new FakeParent({ wsUrl: hub.wsUrl, token: parentCreds.token, onEvent: () => {} });
    await parent.connect();
    parent.watch(monitorCreds.deviceId);
    await new Promise((r) => setTimeout(r, 500));

    expect(parent.mediaFrames).toBe(3);
    parent.close();
    monitor.close();
  });

  it("receives MONITOR_LOST when a monitor goes silent", async () => {
    // The watchdog's output is what the real parent app treats as an alarm, so
    // fake-parent has to see it too or it cannot stand in for the app.
    const parentCreds = await pairParent();
    const seen: { type: string }[] = [];
    const parent = new FakeParent({
      wsUrl: hub.wsUrl, token: parentCreds.token, onEvent: (e) => seen.push(e),
    });
    await parent.connect();

    const monitorCreds = await pairMonitor();
    const monitor = new FakeMonitor({
      wsUrl: hub.wsUrl, token: monitorCreds.token, name: "Fake Nursery",
    });
    await monitor.connect();
    await new Promise((r) => setTimeout(r, 300));
    monitor.close();

    // Drive the sweep directly rather than waiting 45s of real time. Age only
    // the monitor: sweeping at a future clock would also make the connected
    // parent look silent, and the hub now terminates silent connections.
    await new Promise((r) => setTimeout(r, 100));
    for (const m of hub.deps.devices.listByRole("monitor")) {
      hub.deps.devices.touch(m.id, Date.now() - 60_000);
    }
    hub.watchdog.sweep(Date.now());
    await new Promise((r) => setTimeout(r, 400));

    expect(seen.map((e) => e.type)).toContain("MONITOR_LOST");
    parent.close();
  }, 15_000);

  it("reports a revoked token rather than looping silently", async () => {
    const parentCreds = await pairParent();
    const parent = new FakeParent({ wsUrl: hub.wsUrl, token: parentCreds.token, onEvent: () => {} });
    await expect(parent.connect()).resolves.toBeUndefined();
    parent.close();

    hub.deps.devices.revoke(parentCreds.deviceId);
    const rejected = new FakeParent({ wsUrl: hub.wsUrl, token: parentCreds.token, onEvent: () => {} });
    await expect(rejected.connect()).rejects.toThrow();
    rejected.close();
  });
});
