import { describe, expect, it } from "vitest";
import { mintToken } from "../src/auth.js";
import { DeviceRepo } from "../src/db/devices.js";
import { EventRepo } from "../src/db/events.js";
import { openDb } from "../src/db/open.js";

function fixture() {
  const db = openDb(":memory:");
  const devices = new DeviceRepo(db);
  const monitor = devices.create("monitor", "Nursery", mintToken());
  const parent = devices.create("parent", "Mum", mintToken());
  return { db, devices, events: new EventRepo(db), monitor, parent };
}

describe("EventRepo", () => {
  it("assigns strictly increasing ids", () => {
    const { events, monitor } = fixture();
    const a = events.append(monitor.id, "LOUD_NOISE", 10, {});
    const b = events.append(monitor.id, "LOUD_NOISE", 20, {});
    expect(b.id).toBeGreaterThan(a.id);
  });

  it("round-trips metadata", () => {
    const { events, monitor } = fixture();
    const row = events.append(monitor.id, "LOUD_NOISE", 10, { peakDb: -12.5, durationMs: 1800 });
    expect(events.since(0, 10)[0]?.metadata).toEqual({ peakDb: -12.5, durationMs: 1800 });
    expect(row.metadata.peakDb).toBe(-12.5);
  });

  it("returns only events after the cursor", () => {
    const { events, monitor } = fixture();
    const first = events.append(monitor.id, "LOUD_NOISE", 10, {});
    events.append(monitor.id, "MOTION", 20, {});
    expect(events.since(first.id, 10).map((e) => e.type)).toEqual(["MOTION"]);
  });

  it("returns everything for cursor 0", () => {
    const { events, monitor } = fixture();
    events.append(monitor.id, "LOUD_NOISE", 10, {});
    events.append(monitor.id, "MOTION", 20, {});
    expect(events.since(0, 10)).toHaveLength(2);
  });

  it("honours the limit", () => {
    const { events, monitor } = fixture();
    for (let i = 0; i < 5; i++) events.append(monitor.id, "LOUD_NOISE", i, {});
    expect(events.since(0, 2)).toHaveLength(2);
  });

  it("tracks acknowledgement per device", () => {
    const { events, monitor, parent } = fixture();
    const row = events.append(monitor.id, "LOUD_NOISE", 10, {});
    expect(events.unackedCount(parent.id)).toBe(1);
    events.ack(row.id, parent.id, 11);
    expect(events.unackedCount(parent.id)).toBe(0);
  });

  it("is idempotent when the same device acks twice", () => {
    const { events, monitor, parent } = fixture();
    const row = events.append(monitor.id, "LOUD_NOISE", 10, {});
    events.ack(row.id, parent.id, 11);
    events.ack(row.id, parent.id, 12);
    expect(events.unackedCount(parent.id)).toBe(0);
  });

  it("does not clear an event for one parent when the other acks it", () => {
    const { events, devices, monitor } = fixture();
    const mum = devices.create("parent", "Mum2", mintToken());
    const dad = devices.create("parent", "Dad", mintToken());
    const row = events.append(monitor.id, "LOUD_NOISE", 10, {});
    events.ack(row.id, mum.id, 11);
    expect(events.unackedCount(mum.id)).toBe(0);
    expect(events.unackedCount(dad.id)).toBe(1);
  });
});
