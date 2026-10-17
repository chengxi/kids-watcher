# Manual Pairing — Progress and Handoff

**Plan:** `2026-10-02-manual-pairing-entry.md` (kept outside this repository)
**Spec:** `2026-10-02-manual-pairing-design.md` (kept outside this repository)
**Branch:** `feat/manual-pairing`

Anyone picking this up: read the plan's Global Constraints, then "Deviations"
below, then start at the first task not marked done.

## Status

| Task | Title | State |
| --- | --- | --- |
| 1 | Six-digit codes the hub can issue, sweep, and redeem by role | **done** |
| 2 | The redemption lockout | **done** |
| 3 | The pair command prints what the user compares | **done** |
| 4 | The check string in Kotlin, agreed across languages | **done** |
| 5 | `HubProbe`: learn the certificate before trusting it | **done** |
| 6 | `ManualPairing`: the typed-pairing state machine | **done** |
| 7 | The parent app's manual pairing screen | **done** |
| 8 | The monitor app's manual pairing screen | **done** |
| 9 | Verify on the Pixel 6 and Pixel 8 Pro, and write it down | **done** |

## Deviations from the plan

Recorded as they happen, with the reason. A deviation with no reason is a bug.

## Negative controls

### Task 1 — two deviations

- `packages/protocol` must be rebuilt (`npx tsc -b packages/protocol`) before
  the hub tests see a new export: the hub imports the built package, so
  `PAIRING_CODE_DIGITS` arrived as `undefined` and `randomInt` threw on `NaN`.
- The existing `rejects an unknown code with 403` test passed `ZZZZZZZZ`, which
  is now a malformed body (400). Changed to `000000`: well-formed, unknown.

### Task 1 — negative controls

1. Removed the expiry sweep in `issue`. Only `sweeps expired codes when
   issuing…` failed (`could not issue a unique pairing code`). Restored.
2. Moved the `used_at_ms` update above the `expectRole` check. Only `refuses a
   code issued for another role without consuming it` failed (the second
   redemption returned `invalid`). Restored; 8/8 green.

### Task 2 — two deviations

- The plan's `failTimes` test helper advanced the clock one second past the
  last failure, so the first lockout read 59000 rather than 60000. The helper
  was wrong, not the limiter: it now returns the time of the last failure.
- `tools/fake-monitor/test/client.test.ts` passed `ZZZZZZZZ` as its bad code,
  which is now a malformed body (400) rather than an unknown code (403).
  Changed to `000000`.

### Task 2 — negative controls

1. Made `retryAfterMs` always return 0. Only `locks redemption after repeated
   wrong codes…` failed (201 where 429 was expected). Restored.
2. Counted a role mismatch as a failure. Only `does not count a role mismatch
   as a guess` failed (429 where 409 was expected). Restored; 10/10 green.

### Task 3 — no deviations

`tools/pair-device.sh` and `tools/show-pairing-qr.sh` both extract the code with
`grep 'code:' | awk '{print $2}'`; simulated against the new printout, both
still read `123456`. No script change was needed.

### Task 3 — negative control

Printed the grouped code on the machine-readable `code:` line. Only `keeps a
machine-readable code line…` failed (`"123"` rather than `"123456"`), which is
exactly what would have broken both shell tools. Restored; 4/4 green.

### Task 4 — no deviations

### Task 4 — negative control

Set `FINGERPRINT_CHECK_BYTES = 4` in Kotlin. Two `FingerprintCheckTest` cases
failed and the conformance script reported `REJECTED FINGERPRINT_CHECK_BYTES:
Kotlin 4, TypeScript 3`. Restored; 26/26 validate again.

### Task 5 — one deviation

The TLS-error test probes `127.0.0.1` rather than `server.hostName`. Through
the hostname the connection to a plain-HTTP MockWebServer was refused outright
on the IPv6 loopback (`Unreachable`), which never reaches the handshake; over
IPv4 it connects and fails in TLS, which is the branch under test.

### Task 5 — negative controls

1. Dropped the `"ok":true` body check, keeping only the status check. Only
   `reports not a hub when something else is listening on TLS` failed — a
   router admin page would have been accepted as a hub. Restored.
2. Made the recording trust manager a no-op. Three tests failed, including
   `returns the certificate fingerprint…`. Restored; 5/5 green.

Containment check: `X509ExtendedTrustManager` appears in exactly two files,
`HubTrust.kt` (pinned) and `HubProbe.kt` (trust-nothing, private).

### Task 6 — two deviations

- `HubApi.pair` takes an explicit `baseUrl` to store, separate from the client's
  own base URL. The plan's version returned the client's, which the existing QR
  test caught: credentials must record what was scanned or confirmed, not
  whatever host answered.
- `ManualPairingTest` runs under `RobolectricTestRunner`. `ManualPairing` logs
  through `android.util.Log`, which a plain JVM test cannot call. This matches
  `PairingViewModelTest`.

### Task 6 — negative controls

1. Paired as soon as the probe returned, skipping the confirmation. `asks for
   confirmation before sending the code` and `a mismatch sends nothing…` both
   failed. This is the spec's central rule. Restored.
2. Made `confirmMismatch` pair anyway. Only `a mismatch sends nothing and leaves
   the code unused` failed. Restored; the whole `:shared` suite is green.

### Task 7 — no deviations

The "Enter a code instead" button sits below the scanner rather than replacing
it: the QR path stays the default, as the spec says.

### Task 7 — negative control

Added a `rememberLauncherForActivityResult` to the manual screen. Only `the
manual screen requests no permissions` failed. Removed; `:parent` green.

### Task 8 — no deviations

The monitor's screen differs from the parent's in exactly three places: the
package, `role = "monitor"`, and the heading. The shared logic is in
`ManualPairing`, so there is nothing else to keep in step.

### Task 8 — negative control

Changed the monitor's screen to `role = "parent"`. Only `the monitor pairs as a
monitor` failed. Restored; `:shared`, `:monitor` and `:parent` all green.

## Task 9 — verified on the Pixel 6 and Pixel 8 Pro (2026-10-02)

Hub and branch both at `c8b1316` when testing started; certificate fingerprint
unchanged (`D8:52:26:…`), so nothing needed re-pairing.

### Verified on hardware

| Claim | Evidence |
| --- | --- |
| The pair command prints what the user compares | `code: 803830`, `type it as: 803 830`, `check: D8:52:26`, full fingerprint |
| The lockout is real | Five wrong codes → `403` each; the sixth request → `429`, `retry-after: 60`, `retryAfterMs: 59970` |
| The lockout does not consume a real code | After the lockout cleared, the code issued before it still paired (`201`) |
| A role mismatch is refused and the code survives | A monitor code with `expectRole: parent` → `409 role_mismatch`; the same code then paired as a monitor (`201`) |
| Typed pairing works end to end | Pixel 8 Pro, app cleared: typed `192.168.1.100`, `8444`, `669 451`; confirm step showed `D8:52:26`; tapping "They match" paired as `d50105e9` |
| A spaced code is accepted | The code was typed as `669 451`, with the space |
| **A mismatch sends nothing, and the code survives it** | Tapped "They do not match": the screen said nothing was sent, the hub's device list did not grow, and the *same code* then paired normally. This is the spec's central claim |
| Pairing needs no camera | `dumpsys package` during the successful pairing: `CAMERA: granted=false`, `ACCESS_LOCAL_NETWORK: granted=true` |
| The code never reaches the log | `logcat -s KidsMonitor` shows only "hub found … awaiting confirmation" and "paired as device …" |
| The new registration works | The parent shows "Pixel 6 · Online"; a forced `MONITOR_LOST` reached it as "the monitor is offline" within ~10 s |

### The defect this found

The typed screen originally requested **no** permissions, which the plan and
its test treated as the goal. On the Pixel 8 Pro (SDK 37) that made pairing
impossible on a freshly installed app: `ACCESS_LOCAL_NETWORK` is denied by
default, so every connection to the LAN hub timed out after 10 s with "failed
to connect", the same trap M5 hit by a different route.

Fixed in this task: `RuntimePermissions.PARENT_TYPED` and `MONITOR_TYPED` ask
for the local network (and notifications, and the microphone on the monitor)
but never the camera, requested through one launcher. The screen tests now
assert the real rule — the camera is never requested, and there is exactly one
launcher — rather than "nothing is requested", which was wrong.

### Not verified

- **The QR path on hardware this session.** One attempt failed, but only
  because the permission defect above also denied the local network to the
  scanning screen; the code path itself is unchanged and covered by
  `HubApiTest` and `PairingViewModelTest` — which did catch a real regression
  during Task 6 (stored credentials must record the confirmed host). Re-testing
  it needs another credential clear and two permission taps, so it was left for
  the next hardware session.
- The monitor app's typed screen on a device: only the parent's was exercised.
  The two differ in three lines (package, role, heading).
- A hub on a different address than the one these phones already use.
