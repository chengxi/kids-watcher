# Building and testing the iOS monitor

The KidsMonitor scheme builds the monitoring device. Use the existing Android
parent for viewing, alerts, device selection, camera switching, photos, and
talkback, or the separate [iOS parent](ios-parent.md) while it is foregrounded.
It uses the current hub protocol; installing it does not require a hub update.
Requires iOS 16+, Xcode with an iOS SDK, and a physical iPhone for camera/mic
verification. The project is checked in; XcodeGen is only needed to regenerate
it after editing `apps/ios/project.yml`.

## Build

An already Xcode-paired phone can use Wi-Fi for installation and testing;
first-time developer pairing may require USB. See
[device connections](device-connections.md) for the platform/version limits.
QR pairing with the hub does not require a developer connection.

Open `apps/ios/KidsMonitor.xcodeproj`, choose the KidsMonitor scheme, select your
Apple development team under Signing & Capabilities, and Run on the phone.
Development signing can expire; an expired development app cannot be relied on
as an unattended monitor. Keep the phone powered and on home Wi-Fi.

From the repository root, simulator build and platform-independent tests:

```bash
cd apps/ios
swift test
xcodebuild -project KidsMonitor.xcodeproj -scheme KidsMonitor \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData \
  SYMROOT="$PWD/DerivedData/Products" OBJROOT="$PWD/DerivedData/Intermediates" \
  CODE_SIGNING_ALLOWED=NO build
```

The explicit output directories avoid machine-wide custom Xcode build locations.
A damaged build database after an outage can be bypassed by choosing fresh
DerivedData/output directories; do not clear app data or pairing to fix a build.

## Pair and start

Confirm the deployed address, persistent certificate and service using
[recovering-hub.md](recovering-hub.md). Ask for a distinct device name and compare
existing names. Use a label that moves with the phone, such as **iPhone 13 Pro**
or **Blue iPhone**. No name is automatically submitted from the hardware model.

1. Accept the prefilled device model name with **Continue to pairing**, or edit
   it first. For example, an iPhone 13 Pro defaults to **iPhone 13 Pro**.
2. Generate a fresh **monitor** code on the existing hub using
   [setup-hub.md](setup-hub.md#pair-a-device).
3. Scan the QR, or tap **Enter address & pairing code** and type the host, port
   and six digits. The typed path works without camera permission. Compare the
   app's six certificate characters with the hub's `check:` line before tapping
   **They match - pair**. A mismatch sends no code.
4. Allow Local Network access. Pairing stores the token and certificate pin in
   the device-only Keychain. QR and typed pairing send `expectRole=monitor`.
5. Tap **Start monitoring** while visible. Allow microphone access; camera access
   is optional and enables on-demand video. Denied microphone access blocks Start.

Start is always explicit, including after a reboot, app termination, installing
an update or an audio interruption. Finish installation and hardware XCTest runs
before an operator's live-audio trial: they can terminate the app and interrupt
monitoring. During the trial, use passive counters/logs and leave both apps
running. Calls/audio interruptions, media-service resets, and a microphone
that stops producing windows end monitoring and disconnect the hub rather than
reporting a quiet room. Open the app and tap Start to resume. Existing Android
pairings remain intact.

## What matches Android

The black dashboard, status ring, 20-segment sound meter, adaptive floor and
threshold, diagnostic rows, battery/charging, alert timestamp, and Start/Stop
controls follow the current Android monitor. Portrait and landscape use the same
one-column/two-column arrangement. Naming, QR/manual pairing, role checks,
certificate pinning, reconnect backoff, and 15-second heartbeats use the same
protocol.

Audio is converted to 16 kHz mono PCM16 and analyzed in 320-sample (20 ms)
windows. Sustained detection, cooldown, quiet-window EMA, median/IQR calibration,
and duration-weighted energy history follow Android's algorithms. Numeric
history intervals are at most 15 seconds and split at capture gaps and
calibration boundaries. Camera starts only for live-view requests; JPEG quality
is 60%, target resolution 640×480 at 10 fps, and gravity-oriented images use
`rotationDegrees=0`. Portrait JPEGs are 480×640. Front video is unmirrored. Camera
switching preserves the stream/audio and attempts to restore the previous lens
on failure. A format is advertised only after a captured frame proves readiness.
Leaving the last viewer, stopping, or disconnecting releases the camera and voice.
No media is saved by the monitor or hub.

## iOS differences

- Background audio mode supports continued sound monitoring while locked.
  **Camera video is foreground-only in this app.** The screen stays awake while
  monitoring; manual locking or backgrounding releases the camera and reports
  sound-only media to the parent. To resume video, reopen the monitor and retry
  video on the Android parent. The app does not pretend background camera parity.
- Talkback uses the built-in speaker with normal audio processing while this
  app is foregrounded, restoring measurement mode afterward. The video-only
  camera session leaves the shared audio settings to the microphone/speaker
  controller. It rejects other output routes, bounds queued voice, and stops after three seconds without
  valid PCM or on backgrounding/stream loss. iOS provides no public API to set the
  system speaker volume to Android's 80% target: set it with the phone's buttons.
  The heartbeat reports the actual volume. No volume setting is overwritten.
- iOS exposes thermal severity, but no public battery-temperature reading.
  Temperature is **unavailable** and its heartbeat field is null.
- iOS has a system microphone indicator instead of Android's foreground-service
  notification. Both implementations require Start after a phone restart.

Apple references: [background audio session](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playandrecord)
and [multitasking camera access](https://developer.apple.com/documentation/avfoundation/avcapturesession/ismultitaskingcameraaccessenabled).
This monitor enables audio background mode; it does not add a VoIP mode or a
multitasking-camera entitlement to claim locked-camera support.

## Automated verification

`swift test` covers algorithm and binary compatibility without a simulator.
XCTest covers explicit naming/no auto-start, certificate-confirmation cancellation,
and dashboard rendering in both orientations. For real URLSession TLS/WSS tests,
run this **disposable loopback fixture**, in another terminal from repo root:

```bash
npm run typecheck
node tools/ios/test-hub.mjs
```

Then choose any installed simulator and run:

```bash
cd apps/ios
xcodebuild -project KidsMonitor.xcodeproj -scheme KidsMonitor \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath DerivedData \
  SYMROOT="$PWD/DerivedData/Products" OBJROOT="$PWD/DerivedData/Intermediates" \
  CODE_SIGNING_ALLOWED=NO test
```

The fixture uses temporary data and ports 18555/18556, separate from every real
pairing. Tests verify probe/pin agreement, pin rejection before code redemption,
HTTP 201 pairing, single-use codes, authenticated socket/config, heartbeat/noise
acceptance, and parent-voice binary delivery through the real TypeScript hub.
`AudioHardwareTests` additionally require a physical iPhone with permissions
already granted; they play brief synthetic tones and verify rendering, camera
switching, audio-mode restoration and continuing microphone windows. Integration tests skip if the fixture is
absent. Stop the fixture with Ctrl-C to close it and delete its temporary data.
It is test tooling, not a deployment or outage workaround.

## Physical acceptance with the Android parent

Confirm the chosen iPhone name in the parent's device list. Check advancing
last-seen and fresh numeric noise samples twice at least 20 seconds apart using
[recovering-hub.md](recovering-hub.md#4-prove-the-paired-apps-resumed-traffic).
Then check:

- Sustained loud sound produces an alert on the Android parent; quiet intervals
  appear on its noise timeline, and calibration pauses alerts.
- Watch live produces real video and audible room sound. Rotate the iPhone in
  four mounting positions, switch front/back, and save a photo on the parent.
- Talk opens the iPhone speaker, uses the phone's actual volume, and stops on
  leaving live view, backgrounding, socket loss or missing voice frames.
- Leaving Watch releases video while noise measurements continue. A second
  parent should share the same stream and camera.
- Lock the iPhone: noise samples/heartbeats should continue, video should become
  sound-only, and voice should stop. Unlock/reopen and retry video on the parent.
- Stop and relaunch/reboot: capture stays off until Start. Interrupt microphone
  access, deny camera access, and disconnect/reconnect Wi-Fi to check failures.

Simulator and protocol tests do not prove physical microphone/video, lock-screen
networking, long-running reliability, speaker acoustics or Android playback.
See [iOS implementation status](../planning/progress-ios-monitor.md) for the
checks completed on the current hardware.
