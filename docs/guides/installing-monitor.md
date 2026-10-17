# Installing an additional Android monitor

The monitor supports Android 12 (API 31) and newer. The parent and existing
monitors keep their pairing when another monitor is added.

## Build and install

Connect the phone to the hub's Wi-Fi and unlock it. Authorize a developer
connection using either Android Wireless debugging or USB debugging; USB is not
mandatory on Android 11+. See [device connections](device-connections.md) for
the wireless pairing steps. For USB, enable developer options and USB debugging,
connect the cable, and approve this computer on the phone.

From the repository root, discover the device identifier and build. The
`<monitor-serial>` below is the identifier from `adb devices -l`, including when
the connection is wireless:

```bash
adb devices -l
cd apps/android
./gradlew :monitor:assembleDebug :monitor:lintDebug :monitor:testDebugUnitTest :shared:testDebugUnitTest
cd ../..
adb -s <monitor-serial> install -r apps/android/monitor/build/outputs/apk/debug/monitor-debug.apk
adb -s <monitor-serial> shell am start -n com.kidsmonitor.monitor/.MainActivity
```

Use the Android Studio bundled JDK if the shell's Java is incompatible. If `adb`
is missing from PATH, locate `platform-tools/adb` under the SDK directory in
`apps/android/local.properties`.

## Choose a name

Before pairing, ask the user what this monitor should be called unless they
already provided a name. Suggest a device name such as **Pixel 6**, **Blue Pixel**, or **iPhone 13 Pro**.
Monitors can move between rooms, so prefer a label that travels with the phone. Compare with the existing monitor names
in the parent's device list or the hub's `devices.js list` command from
[setup-hub.md](setup-hub.md#list-and-revoke-devices). Two Pixel 6 phones should
have distinct names; do not silently use the model or serial.

The app opens **Name this monitor** before QR, typed-code, or ADB pairing.
Accept the prefilled device model name by tapping **Continue to pairing**,
or edit it first (1–64 characters). Outer whitespace is removed. The same name follows both pairing screens; **Change monitor name**
returns to the naming step before an attempt or after a failure. Allow camera
and microphone access when requested. Newer Android versions also request
notification and local-network permissions when applicable.

## Pair and start

Confirm the existing hub address and deployment first using
[setup-hub.md](setup-hub.md) and [recovering-hub.md](recovering-hub.md).
For this development build, the existing helper sends a fresh single-use
pairing payload while preserving certificate pinning:

```bash
tools/pair-device.sh monitor <monitor-serial>
```

The ADB helper also waits for **Name this monitor** on the phone; it does not
register the phone automatically under its model name. Complete the naming step
within five minutes of issuing the code, or issue a fresh code with the helper.

The helper requires `HUB_SSH` and uses `~/kids-monitor`; set `HUB_SSH=user@hub-host`
and `HUB_DIR` for another installation. Keep pairing payloads out of logs.
For normal QR/manual setup, follow [setup-hub.md](setup-hub.md).

When paired, tap **Start monitoring** while the app is visible. Confirm the
microphone and home hub report connected/listening. After a phone reboot,
unlock it, open the monitor and tap Start again.

## Verify from the parent

Open the already-paired parent. Both monitors should appear in its device
selector with the chosen names; select each and confirm Online. Names are
display labels, not unique identifiers enforced by the hub, so check that the
new name is distinct. Use the two snapshots in
[recovering-hub.md](recovering-hub.md) at least 20 seconds apart to verify
advancing last-seen times for both monitors and the parent, and fresh noise
samples from both monitors.

Select the new monitor and tap **Watch live**. Check the picture and audio,
then return home and confirm the monitor camera stops while listening continues.
Repeat with the monitor's screen off. See
[live-view-controls.md](live-view-controls.md) for front/back camera controls.

## Pixel 3 verification — October 9, 2026

Installed the debug monitor on a factory-reset Pixel 3 running Android 12
(API 31), after lowering the monitor's minimum SDK from 33 to 31. Camera and
microphone permissions, pairing, foreground capture at 16 kHz, noise samples,
and both online device selectors were verified with the existing Pixel 6 and
Pixel 8 Pro. The parent displayed live video at 640 × 480 and had an active
16 kHz audio playback track with advancing frame counts. Front/back camera
switching delivered pictures; leaving Watch released the camera. A fresh Watch
session delivered video and active audio playback while the monitor was dozing
with its screen off, and noise samples continued after leaving Watch.

Build, monitor/shared unit tests, and lint passed (one existing missing-icon
warning). Long unattended operation, subjective speaker sound quality, and
deliberate loud-noise notification delivery remain untested on the Pixel 3.
