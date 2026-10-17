# Architecture

Living document. Update it in the same commit as any change that contradicts it.
The frozen, dated reasoning behind Phase 1 lives in
the Phase 1 design spec, which is kept outside this repository;
this file describes the system as it stands today.

**Status:** Phase 1 (LAN-only, Android). Pixel 6 and Pixel 3 monitors work with
the Pixel 8 Pro parent. Initial Pixel 3 bring-up on Android 12 is verified;
extended old-phone reliability testing remains open. See
[installing a monitor](docs/guides/installing-monitor.md). The iOS monitoring-device
app now builds, installs and pairs; live video/camera switching with the Android
parent are verified, while extended physical acceptance is still pending;
see [iOS monitor status](docs/planning/progress-ios-monitor.md). The separate iOS
parent is also implemented and tested on an iPhone 13 Pro with foreground-only
LAN delivery; see [iOS parent status](docs/planning/progress-ios-parent.md).

## What this is

An old Android phone or iPhone becomes a child monitor. It listens in the child's room and
pushes an alert to the parent's phone when something happens. The parent can pull
up a live audio/video feed on demand and browse a log of past events.

Everything runs on the home wifi. No cloud, no accounts, no internet.

## Shape

Exactly one component listens on a port. Both phones dial out to it.

```
   Old phone (Monitor)              Pi / NAS                Parent phone
  +--------------------+         +------------+         +------------------+
  | CameraX  +  mic    |         |            |         |  Alert list      |
  | Event detector     |==WSS===>|    HUB     |<===WSS==|  Live view       |
  | Media pusher       |  (out)  |  (listens) |  (out)  |  Foreground svc  |
  +--------------------+         +-----+------+         +------------------+
     outbound only                     |                    outbound only
                                    SQLite
                               devices . events
```

The hub is the trust anchor, the only stateful component, and the media relay.
When the parent taps "view", the hub tells the monitor to start pushing frames
over its existing socket, then fans them out.

**Why the hub relays instead of the phones talking directly:** one TLS identity
to pin instead of one per phone; no inbound connectivity needed on either phone
(no DHCP chasing, no AP client isolation problems, no server surface on a
phone); and multi-monitor plus the eventual internet relay become drop-ins
rather than rewrites.

**Multiple monitors and multiple parents are supported.** Events fan out to
every connected parent, but a monitor serves at most one active stream shared by
all viewers — encoding twice on an old phone is not worth it. Parent alert history
has no read/unread or acknowledgement state.

**Audio is always on; video is on demand.** Continuous camera capture cooks an
old phone in a closed room, and detection is audio-driven anyway. The camera
spins up only when someone is watching.

## Repository layout

```
kids-monitor/
├─ apps/
│  ├─ android/              # one Gradle build, three modules
│  │   ├─ monitor/          #   the old phone            (minSdk 31)
│  │   ├─ parent/           #   the parent's phone       (minSdk 29)
│  │   └─ shared/           #   protocol, pairing, pinned WSS client, tokens
│  └─ ios/                  # SwiftUI monitor/parent + tested Swift protocol/audio core
├─ services/hub/            # TypeScript · Fastify + ws · SQLite · Docker
├─ packages/protocol/       # wire protocol → generated TS types + Kotlin classes
├─ deploy/                  # docker-compose, arm64 + amd64
├─ tools/fake-monitor/      # CLI that speaks the protocol; no phone required
└─ docs/
```

`packages/protocol` defines the TypeScript/Kotlin wire messages. The iOS monitor
uses a Swift core with byte-order and algorithm parity tests plus TLS/WSS
integration tests against the TypeScript hub.

`tools/fake-monitor` lets the hub and the parent app be developed and tested
without a physical phone or an Android SDK.

## Platform constraints that shaped this

Five Android behaviours drive the design. The first three were verified before
any code was written; the last two were found the hard way, on a device, after
the code was already working in tests. Each cost real time, and each fails
*silently* rather than loudly — so do not re-litigate them without reading
the Phase 1 design spec, §3 (kept outside this repository),
and [lessons-learned](docs/lessons-learned.md).

1. **Capture survives the screen locking** — via a foreground service typed
   `camera`+`microphone`, started while an activity is visible, showing a
   permanent notification.
2. **The monitor cannot auto-start after a reboot.** Android 15+ refuses to let
   `BOOT_COMPLETED` start a camera or microphone foreground service. Someone must
   tap "Start" by hand. The system therefore treats a missing monitor as an
   alarm, not a silent gap.
3. **The parent's persistent socket is the weakest link.** No FCM without
   internet, so the parent app holds its own WebSocket in its own foreground
   service. This avoids App Standby classification but does not guarantee
   unrestricted networking during Doze. Device-idle restrictions and OEM
   battery managers remain risks, mitigated by the watchdog, heartbeats,
   reconnect/backfill, and explicit connection feedback. Long unplugged tests
   are needed before choosing battery-optimization exemptions.
4. **The parent service is typed `specialUse`.** `dataSync` is capped at 6 hours
   per 24 on Android 15+, after which alerts stop arriving silently.
   `connectedDevice` was tried and crashes on Android 17: it additionally
   requires a Bluetooth/NFC/USB/wifi-control permission this app never uses.
   `specialUse` carries no time limit and states its justification in the
   manifest.
5. **Android 17 gates the local network.** `ACCESS_LOCAL_NETWORK` is a mandatory
   runtime permission at SDK 37+; `INTERNET` no longer covers LAN traffic, and
   both apps here talk only to a LAN hub. Blocked connections **time out rather
   than erroring**, so the symptom is identical to an unreachable server.
   It must be requested **in the same request** as the app's other permissions:
   Android drops a permission request made while another dialog is open, and
   the only sign is a logcat warning. Android 17 labels it "nearby devices".

**On reconnect the parent app backfills** via `GET /events?since=<cursor>` in a
separate coroutine, so slow HTTP history loading cannot block stream responses
or alert delivery. A captured recovery cursor is persisted separately from the
newest cached event id and advances only after recovery completes; newer live
events cannot skip a missing overnight gap. Recovery drains all pages.

**The socket's connection events are a queue with one consumer.** Backfill is
triggered by `Connected`, so that event must not be lost to a collector that
started a moment late. Socket events are queued until taken (`RoleSocket`), which
means two collectors would split the stream between them. Each socket has
exactly one consumer: its service. The queue is unbounded, so a socket nobody
collects from keeps every event in memory for its whole life. Harmless with
one consumer per socket, but a second socket without a consumer would grow
without limit.

**Connection state is separate from monitor availability (2026-10-03).** The
parent reports connecting, reconnecting, retry waits, and stopped delivery.
An unsuccessful status request means unknown availability; only a successful
hub report marks a monitor offline. Shared reconnect backoff resets after a
successful connection, and foreground/resume or Refresh can skip a retry wait
without replacing a healthy socket. HTTP calls are cancellable and bounded to
ten seconds independently of the WebSocket's unlimited read timeout. Details,
battery assessment, and remaining soak-test work are in
[`progress-connection-recovery.md`](docs/planning/progress-connection-recovery.md).

**Cold live-view startup retries automatically (2026-10-03).** Watching intent
waits for a connected hub before spending a video attempt. The hub acknowledges
receipt, then the monitor acknowledges preparation before opening the camera.
These observations distinguish hub/network trouble, a monitor that has not
responded, and video preparation. Up to three startup attempts use 15-second
phase deadlines and 2/4-second retry waits (5/10 seconds for a hub-reported
offline monitor). A ready format has a separate 12-second decoded-picture
deadline. Request ids prevent late responses or cancellation from affecting a
new attempt. Leaving or locking the parent cancels retries and releases the
viewer even before a stream is ready; a startup failure retains sound-only
playback after bounded video retries when sound is available. See
[`progress-cold-start-retries.md`](docs/planning/progress-cold-start-retries.md).

**Live media is a separate, lossy path (M4).** Control messages
(`request_stream`, `stream_waiting`, `start_stream`, `stream_preparing`,
`stream_started`, `stream_ready`, `cancel_stream_request`, `stop_stream`, `stream_ended`) stay on the lossless JSON path above. Binary
JPEG and PCM frames never touch the events queue: each socket delivers them on
two broadcasts, one per kind with its own small buffer (`MediaFeed`), which may
have several collectors and drop their oldest frame when a reader falls
behind. The rules:

- **Bounded at every hop, and video is dropped before audio.** The monitor
  drops a frame when OkHttp's queue is past a per-kind limit (OkHttp closes a
  socket whose queue passes 16 MiB); the hub drops per viewer by
  `bufferedAmount`, with a lower limit for video; the parent's socket and
  `LiveLink` buffer pictures and sound separately, so a picture backlog cannot
  push out sound. A stale picture is worthless; a gap in sound is what a parent
  notices.
- **Stream ids are checked at every hop.** The hub forwards only frames stamped
  with the monitor's current stream, and ignores `stream_started` /
  `stream_stopped` naming another one; the parent plays only frames for the
  stream it is playing; the monitor sends nothing for a stream that has ended,
  and nothing before it has announced `stream_started`.
- **Live view shares `AlertService`'s socket.** The hub allows one connection
  per device and closes the older one, so a second socket for live view would
  knock alerts offline. `AlertService` owns the `LiveSession` and forwards
  media into `LiveLink`; the screen never touches the socket. The same process
  hosts alerts, so nothing a stream contains may crash it: advertised formats
  are bounded by the hub's schema (<= 4096 px, 8-48 kHz), the parent reads a
  JPEG's header before decoding and refuses one far larger than advertised,
  and a player that cannot be built means no sound, not a crash.
- **Live view is foreground-only.** Leaving the screen sends `stop_stream`, and
  the monitor's camera runs only while someone watches: viewer leaves, last
  viewer's socket drops, monitor's socket drops, or the monitor service stops.
- **The hub ends silent connections.** A phone that leaves wifi or dies sends
  no close frame, and TCP takes many minutes to notice. The watchdog terminates
  any connection (either role) silent past the 45 s liveness window, so a
  vanished parent stops being a viewer and the camera turns off. `MONITOR_LOST`
  is still driven by the device's last-seen time, unchanged.
- **The monitor advertises only what it captures.** Video counts once the
  camera has delivered a frame (bind returns before the camera opens); audio
  only while the recorder runs. Silence from a microphone that is not recording
  would read as a quiet room.

**Camera orientation follows gravity (2026-10-03).** The monitor service owns
an `OrientationEventListener`, so it learns the mount before the camera opens
and while the display is locked. CameraX's target rotation follows that sensor
with hysteresis near diagonals; unknown orientation (a flat phone) keeps the last
known rotation, defaulting to natural portrait. JPEG pixels stay unrotated on
the monitor. The proving frame determines the initial rotation, and a changed
frame rotation reannounces `stream_started` for the same stream before its
JPEG. The hub already relays that as `stream_ready`, retaining it for later
viewers. The parent accepts matching-stream format updates without reopening
audio or restarting the camera. Control and media have separate receive paths,
so a turn can briefly display a frame with the previous rotation.

**Live-view controls and gallery snapshots (2026-10-09).** Back leaves watching;
there is no separate Stop watching button. A toolbar below the picture holds
photo capture, front/back camera switching (a camera with circular arrows), view
reset and rotation in both orientations. The battery remains over the video and
includes a charging bolt. A top-right information button shows battery temperature (not
room temperature), battery percentage, charging state, and media speaker volume.
Optional heartbeat fields carry these readings; `/monitors` exposes them from
the active connection, with nulls for unavailable/offline/older devices.

The camera button saves the current decoded live frame to the parent's
`MediaStore.Images` camera roll (`DCIM/KidsMonitor`), at stream resolution with
monitor and manual video rotation applied, without UI overlays or zoom/pan.
It rejects pictures older than five seconds and serializes saves. JPEG encoding
and pending-row publication run off the main thread; explicit saves finish even
on navigation, and failures remove the pending entry. No gallery-read permission,
automatic capture, hub photo storage, or new camera session is needed. Details
and verification are in [live-view controls](docs/guides/live-view-controls.md).

**Parent camera selection (2026-10-09).** Live view can switch the monitor
between its front and back cameras. Video formats advertise the active lens and
available lenses; older monitors omit these fields and the switch is disabled.
The hub permits only current viewers, serializes one switch per shared stream,
and correlates a bounded result with the requesting parent. All viewers receive
the changed video format. The monitor keeps the stream id, audio capture, noise
detection and voice session while rebinding CameraX. It confirms video only after
a first frame, rejects callbacks from the previous camera, and attempts to restore
the previous lens if opening the requested one fails. Selection lasts for the
monitor service's lifetime and defaults to back after a service restart. Front
frames are shown without selfie mirroring. See [live-view controls](docs/guides/live-view-controls.md)
for usage and the remaining hardware verification.

**Monitor battery is shown in the parent list and live view (2026-10-03).**
The monitor samples battery and thermal state on connection and every 15 s
heartbeat, rather than caching them when its notification changes. The parent
polls the existing `/monitors` endpoint every 15 s while visible, including while watching;
it refreshes immediately on resume/reconnect/Refresh and pauses while hidden;
a changed battery reading can take about 30 s to display. Missing readings,
offline monitors, a disconnected parent or a failed poll show battery unavailable.
The live screen paints a black background behind the status bar/cutout with
white system icons and restores the prior icon appearance on leaving.

Pixel checks confirm the battery display, status bar in both parent orientations,
locked-monitor live playback and camera release. The full physical four-position
rotation sequence remains to be checked; see
[progress](docs/planning/progress-monitor-usability.md).

**Parent Events (2026-10-09).** The dashboard feed is named Events and includes
noise, movement, monitoring start/stop, offline, and back-online events. All devices
and All events are the defaults; device and event-category filters apply in SQLite
before paging. Android Paging 3 loads older history on scroll in pages of 50, with
100 initially and a bounded loaded window targeting 200; saved history is retained
on disk and remains available offline. Filter changes return to the Events heading.
Parent schema 4 adds device/type indexes and preserves history. Manual refresh and
reconnect recovery drain all hub cursor pages. There is no read/unread state.
The hub records MONITOR_ONLINE once after a reported 45-second liveness outage;
first connections and quick reconnects are quiet. Hub schema 3 persists loss markers
across restarts, including pending losses from the previous schema. A reconnect after
a long hub outage preserves the offline/online pair even before the watchdog runs.
Only the hub generates connection events. Recovery is green in the feed and a quiet
Android notification, clearing that monitor's outstanding offline notification.
Every event notification title identifies its source device. The parent service
loads names through `/monitors` on reconnect, caches them per pairing, and resolves
unknown devices in a separate ordered notification worker; HTTP lookup cannot block
socket control or media. Failed lookup falls back to a shortened monitor ID.
See [Events behavior and loading decision](docs/guides/events.md).

**The Alerts screen includes a 24-hour noise timeline (2026-10-03, deployed to the home hub and paired Pixels).**
The monitor summarizes its existing audio windows into at most 15-second
intervals containing mean energy level, peak RMS level and the effective
adaptive threshold. It sends only numeric metadata via `noise_level`; audio
is neither uploaded nor stored for the chart. Calibration reports a null
threshold. Capture pauses split intervals rather than inventing silent samples.
The hub retains levels for two days and exposes the last day through authenticated
`GET /monitors/:id/noise-history`, with an incremental `fromMs` cursor and the
day's alert markers. The parent persists levels and chart markers separately
from the alert-delivery cursor, polls history once a minute while the Alerts
screen is visible, and reloads on resume/reconnect/Refresh. It plots 15-second
peaks in dBFS, measured noise-alert peaks, and the latest adaptive alert level
as a horizontal dashed reference; historical settings remain available on tap.
Grey intervals explicitly mean no measurements, including history from before
collection was enabled. Alert history remains below the chart. Hub and parent
schema migrations preserve existing data.
See [implementation and verification](docs/planning/progress-noise-history.md).

**Parent dashboard simplification (2026-10-08).** The smoothed curve now averages
the same 15-second peaks as the raw trace, in the displayed dBFS domain, rather
than comparing mean energy levels with peaks. It uses a centered, cosine-weighted
window of 30/60/180/360 minutes for the 1/3/12/24-hour views, integrating the
tapered weight over measured durations, with one curve knot per quarter window.
The default three-hour view has about 13 knots for continuous capture. Raw
peaks always draw faintly behind the trend, alert reference, and event markers.
The Y-axis fits visible measured peaks, trend values, alert peaks and the latest
alert level, with padding and a minimum 20 dB span. The dashed reference is a
horizontal line at the latest adaptive alert level; it is labelled Latest alert
level rather than implying it was the setting throughout history. Historical
settings remain available when tapping a time. The bottom-right Sound level
guide explains adaptive thresholds, sustained detection and smoothing, and
provides sourced everyday acoustic examples on their separate dBA scale; see
[reading the chart](docs/guides/reading-noise-chart.md). The
phone chart measures uncalibrated dBFS; it cannot be mapped to those examples. This is a visual peak trend,
not an RMS energy measurement. Coverage is the exact union of recorded sample
intervals: every positive gap retains its true duration as a grey band, and both
raw and smoothed strokes are clipped to measured coverage. Missing time is never
compressed, bridged, or counted as measured silence. Scheduling gaps up to 250 ms
can share an averaging window, with no weight for missing time and no connecting
stroke; longer capture gaps start separate averages. Overlaps are clipped instead of
splitting the curve or counting a measured duration twice. Shape-preserving
interpolation avoids flattening the curve at every knot or inventing peaks. Alerts have no read/unread state, count, or
acknowledgement action. Parent schema version 3 removes that state while preserving
alert history, noise measurements, and chart markers. Duration remains event
metadata but is omitted from alert rows and notifications. Battery is informational;
the top-right Status button is the dashboard's only status-sheet entry point.
The device card contains its name, connection status, battery, and Watch live
button. Loudness readings and the live level bar are omitted; the noise timeline
and alerts provide the sound history.

**Parent voice to the monitor (2026-10-08).** In a foreground live view, the
parent can toggle Talk to monitor. Microphone permission is requested on first
use; capture starts only after the monitor acknowledges speaker readiness.
The hub authorizes the current viewer, allows one speaking parent per monitor,
and relays bounded PCM16 (16 kHz mono) on a distinct talk id. The monitor prefers
its built-in speaker, checks the actual output route during playback, temporarily
raises media volume to at least 80%, and restores the previous setting unless
someone changed it. The speaking parent's room-audio playback pauses to prevent
feedback; video and monitor noise detection continue. Stop talking, leaving or
locking live view, stream termination, socket loss, microphone/playback failure,
or three seconds without voice frames ends speech. Voice never auto-resumes
and no audio is stored. See [using and verifying voice](docs/guides/talking-to-monitor.md).

**Verified on hardware (M5, Pixel 6 monitor on Android 16, Pixel 8 Pro parent on
Android 17):** capture continues through a locked, dozing screen; a real alert
reaches the locked parent phone; a reboot raises `MONITOR_LOST` in 40 s and the
monitor stays down until someone taps Start; 92 hours of continuous capture
without throttling.

**Verified on hardware (M4, same two phones, 2026-10-02):** the camera opens
from a hub message on a locked, dozing monitor (640x480, `rotationDegrees=90`,
picture upright on the parent); glass-to-glass video latency 0.1-0.2 s; audio
plays as a 16 kHz `AudioTrack`; the camera is released when the viewer presses
Home, locks the phone, is force-stopped (seconds) or silently loses wifi (51 s,
via the hub's silent-connection termination); a second viewer joins the running
stream without reopening the camera, and the camera stops only when the last
one leaves; noise detection still fires during a stream; 10 minutes of
streaming leaves thermal status 0 (31.7 -> 32.4 C).

**Not verified:** a monitor on Android 17, where background-audio hardening
applies; a monitor on battery; a second *real* parent phone (a fake viewer
stood in); a locked parent left idle for more than the hub's 45-60 s silence
window; the camera-busy and microphone-denied fallbacks on a device.

**Monitor onboarding names (2026-10-11).** An unpaired monitor first asks for
a recognizable name (1–64 characters after trimming), before QR scanning,
typed-code pairing, or redeeming an ADB-delivered debug payload. Naming is an
explicit step: the device model name is prefilled and editable, and tapping
Continue to pairing accepts the default or the edited name. No code is redeemed
before that confirmation, including a debug payload. iOS resolves known hardware
identifiers to model names and falls back to its system device-family name for
unlisted hardware. The name is shared by both pairing screens and can be changed before an attempt or after failure.
Successful pairing stores the same name locally and on the hub; the monitor
dashboard and socket hello use the stored name after restart. Existing monitors
keep their saved names, falling back to the model if they predate naming.
Device IDs remain the identity; the hub does not enforce unique display names.
Setup agents ask for a distinct name and verify it in the parent's device list.
See [installing a monitor](docs/guides/installing-monitor.md#choose-a-name).

**iOS monitor (2026-10-12).** `apps/ios/KidsMonitor.xcodeproj` builds a SwiftUI
monitor using the current Android monitor's dashboard and hub protocol. Pairing
is QR or typed-code with certificate confirmation, names are explicit, and
credentials use a device-only Keychain item available after first unlock.
AVAudioEngine converts capture to 16 kHz mono; detection/calibration/history
follow Android. AVFoundation supplies on-demand JPEG video with gravity-based
pixel orientation, first-frame readiness, front/back switching and restoration.
Talkback uses the built-in speaker with bounded voice buffering and a 3-second
silence timeout. Voice temporarily selects normal audio processing rather than
measurement mode, which attenuates output, and restores measurement mode when
speech stops. Queue slots are released on rendering, so output-presentation
latency does not discard fresh speech. The video-only camera does not configure
the shared audio session. The Android parent remains the test client.

Parity has explicit platform limits: this app supports background audio but
releases video and talkback on background/lock, keeps the display awake while
monitoring, and requires foreground live-view retry for video. iOS exposes no
public battery temperature or system-volume setter; report null temperature and
actual speaker volume instead of claiming Android's automatic 80% volume.
Audio interruption/capture failure ends monitoring and requires Start. No capture
auto-starts on launch/reboot. Source/build and verification details are in
[iOS monitor guide](docs/guides/ios-monitor.md) and
[implementation status](docs/planning/progress-ios-monitor.md).

**iOS parent (2026-10-12).** The separate `KidsParent` target uses the Android
parent as its feature/UI reference: light dashboard, monitor selection/status and
battery, 1/3/12/24-hour noise history, Events filters, and a dark live view in
portrait/landscape. TLS/QR/manual pairing share the Swift transport with an explicit
parent role and a separate device-only Keychain service. One socket receives
events, controls and bounded JPEG/PCM media. Local SQLite retains events/noise and
names offline; event filters apply before a bounded 100-row display window, with
Older/Newer buttons advancing 50 rows. Recovery pages advance a separate cursor
transactionally, so a newer live event cannot skip a missed history gap. Noise
coverage/trend/threshold follow Android, including exact grey gaps.

Live controls include mute, camera selection, pinch zoom/reset, explicit Photos
snapshots with add-only permission, and microphone talkback after speaker readiness.
Room audio pauses during speech. Viewer requests and controls carry correlation
ids; camera formats and media decoding are bounded, JPEG decoding holds at most
one active and one newest pending frame, and PCM playback has a bounded queue.
Foreground video startup has up to three attempts with phase/first-picture
responsiveness feedback, retry and sound-only controls. Leaving/backgrounding,
connection loss and audio interruption stop speech; voice never auto-resumes.

**iOS parent delivery is foreground-only.** No APNs/cloud service exists, and
iOS suspends ordinary LAN sockets in the background. Background/lock releases live
media and stops the socket; reopening reconnects and drains missed event history.
Status and the dashboard explain this limit. Local notifications are optional and
do not imply locked-parent delivery. No silent audio/VoIP background workaround is
used. See [iOS parent guide](docs/guides/ios-parent.md) for builds, pairing,
platform limits and verification.

## Security model

Adversary: anyone else on the home wifi.

- The hub generates a self-signed certificate on first run, into a Docker volume.
  **No key material lives in this repository.**
- Pairing is a QR code carrying host, port, certificate fingerprint, and a
  single-use six-digit code that expires in 5 minutes.
- **Or the code is typed.** Six digits cannot carry a fingerprint, so the app
  connects to the typed address with a trust-nothing client (confined to
  `HubProbe`), shows the certificate's first three bytes, and sends the code
  only once the person confirms them against the hub's `check:` line. A
  mismatch sends nothing, so the code survives for a real retry. The typed path
  also declares the role it expects, because a typed code carries no role; the
  hub answers 409 and keeps the code unused when they disagree.
- **Redemption is rate-limited** on the hub: five failures in a rolling minute
  lock it hub-wide, doubling to fifteen minutes, cleared by any success. That
  brake is what makes six digits safe (about 1 in 40,000 per code lifetime).
- The typed path asks for the local-network permission but never the camera.
  Asking for nothing looks tidier and does not work: on Android 17 a denied
  `ACCESS_LOCAL_NETWORK` makes every hub connection time out rather than fail.

**Verified on hardware (manual pairing, 2026-10-02, Pixel 8 Pro on Android 17):**
typed pairing completes with the camera permission denied; the six-character
check matches the hub's `check:` line; answering "they do not match" sends
nothing and the same code still pairs afterwards; a monitor code typed into the
parent app is refused with 409 and survives; five wrong codes lock redemption
for a minute without consuming a real code.
- Clients pin that exact fingerprint. Pinning is what makes a self-signed
  certificate on a LAN address trustworthy, and it means a DHCP address change
  breaks nothing.
- Devices hold a 256-bit random token in Android `EncryptedSharedPreferences` or
  an iOS device-only Keychain item. The hub stores only its SHA-256.
- **No unauthenticated endpoint exists**, including the media stream. Devices can
  be revoked; revocation drops the socket immediately.
- No media is recorded or stored. Events carry metadata only.

## Wire protocol

One endpoint, `wss://<hub>:8443/ws?role=monitor|parent`, bearer token on the
upgrade. JSON text frames for control, binary frames for media, with a fixed
12-byte header (`magic, type, streamId, timestamp`).

Full message list and REST surface: the Phase 1 design spec, §6 (kept outside this repository).

## Where things are headed

- **Phase 1** — monitor app, parent app, local hub. All Android, all LAN.
- **Phase 2** — parent app on iOS, implemented for foreground LAN delivery.
- **Phase 3** — internet relay for alerts away from home. The transport is behind
  an interface so this slots in without rewriting the apps.
