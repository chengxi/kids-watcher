import { describe, expect, it } from "vitest";
import {
  BASE_LOCKOUT_MS,
  FAILURE_WINDOW_MS,
  MAX_FAILURES,
  MAX_LOCKOUT_MS,
  RedemptionLimiter,
} from "../src/redemptionLimiter.js";

/** Records n failures a second apart and returns the time of the last one. */
function failTimes(limiter: RedemptionLimiter, n: number, startMs: number): number {
  let now = startMs;
  for (let i = 0; i < n; i++) {
    limiter.recordFailure(now);
    if (i < n - 1) now += 1000;
  }
  return now;
}

describe("RedemptionLimiter", () => {
  it("stays open while failures are below the threshold", () => {
    const limiter = new RedemptionLimiter();
    const now = failTimes(limiter, MAX_FAILURES - 1, 0);
    expect(limiter.retryAfterMs(now)).toBe(0);
  });

  it("locks after too many failures and reopens when the lockout expires", () => {
    const limiter = new RedemptionLimiter();
    const now = failTimes(limiter, MAX_FAILURES, 0);
    expect(limiter.retryAfterMs(now)).toBeGreaterThan(0);
    expect(limiter.retryAfterMs(now)).toBeLessThanOrEqual(BASE_LOCKOUT_MS);
    expect(limiter.retryAfterMs(now + BASE_LOCKOUT_MS)).toBe(0);
  });

  it("forgets failures older than the window", () => {
    const limiter = new RedemptionLimiter();
    for (let i = 0; i < MAX_FAILURES - 1; i++) limiter.recordFailure(i * 1000);
    // Long enough that every earlier failure has aged out.
    const later = FAILURE_WINDOW_MS + 10_000;
    limiter.recordFailure(later);
    expect(limiter.retryAfterMs(later)).toBe(0);
  });

  it("doubles the lockout each time it trips again, up to the cap", () => {
    const limiter = new RedemptionLimiter();
    let now = failTimes(limiter, MAX_FAILURES, 0);
    expect(limiter.retryAfterMs(now)).toBe(BASE_LOCKOUT_MS);

    now += BASE_LOCKOUT_MS;
    now = failTimes(limiter, MAX_FAILURES, now);
    expect(limiter.retryAfterMs(now)).toBe(BASE_LOCKOUT_MS * 2);

    for (let round = 0; round < 10; round++) {
      now += limiter.retryAfterMs(now);
      now = failTimes(limiter, MAX_FAILURES, now);
    }
    expect(limiter.retryAfterMs(now)).toBe(MAX_LOCKOUT_MS);
  });

  it("clears everything after a successful redemption", () => {
    const limiter = new RedemptionLimiter();
    const now = failTimes(limiter, MAX_FAILURES, 0);
    limiter.recordSuccess();
    expect(limiter.retryAfterMs(now)).toBe(0);

    // And the next lockout starts from the base again, not from the doubled one.
    const after = failTimes(limiter, MAX_FAILURES, now);
    expect(limiter.retryAfterMs(after)).toBe(BASE_LOCKOUT_MS);
  });
});
