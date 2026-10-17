# Monitor mounting and parent usability

**Date:** 2026-10-03
**Status:** built, tested and installed; parent status bar, battery and live playback verified on the Pixels; physical four-position rotation check pending.

## Changes

- A service-owned `GravityOrientation` uses `OrientationEventListener` independently
  of the screen. It updates CameraX `targetRotation` for portrait, either landscape
  direction and upside-down portrait. A 15 degree margin around diagonal boundaries
  prevents jitter. Unknown orientation keeps the last usable mount; natural
  portrait is the fallback when no usable reading has arrived.
- Camera frames carry their actual `ImageInfo.rotationDegrees` into
  `StreamController`. The proving frame supplies the initial rotation. When a
  subsequent frame changes rotation, the controller reannounces the format before
  sending its JPEG. It preserves audio and stream identity. The existing hub
  relays repeated `stream_started` as `stream_ready`, so no protocol schema or
  hub behavior change is needed. The parent now applies matching-stream updates.
  The monitor still does no pixel rotation or re-encoding for orientation.
- The parent live view paints black behind the status bar and top cutout, uses
  white system icons, and restores their previous appearance on leaving. The
  picture fits the available area so a tall portrait frame remains visible above
  the bottom system bar.
- Monitor battery appears in the parent list and live view. The monitor reads
  battery and thermal state on connection and every heartbeat, fixing the prior
  notification-driven snapshot. The existing `/monitors` poll stays active while
  watching. Heartbeats and polling each run every 15 s (up to about 30 s for a
  changed battery percentage to appear). Missing or invalid readings and unavailable
  connections show battery unavailable; a failed poll clears stale availability.

Implementation references: [CameraX rotations](https://developer.android.com/media/camera/camerax/orientation-rotation)
and [Compose system insets](https://developer.android.com/develop/ui/compose/system/insets).

## Verification

- `npm run typecheck`: passed.
- Full TypeScript suite: 167 tests passed.
- Relay suite: 17 tests passed, including rotation updates to an existing
  viewer and retention of the latest rotation for a viewer joining later.
- All changed Kotlin main sources, including Compose UI, compiled with cached
  Kotlin 2.4.20 and the Compose compiler plugin, Android API jar and dependencies.
- 62 JUnit tests passed using that compilation: gravity orientation, stream
  controller, parent live session, battery display and monitor socket. The socket
  regression uses two virtual heartbeat intervals over a real local WebSocket to
  prove it refreshes both battery and thermal values.
- `git diff --check`: passed.
- `npm run secrets:scan`: clean.

Normal Gradle verification was attempted with:

```sh
cd apps/android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :shared:testDebugUnitTest :monitor:testDebugUnitTest \
  :parent:testDebugUnitTest :monitor:assembleDebug :parent:assembleDebug --console=plain
```

The first attempt stalled reading SDK files on an external drive. After
filesystem access was granted, the same command completed successfully in 37 s.
All three module suites passed: shared 122 tests (4 live tests skipped), monitor
102, parent 113; zero failures or errors. Both debug APKs were packaged.
`tools/check-protocol-conformance.mjs` also passed all 26 encodings and constants.

The monitor APK was installed on the paired Pixel 6 with `adb install -r`,
preserving credentials. Its listening service was restarted from the visible
status screen and reconnected to the home hub (`capture started at 16000Hz`,
then the hub's detection config arrived). The home hub was not updated.

With the Pixel 6 locked (`mWakefulness=Dozing`), noise-level logging continued.
`dumpsys sensorservice` shows this app's `OrientationEventListener` still
registered on the accelerometer, with sensor access allowed and a 200 ms
sampling period. This verifies service/sensor registration through screen lock;
physical mount transitions and the streamed picture still need checking.

The Pixel 8 Pro disconnected from ADB during the build, then reconnected. Its
parent APK was installed successfully with `adb install -r`, preserving pairing.
It initially used a different Wi-Fi subnet from the monitor and hub. After the
user switched it to `Home WiFi` (`192.168.1.37`) and unlocked it, restarting the
parent app established its connection immediately (avoiding the reconnect
backoff from the previous unavailable network).

## Verified on hardware

| Check | Evidence |
| --- | --- |
| Battery shown in both parent screens | Monitor row and live view both showed 84%, matching the Pixel 6's `dumpsys battery` reading; live view subsequently refreshed to 85%, again matching the device |
| Unknown connection state does not claim a battery reading | While the parent socket was disconnected, the monitor row showed "Offline · Monitor battery unavailable" |
| Video opens on a locked monitor | Pixel 6 was Dozing; Watch opened camera 0, reported 640x480 with gravity-selected rotation 0, and displayed the scene on the parent |
| Black status bar and readable icons | Parent screenshots in portrait and landscape show black behind time, notifications and system battery, with white icons |
| Picture fits the available area | Portrait and landscape screenshots show the complete scene, without the earlier landscape top/bottom cropping |
| Leaving restores the normal status bar | Back returned to Alerts with black icons over the normal app background |
| Leaving releases the camera | Monitor log reports `camera stopped`; `dumpsys media.camera` reports no active client and camera 0 closed |
| Listening continues | Noise-level logging continued throughout playback and after camera release; a missed noise event appeared in the parent after reconnect/backfill |

Parent screenshots were inspected locally under
`/tmp/kidsmonitor-device-verification/`; room images were not added to the repo.
The parent's original rotation settings (`accelerometer_rotation=1`,
`user_rotation=0`) and USB stay-awake setting (`stay_on_while_plugged_in=0`)
were restored after testing. Live view was reopened for the physical rotation
check; its own keep-screen-on behavior applies while watching.

## Hardware checks still needed

1. With the monitor locked and its auto-rotate disabled, physically turn it through
   portrait, both landscape directions and upside-down portrait. Confirm the
   parent picture settles upright, including turns during one uninterrupted
   live stream. Control and media have separate receive paths, so a frame may
   briefly use the preceding rotation. The four positions and stream updates
   pass unit tests; the full physical sequence is awaiting user feedback.
2. Confirm audio continuity by ear during physical turns. The display refreshed
   from 84% to 85% during verification without a reconnect; its value matched the
   device at both observations. Exact heartbeat/poll delay was not timed on hardware.

A manual rotate control for a monitor initially lying flat remains a separate
follow-up; gravity cannot determine that picture's upright direction.
