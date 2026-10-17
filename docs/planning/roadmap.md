# Roadmap

Phase 1 is specified in
the Phase 1 design spec, which is kept outside this repository.
Phases 2 and 3 are named here only so Phase 1 does not paint itself into a
corner. Each gets its own spec when it starts.

## Phase 1 — LAN-only, Android

Sequenced so nothing waits on the Android SDK longer than it must.

| Milestone | Deliverable | Blocked on |
| --- | --- | --- |
| M0 | Repo scaffold, protocol package, CI, secret scanning | **done** — [progress](progress-m0-m2.md) |
| M1 | Hub: pairing, auth, WSS, event persistence, REST log | **done** — same plan and progress as M0 |
| M2 | `fake-monitor` CLI emitting events and synthetic media | **done** — same plan and progress as M0 |
| M3 | Parent app: pairing by QR, alert list, foreground socket, notifications | **done** — [progress](progress-m3.md); verified on a Pixel 8 Pro (Android 17) |
| M4 | Live audio and video: monitor media push + parent live view | **done 2026-10-02** — [progress](progress-m4.md); foreground-only live view, 640×480 at 10 fps, verified on both phones |
| M5 | Monitor app: capture and audio detection | **done** — [progress](progress-m5.md); verified on a Pixel 6 and Pixel 8 Pro |
| M6 | Hardware bring-up on the real old phone | physical phone |

Everything above M6 is merged to `main` and running on the two Pixels. M6 is the
only Phase 1 milestone left, and it is waiting on hardware rather than on code.

**Reordered 2026-09-10:** M5 now precedes M4. The hub's media relay is already
built and tested, so the monitor app has a working server to talk to, and doing
it first yields a real end-to-end monitor before any live-view UI exists. Media
push moves into M4, alongside the parent screen that consumes it.

**Still open from M0–M2:** the Docker image build has never been run — see
[`progress-m0-m2.md`](progress-m0-m2.md). Everything else in those milestones is
merged.

## Phase 2 — Parent app on iOS

Implemented 2026-10-12 as a separate SwiftUI **KidsParent** target, reusing the
hub and protocol without deployment changes. Dashboard, persisted Events/noise,
foreground live video/audio, camera switching, snapshots and voice follow the
Android parent. Physical iPhone 13 Pro tests verify real Pixel 6 video/PCM,
camera switching and lifecycle recovery. See [the iOS parent guide](../guides/ios-parent.md)
and [implementation status](progress-ios-parent.md).

The platform limit is explicit: **LAN-only live alerts require the app to stay
open**. Background/lock stops delivery and live media; reopening backfills all
missed event pages. Reliable locked-parent alerts require the Phase 3 APNs/relay
work and are not part of this implementation.

## Phase 3 — Internet relay

Alerts away from home. Requires FCM/APNs, a reachable relay, and identity that
works across networks. The Phase 1 notification transport sits behind an
interface specifically so this drops in.

## Done — manual pairing entry (2026-10-02; [progress](progress-manual-pairing.md))

Scanning a QR is the wrong ergonomics for a code that has to be re-entered after
every `pm clear`, every reinstall and every revocation. Decided during M5 Task 9,
to be built once M5 is verified and merged.

**Shape:**

1. **The pairing code becomes 6 digits.** It is single-use with a 5-minute TTL,
   so 10^6 is ample — the same reasoning TOTP and Matter setup codes use. This
   requires **hub-side rate limiting on redemption**, which is what actually
   makes a short code safe; without it, 10^6 is brute-forceable in the window.
   That limiter is part of the work, not a follow-up.
2. **Host and port are typed**, or discovered by mDNS later.
3. **The certificate fingerprint is confirmed by eye.** After connecting, the app
   shows a 6-character string derived from the fingerprint; the hub prints the
   same one next to the code. The user compares them. SSH's and Bluetooth's
   numeric-comparison model.

**Why the fingerprint cannot just be shortened into the code.** It is the whole
trust anchor — `CertificatePinner` was dropped precisely because CA validation is
meaningless for a self-signed home server — and 256 bits do not fit in six
digits. Nor can the hub prove its fingerprint *using* the code: the app sends the
code over the channel being authenticated, so a man-in-the-middle learns it and
can compute any HMAC the app would check. The cryptographically correct fix is a
PAKE (SPAKE2, which is what Matter actually uses behind its short setup code);
that was considered and rejected as disproportionate for Phase 1. The eyeball
comparison buys the same MITM detection for one glance.

**The QR stays.** It carries the fingerprint without a human in the loop, so it
remains the stronger path and the default.

**Not needed for development.** `tools/pair-device.sh` already pairs a debug
build over adb with no camera and no typing, which is the friction that prompted
this. The manual path is for anyone deploying this without a laptop plugged in.

## Implemented — monitor mounting and parent usability (2026-10-03)

Gravity-based camera orientation, a black parent live-view status-bar area, and
monitor battery percentages in the parent list and live view are implemented.
The sensor runs with the monitor service, independent of the display. Rotation
changes during a stream reuse the existing format announcements; no hub upgrade
or wire-schema change is needed. Battery readings refresh on every heartbeat.

Verification and remaining hardware checks:
[`progress-monitor-usability.md`](progress-monitor-usability.md).
Do the four mounting positions and locked-screen tests before M6.

**Follow-up:** a parent-side rotate control, remembered per monitor, could cover
a phone initially lying flat where gravity cannot choose a picture orientation.
For now unknown gravity preserves the previous mount (natural portrait until
there has been a usable sensor reading).

## Implemented — iOS monitoring device (2026-10-12)

The SwiftUI monitor matches the current Android monitor's dashboard and protocol;
the Android parent remains the test client. Signed iPhone and simulator builds,
core/noise parity, TLS/WSS integration and media lifecycle tests are implemented.
Physical cross-platform acceptance is pending hub reachability and unlocked,
connected phones after a power outage. Locked-camera and automatic speaker-volume
parity are limited by iOS. See [status](progress-ios-monitor.md) and
[build/pair/verify guide](../guides/ios-monitor.md).

## Before making the repository public

- **Document the OEM battery-management risk for other developers.** Both of
  this project's devices are Pixels, so spec §3.3's top residual risk is small
  here and is deliberately not being chased during development. It is *not*
  small on Xiaomi, Samsung, Oppo or Huawei phones, and anyone forking this will
  hit it. The mitigations are already built (watchdog, backoff reconnect,
  backfill on reconnect, visible disconnected banner); what is missing is a
  README section telling a stranger what to expect on their hardware.

## Prerequisites

- **Android toolchain — working.** Android Studio and the SDK live at
  `<android-sdk-path>/`; `adb` is in
  `AndroidSDK/platform-tools/`. Both apps build, test and install from the
  command line, so whatever was missing in the M0 survey no longer blocks
  anything. The build targets compileSdk 37 with AGP 9.4.0 and Kotlin 2.4.20
  (see `apps/android/gradle/libs.versions.toml`).
- **The physical old phone — outstanding.** Blocks M6, and with it the only real
  verification of thermals, doze survival, reboot behaviour, and OEM battery
  managers.
