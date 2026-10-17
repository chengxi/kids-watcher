# Parent live-view controls

Tap **Watch live** on a connected monitor. The top-left Back arrow (or Android
Back) leaves the live view and stops watching. Locking the parent phone or
switching apps also stops watching and voice transmission. The camera closes
when the last viewer leaves.

## Battery and device information

The live picture shows the monitoring phone's battery icon. A lightning bolt
beside it means the phone reports charging or fully charged on power. Tap the
**ⓘ** button in the top-right corner for battery temperature in °C, battery
percentage, charging state, and media speaker volume. Temperature is the Android
battery sensor, not room temperature. Speaker volume is the monitoring phone's
media volume, including the temporary boost while using **Talk to monitor**;
it is not the room noise measurement.

These values travel in the existing monitor heartbeat and `/monitors` response.
The monitor samples on connection and every 15 seconds; the visible parent polls
every 15 seconds, so a changed reading may take about 30 seconds to appear.
Unavailable, disconnected, or older monitors display **Unavailable** rather than
made-up values. The hub holds these readings in the current connection only;
there is no new database or migration.

## Front and back cameras

The toolbar below the live picture holds the photo, camera-switch, reset, and
rotation icons in both orientations. These controls do not cover the live video;
only the battery indicator remains over the picture. Tap the camera icon with
circular arrows inside it to switch between front and back cameras. Its
accessibility label names the destination camera. The active lens is labelled
outside the picture. The button is enabled
when the monitor advertises both cameras and the hub is connected. During a
switch the icon shows a spinner, **Switching camera…** appears outside the
picture, and repeated taps and photos are disabled.
Audio, noise detection, and Talk to monitor continue through the video pause.

All parents watching the same monitor see the same selected camera. The hub
allows one switch at a time; another parent's overlapping request shows a retry
message. If the requested camera does not open or deliver a frame in time, the
monitor attempts to restore the previous camera and reports the failure. If
neither camera opens, use **Retry video**. A missing response ends the switch
indicator within 35 seconds. Leaving live view still releases this viewer;
the camera closes when the last viewer leaves.

The selection lasts until the monitor service restarts, including between live
views. Restarting monitoring defaults to the back camera. Front-camera video is
not selfie-mirrored. Rotation and photo saving apply to either camera as usual.
Install the updated hub and both apps to use this feature; retain app data and
pairing. Older monitors can still stream but do not enable this control.

Camera-switch verification must include back → front → back on the paired
phones, both parent orientations, a locked monitor, correct video orientation
and photos from each lens, uninterrupted room audio and voice, and camera
release on leaving. Automated tests cover protocol compatibility, authorization,
shared viewer updates, timeouts, restoration, stale callbacks, and audio-player
continuity. See the camera and toolbar hardware check below for verified behavior
and remaining gaps.

Local verification on 2026-10-09 passed both APK builds, 126 shared tests (four
live integration tests skipped), 117 monitor tests, 186 parent tests, TypeScript
type checking, 199 Node tests, 41 cross-language protocol checks, the secret
scan, and `git diff --check`. Portrait and landscape controls were rendered and
visually inspected. Run Node tests with
`npm test -- --exclude '**/.claude/**'` when this checkout contains nested agent
worktrees; the default discovery also tries to run those unrelated checkouts.

## Save a photo

Tap the photo icon at the left of the toolbar below the live picture. It saves the
current decoded monitor picture as a JPEG in the parent phone's camera roll,
under **DCIM/KidsMonitor** (the KidsMonitor album in gallery apps). The success
message confirms publication. This uses the live stream's resolution, currently
640 × 480 before orientation; it does not request a separate full-resolution
camera exposure. Monitor orientation and the parent's Rotate control are applied.
Zoom, pan, buttons, and status overlays are not baked into the photo.

The button requires live video and a hub connection; the save action rejects a
picture decoded more than five seconds ago. One save runs at a time. Once the
parent taps the shutter, leaving the screen does not cancel that save. A failed
write or publication removes its pending gallery entry and shows a retry message.
No photo is saved automatically or stored on the hub. Standard Android
[MediaStore](https://developer.android.com/training/data-storage/shared/media)
provides gallery publication without requesting broad photo-library access
(parent minSdk 29).

## Verification after changes

- Run the shared, monitor, and parent Android unit tests and assemble both apps.
  `LivePhotoStoreTest` verifies JPEG output, orientation, publication order, and
  cleanup after write/publication failure. Run the Kotlin/TypeScript protocol
  conformance check; optional heartbeat fields preserve old-client support.
- Run hub tests and type checking. `monitor-status.test.ts` verifies heartbeat
  readings, zero speaker volume, unavailable readings, and removal on disconnect.
- Install both apps without clearing their data. Restart monitoring from the
  unlocked, visible monitor app; check both paired devices' advancing last-seen
  timestamps and fresh noise samples, following [recovery](recovering-hub.md).
- On the parent, check live video, the charging bolt, the information dialog,
  photo-save confirmation, and a non-pending JPEG in the parent camera roll.
  Rotate video and save another photo to verify dimensions. Back must release
  watching and the monitor camera. Keep private photos on the phone.

## Deployment compatibility

Deploy the hub's status fields and camera controls, then the monitor and parent apps.
An old monitor continues heartbeating normally but cannot supply the extra
readings. An old parent ignores the extra response fields. Preserve pairing,
TLS material, and the hub data volume as in [deployment](deploying.md).

## Hardware verification, 2026-10-09

The updated parent app was installed on the paired Pixel 8 Pro, and the updated
monitor app on the paired Pixel 6 without clearing pairing. Monitoring was
restarted from the visible, unlocked Pixel 6. Its foreground microphone service,
advancing last-seen timestamps for both paired phones across checks more than
20 seconds apart, and advancing noise samples were verified against the hub host.
The hub certificate fingerprint was unchanged and its database check returned
`ok`.

A complete 480 × 640 JPEG from the updated app was present in the Pixel 8 Pro's
`DCIM/KidsMonitor/` album, with a nonzero size and `is_pending=0`. The monitor had
no active camera client after the parent locked. A later unlocked-phone check confirmed the live charging bolt and information
dialog: battery temperature 28.3 °C, battery 100%, fully charged on power, and
speaker volume 76%. The Stop watching button was absent. Rotating the view 90°
and tapping the camera showed the saved confirmation and published a second,
640 × 480 JPEG in the same album with `is_pending=0`. Back returned to the chart
and released the monitor camera (no active camera clients). Automated rotation,
publication, and failure-cleanup tests pass, along with 186 hub tests and the
Android suites (124 shared passed plus four skipped, 110 monitor, 174 parent
including the subsequent exact-gap chart change).


## Camera and toolbar hardware check, 2026-10-09

The camera relay was deployed to the hub host at `d73a287`, based on the existing
`330e1b0` deployment, with only camera protocol, relay and test changes. Both APKs
were installed with `adb install -r`, preserving pairing. The existing data
volume, certificate fingerprint and deployment environment remained unchanged;
SQLite quick-check returned `ok`. After the phones were unlocked and monitoring
restarted, two checks showed advancing last-seen timestamps for both paired
Pixels and fresh monitor noise samples. Both foreground services were active.

The updated toolbar was installed on the Pixel 8 Pro and inspected with a real
live feed: photo, camera switch, reset and rotation are below the video; the
battery remains inside its top-right corner. Portrait and landscape renders
were also visually checked, and the parent APK build and four render tests
passed. Front/back switching opened CameraX devices 1 and 0 while the Pixel 6
was locked. The parent received FRONT/BACK and the corresponding rotation
updates on the same stream, retaining the 16 kHz audio format. Locking the parent
ended viewing and left no active monitor camera client, while monitoring and
noise samples continued.

A physical landscape inspection was interrupted by the parent locking; its
original auto-rotation preferences were restored. New gallery photos from both
lenses and audible room-audio/voice continuity were not checked in this run.
