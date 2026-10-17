# iOS monitor — implementation and verification

October 12, 2026. The monitoring-device app is implemented in `apps/ios/` using
SwiftUI, AVAudioEngine, AVFoundation, URLSession and Keychain. The Android parent
remains the parent app. Existing Android and hub work was preserved; no backend
update, credential revocation, pairing reset or data-volume change was made.

## Delivered

- Confirmed device naming before QR/manual pairing: the model name is prefilled
  and can be accepted unchanged or edited. Examples travel with the
  phone rather than tying it to a room. Android naming copy and operating guides
  now suggest device labels too. The requested physical label is **iPhone 13 Pro**.
- Certificate-pinned HTTP/WSS; a separate unauthenticated health probe for typed
  pairing, fingerprint confirmation before redemption, redirect rejection,
  monitor-role checks and device-only Keychain credentials.
- Explicit Start/Stop, microphone permission/failure handling, reconnect backoff,
  heartbeat/liveness handling, fresh battery/charging/thermal/volume metadata.
- Android noise-detection/calibration/history algorithms in a standalone Swift
  package, with fixed 20 ms PCM16 windows and no fabricated missing measurements.
- On-demand gravity-oriented JPEG video, first-frame readiness, bounded media
  buffering, front/back selection with previous-camera restoration, and cleanup
  on stream cancellation/disconnection/stop.
- Built-in-speaker talkback with stale-id/payload rejection, bounded queued PCM,
  route checks, three-second timeout and lifecycle cleanup.
- Matching black dashboard, typography sizes, orange alert reference, diagnostic
  rows, 20-segment level meter, portrait/landscape arrangements and app icon.

## Verified locally

- Swift core: **10 tests passed**, covering byte order, signed PCM RMS,
  malformed input/roles/addresses, UTF-16 name limits, sustain/cooldown/EMA,
  calibration median/IQR, energy history, gaps and interval limits.
- Simulator XCTest: **10 tests passed**. TLS/WSS tests against an isolated instance of the actual
  TypeScript hub verify certificate probing, mismatched-pin rejection without
  consuming the code, HTTP 201 pairing, code reuse rejection, authenticated
  config, heartbeat and numeric noise acceptance, including a connection held
  beyond the HTTP timeout. The fixture uses temporary
  data and loopback ports; it does not replace the deployed home hub.
- Media lifecycle tests cover first-frame proof, cancellation during startup,
  stale frames after lens switching, audio/voice continuity through lens changes,
  stale/invalid voice packets, background video/voice release with continued
  microphone capture, microphone failure stopping the connection, and Stop canceling a pending
  permission request.
- Both dashboard layouts were captured through a real UIHostingController/window
  and inspected. ImageRenderer returns a blank image for the UIKit-backed
  ScrollView; the visual test now checks actual pixel diversity as well as
  retaining screenshots. See [portrait](assets/ios-monitor-ready-portrait.png)
  and [landscape](assets/ios-monitor-ready-landscape.png). Landscape diagnostics
  can scroll vertically on a short display, matching Android.
- Signed iPhone build succeeded and the app was installed on the connected
  iPhone 13 Pro. A physical XCTest run could not start because the phone was
  locked; installation is not proof of physical capture.
- Android monitor unit tests and lint passed after the naming-copy change.
- Repository TypeScript build/typecheck and secret scan passed. New iOS files
  were separately included in a content scan because the repository scanner
  checks tracked files only. Documentation links and whitespace were checked.

## Platform differences and remaining acceptance

The app intentionally releases video and voice when backgrounded/locked; its
background mode supports audio, and monitoring keeps the display awake for
foreground camera availability. Reopen and retry video on the Android parent.
There is no public iOS battery-temperature reading or system-volume setter;
report null temperature and actual speaker volume. Use the phone's volume
buttons instead of Android's temporary automatic 80% setting. No VoIP background
mode or special camera entitlement is claimed.

After the development-machine power outage, the deployed home hub at
`192.168.1.100:8444` was unreachable over HTTPS and SSH (including direct IP),
and `hub-host` did not resolve. Only the Pixel 6 monitor was present in ADB;
the Pixel 8 Pro parent was absent. The iPhone was still connected but locked.
No recovery action was attempted against an uninspected host.

## Physical pairing follow-up — October 12, 2026

The hub was healthy after automatic reboot recovery. The Mac was on
`192.168.1.x`, while the connected Android parent was on `192.168.2.x`.
SSH through the parent's ADB/netcat connection reached the existing host,
using `HostKeyAlias=<hub-host>` to verify its previously trusted SSH identity.
The deployed revision was `d73a287`, the tree was clean, Docker active/enabled,
and the running hub retained its `unless-stopped` policy and original
`kids-monitor_hub-data` volume. No rebuild or restart was needed.

A fresh monitor QR was generated on that existing hub, rendered locally without
printing its payload, and scanned on the iPhone after explicit naming. The name
entered on the phone was **iPhone 13 pro** (lowercase “pro”); the hub and Android
parent display that exact name. The temporary QR was removed after redemption.

Two snapshots more than 20 seconds apart confirmed advancing last-seen for the
new iPhone, Pixel 6 and Pixel 8 Pro parent. The iPhone's recent numeric noise
sample count advanced from 2 to 6 with fresh end timestamps. SQLite quick-check
returned `ok`. The Pixel 3 remained offline and was not part of this bring-up.

The Android parent displayed the iPhone's live view. Parent diagnostics reported
16 kHz mono audio and 480×640 JPEG video, `rotationDegrees=0`, first from the back
camera and then from the front camera on the same stream. Its LIVE screen showed
Front camera and both camera choices were advertised. Talk negotiation reached
“Microphone on · monitor speaker on,” with room-audio playback paused while
speaking; the parent's audio diagnostics showed the 16 kHz playback/input tracks.
This verifies protocol/capture bring-up, camera switching and speaker-readiness
negotiation, but does not independently establish acoustic quality or audibility.

**Still required:** a real noise-alert exercise, four mounting positions,
talkback/room-audio audibility, last-viewer camera release, locked audio/network
continuity, restart recovery and a soak run. Complete the remaining
[physical Android-parent acceptance](../guides/ios-monitor.md#physical-acceptance-with-the-android-parent)
before claiming full hardware parity/reliability.


## Talkback volume/rendering correction — October 12, 2026

The operator reported very quiet iPhone playback despite the Android parent
showing speaker readiness; speech was audible only close to the parent's mic.
Real diagnostic voice traffic reached the iPhone and rendered on its built-in
speaker at system volume 100%, but the session remained in measurement mode.
Apple's SDK documents that this mode disables output dynamics and lowers playback
level. Keep measurement processing for room listening, temporarily select normal
processing for parent voice, and restore measurement processing when talk ends.
The video-only AVCaptureSession now leaves application audio settings to the
AudioCapture controller, including during lens switches.

The original live queue also discarded 873 of 3097 received buffers (about 28%).
Its three slots waited for `dataPlayedBack`, including hardware presentation
latency. Capacity now frees at `dataRendered`, bounding waiting voice without
counting the fixed hardware delay as queued audio. Debug diagnostics contain only
counters, route and volume metadata; no speech or credentials are recorded.

Verified on the physical iPhone: both speaker-only and camera/speaker tests
rendered all 50 generated 20-ms PCM buffers with zero drops. Both lenses worked
without ending microphone capture. The session switched to default processing
for Talk and returned to measurement afterward; real microphone windows kept
advancing after that restoration. Two hardware tests passed, plus an additional
focused restoration check. A real-hub loopback test proves parent PCM arrives at
the iOS receiver with the correct talk id and byte order; it and the five media
lifecycle tests passed. The updated build was installed preserving Keychain
pairing, and reopened for the operator's normal-distance voice comparison.
Acoustic volume after the correction still requires that operator check.


The operator subsequently reported briefly audible parent-to-monitor speech,
interrupted by an app reinstall during verification. This interruption was a
development installation, not evidence of an unexplained playback timeout. The
current fixed build is installed and the app is running; an uninterrupted
normal-distance trial remains pending explicit Start and Talk. Finish all installs
and hardware XCTest runs before this operator trial, then observe passively.
