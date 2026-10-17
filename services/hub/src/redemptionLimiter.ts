export const MAX_FAILURES = 5;
export const FAILURE_WINDOW_MS = 60_000;
export const BASE_LOCKOUT_MS = 60_000;
export const MAX_LOCKOUT_MS = 900_000;

/**
 * What makes a six-digit pairing code safe. Five wrong codes in a minute lock
 * redemption for everyone, and each repeat doubles the wait.
 *
 * Global rather than per-IP: on a LAN an attacker picks a new address for free,
 * and pairing is rare enough that one brake costs nothing. In memory rather
 * than in the database: a restart clears it, but restarting the hub needs
 * access to the host, which is already a lost game.
 */
export class RedemptionLimiter {
  private failures: number[] = [];
  private lockedUntilMs = 0;
  private lockoutMs = BASE_LOCKOUT_MS;

  /** Milliseconds the caller must wait, or 0 when redemption is open. */
  retryAfterMs(nowMs: number): number {
    return Math.max(0, this.lockedUntilMs - nowMs);
  }

  recordFailure(nowMs: number): void {
    this.failures = this.failures.filter((at) => nowMs - at < FAILURE_WINDOW_MS);
    this.failures.push(nowMs);
    if (this.failures.length < MAX_FAILURES) return;

    this.lockedUntilMs = nowMs + this.lockoutMs;
    this.lockoutMs = Math.min(this.lockoutMs * 2, MAX_LOCKOUT_MS);
    this.failures = [];
  }

  recordSuccess(): void {
    this.failures = [];
    this.lockedUntilMs = 0;
    this.lockoutMs = BASE_LOCKOUT_MS;
  }
}
