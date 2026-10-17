# Building and using the iOS parent

`apps/ios/KidsMonitor.xcodeproj` has a **KidsParent** scheme, separate from
**KidsMonitor**. The parent installs as `com.kidsmonitor.parent.ios` and uses its
own device-only Keychain pairing. Installing it preserves the monitor app and
all existing Android pairings. iOS 16 or later is required.

## Build and install

An already Xcode-paired iPhone/iPad can be installed and launched over Wi-Fi.
First-time developer pairing may require USB, depending on the OS/Xcode version;
see [device connections](device-connections.md). Hub QR pairing itself never
requires a cable.

Choose KidsParent in Xcode, your Apple development team, and a connected iPhone
or iPad. Keep it unlocked and Run to install and launch. If installation succeeds
but launching reports a locked device, unlock it and retry the launch.
The checked-in project is generated from `apps/ios/project.yml`;
regenerate it with `xcodegen generate --spec apps/ios/project.yml` after changing
targets. Team identifiers and provisioning are machine-specific and are not
saved in the project.

Simulator verification from the repository root:

```bash
swift test --package-path apps/ios
xcodebuild -project apps/ios/KidsMonitor.xcodeproj -scheme KidsParent \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath apps/ios/DerivedDataParent \
  SYMROOT="$PWD/apps/ios/DerivedDataParent/Products" \
  OBJROOT="$PWD/apps/ios/DerivedDataParent/Intermediates" \
  CODE_SIGNING_ALLOWED=NO build
```

## Pair with the existing home hub

Establish the actual SSH host, checkout, LAN address, running container,
persistent volume, and certificate using [recovering-hub.md](recovering-hub.md).
Building the parent does not require updating the hub.

For QR pairing, use the [agent-assisted QR walkthrough](pairing-with-qr.md),
which includes the verified Mac procedure and Linux/Windows portability limits.
The app starts with manual entry; tap **Scan a QR code** to open its scanner.
There is currently no setup-page URL. Display the QR on another screen and allow
camera access in the parent app, rather than scanning with the native Camera app.

1. Issue a **parent** code using [setup-hub.md](setup-hub.md#pair-a-device).
2. Enter the hub address, port and six-digit code in the parent app. Compare the
   six certificate characters with the hub's `check:` line before confirming.
   **They do not match** sends no code. Alternatively scan the parent QR; this
   requests camera permission only when opening the scanner and pairs directly
   using the fingerprint included in the QR, without the manual check screen.
3. Allow Local Network access. The parent is named with its phone model and
   “Parent,” for example **iPhone 13 Pro Parent**. Tokens are saved only in the
   device-only Keychain; certificate pins apply to HTTP and WebSocket traffic.
4. Keep the app open and confirm the name and advancing parent last-seen on the
   hub across two checks at least 20 seconds apart. Device status
   and noise data should load without clearing or re-pairing monitors.

For an installed **Debug** app, an operator can transfer the equivalent QR
payload without showing a code in logs:

```bash
python3 tools/ios/pair-parent.py \
  --ssh <actual-ssh-host> --checkout <absolute-deployed-checkout> \
  --host <actual-lan-address> --port <published-port> \
  --device <connected-device-identifier>
```

The script verifies the address printed by the deployed pairing command,
transfers a mode-0600 temporary file, removes its local copy, and launches the
app with `--debug-pair`. The app consumes and deletes the phone file. Never put
pairing material in source, screenshots, test attachments, or command arguments.
`--no-launch` leaves launching to the UI test; keep the code's five-minute
expiry in mind. This path is absent from Release builds and is only for a new,
unpaired parent. Use QR/manual pairing for ordinary installation.

## Dashboard and live view

The light dashboard follows the Android parent: device chips, availability,
battery/charging, Watch live, a sound timeline, Events, and Status. Device status
polls every 15 seconds while foregrounded; noise history refreshes once a minute
and on resume/Refresh. Failed status is unknown, rather than falsely offline.

The timeline has 1/3/12/24-hour views, defaulting to three hours. Raw 15-second
peaks sit behind a cosine-weighted duration average in dBFS. Its automatic axis
includes alert peaks and the latest threshold. Grey intervals are exact missing
coverage; neither raw nor trend lines bridge them. Tap to inspect historical
peaks and thresholds. See [reading-noise-chart.md](reading-noise-chart.md).

Events retain noise, movement, monitoring start/stop, offline and back-online
history in local SQLite. All devices and All events are the defaults. Filtering
happens before paging. The visible window holds at most 100 events; Older/Newer
buttons move by 50, rather than Android's automatic scroll paging. Saved events
and noise remain available offline. Reconnect/Refresh drain every HTTP cursor
page; a live event cannot advance the recovery cursor and skip older events.
There is no read/unread state or acknowledgement action.

Watch live uses the same parent socket as events. The dark portrait/landscape
screen supports mute, front/back camera switching, pinch zoom (double-tap to
reset), snapshots to Photos, device information, retry and sound-only playback.
Photos requests add-only access when explicitly saving; there is no automatic
capture. Talk requests microphone access on first use and captures only after
speaker readiness. Room playback pauses while speaking and resumes when voice
stops. Navigation, background/lock, connection loss, interruption and stream
termination release camera/viewing and voice; voice never automatically resumes.

## iOS delivery limit

**Keep this LAN-only parent open to receive live alerts.** iOS suspends ordinary
apps in the background, and this hub has no APNs/cloud delivery. The app stops
its socket and live media on background/lock and backfills missed events when
reopened. It does not claim Android's foreground-service or locked-parent alert
parity. Status explains this limit and can request local event notifications;
those do not provide background delivery. Existing Android parents continue to
receive events independently.

Apple documents [background suspension](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time)
and [Photos authorization](https://developer.apple.com/documentation/photos/phphotolibrary).

## Verification

Start the disposable loopback fixture in another terminal:

```bash
npm run typecheck
node tools/ios/test-hub.mjs
```

Run ParentTests on an installed simulator, substituting its actual identifier:

```bash
xcrun simctl privacy <simulator-id> grant microphone com.kidsmonitor.parent.ios
xcodebuild -project apps/ios/KidsMonitor.xcodeproj -scheme KidsParent \
  -destination 'platform=iOS Simulator,id=<simulator-id>' \
  -derivedDataPath apps/ios/DerivedDataParent \
  SYMROOT="$PWD/apps/ios/DerivedDataParent/Products" \
  OBJROOT="$PWD/apps/ios/DerivedDataParent/Intermediates" \
  CODE_SIGNING_ALLOWED=NO -only-testing:ParentTests test
```

The fixture uses temporary data and loopback ports 18555/18556. Stop it with
Ctrl-C to remove temporary data. Core tests check role parsing, exact chart
coverage, overlap clipping, gap separation and peak averaging. ParentTests
exercise SQLite cursor persistence/filtering/paging and the actual hub's parent
socket, events, history, video orientation, camera and voice relay.

ParentHardwareTests additionally runs real hub/media acceptance on a physical
phone without UI automation: JPEG decoding, rendered PCM, camera switching,
microphone PCM sending, explicit Photos saving and background/reconnect cleanup.
It selects the first online monitor, or the name set by `KIDSMONITOR_TEST_MONITOR`.
Grant microphone and add-only Photos permission when prompted.

ParentUITests runs on a paired physical phone, records dashboard/live layout
attachments, and exercises live picture, mute, camera, microphone/voice controls,
rotation and Status. It skips if no paired dashboard exists, so check for **zero
skips** before claiming hardware acceptance. Simulator tests do not prove
physical acoustics, Wi-Fi outages, long-running reliability,
or lock/unlock recovery. Keep development provisioning valid.

For a paired physical phone, run the hardware and UI acceptance selectors
(the hardware test saves one explicit snapshot to Photos using add-only access):

```bash
xcodebuild -project apps/ios/KidsMonitor.xcodeproj -scheme KidsParent \
  -destination 'platform=iOS,id=<phone-udid>' \
  -derivedDataPath apps/ios/DerivedDataParentDevice \
  SYMROOT="$PWD/apps/ios/DerivedDataParentDevice/Products" \
  OBJROOT="$PWD/apps/ios/DerivedDataParentDevice/Intermediates" \
  DEVELOPMENT_TEAM=<your-team-id> -allowProvisioningUpdates \
  -only-testing:ParentTests/ParentHardwareTests \
  -only-testing:ParentUITests test
```

Keep the phone unlocked. UI acceptance requires **Settings → Developer → Enable
UI Automation**. The hardware XCTest can verify media independently of that
setting. It releases its stream and voice before finishing.
