# M5 Progress and Handoff

**Plan:** `2026-09-10-m5-monitor-android-app.md` (kept outside this repository)
**Spec:** `2026-09-09-phase-1-lan-monitor-design.md` (kept outside this repository)
**Branch:** `feat/m5-monitor-app`
**Hub:** `https://192.168.1.100:8444`
**Devices:** monitor Pixel 6 (`<pixel-6-serial>`, SDK 36); parent Pixel 8 Pro (`<pixel-8-pro-serial>`, SDK 37)

Anyone picking this up: read the plan, then "Deviations" and "Notes" below, then
start at the first task not marked done.

**Before touching Android code**, read the trap table in
[`../README.md`](../README.md). Three of those five failures are silent.

## Status

| Task | Title | State |
| --- | --- | --- |
| 1 | `:shared` monitor half of the protocol | **done** |
| 2 | `:shared` role-agnostic socket | **done** |
| 3 | `:monitor` module scaffold | **done** (launch verified on emulator, not yet the Pixel 6) |
| 4 | Detection core (level + noise detector) | **done** |
| 5 | Calibration | **done** |
| 6 | Monitor foreground service | **done** (unit-tested; capture unverified until Task 9) |
| 7 | Monitor UI | **done** |
| 8 | `tools/fake-parent` CLI | **done** |
| 9 | On-device verification on the Pixel 6 | **done** |

## How to verify where things stand

```bash
cd apps/android && ./gradlew :shared:testDebugUnitTest :parent:testDebugUnitTest :monitor:testDebugUnitTest
```

```bash
npx vitest run && node tools/check-protocol-conformance.mjs
```

## Deviations from the plan

Recorded as they happen, with the reason. A deviation with no reason is a bug.

### Task 1 — the conformance check found real drift on its first monitor run

`peakDb` and `durationMs` were `.optional()` in the hub's zod schema, which
accepts the key being *absent* but not *null*. Kotlin emits explicit nulls — and
it has to, because `batteryPct`, `video` and `audio` in the same direction are
`.nullable()` and are rejected when the key is missing. A single
`Json { explicitNulls }` setting cannot satisfy both, so **every event the
Android monitor sent would have been rejected by the hub.**

**Changed:** the schema now uses `.nullish()` for those two fields. Absent and
null mean the same thing here — no peak was measured — and clients legitimately
encode it either way. Three tests cover both encodings and confirm a non-numeric
peak is still rejected.

The alternative was a custom Kotlin serializer that omits nulls for this one
type. That is machinery to preserve a strictness that bought nothing.

**Worth noting how this was caught:** not by any Kotlin test and not by any
TypeScript test — both passed. Only `tools/check-protocol-conformance.mjs`,
which parses Kotlin's actual output with the hub's actual schema, could see it.

### Task 2 — how the socket refactor was kept honest

`HubSocket` and `MonitorSocket` are now thin wrappers over an internal
`RoleSocket`, which owns the reconnect loop, the backoff, the heartbeat and the
"ignore undecodable frames" rule. Only three things differ per role: the query
string, the hello message, and the inbound decoder.

**`HubSocketTest` passes unedited.** That was the acceptance criterion, and it
is worth stating plainly: a refactor whose tests had to be adjusted to keep
passing has not been shown to preserve behaviour. `git status` on that file is
clean.

One monitor-specific addition: `MonitorSocket` exposes mutable `batteryPct` and
`thermal`, read at each heartbeat, so liveness reports carry current state
rather than whatever was true at connect time.

### Task 4 — `DEFAULT_MONITOR_CONFIG` did not exist in Kotlin

It was declared in `packages/protocol` (TypeScript) only; M3 never needed it, so
nobody noticed. Added to `:shared` with a test pinning each field to the spec
value. The hub pushes its own copy on connect, so a drift between the two would
make the monitor behave one way before that message arrives and another after —
a maddening thing to debug.

### Task 4 — a sharp edge in the adaptive floor, now documented in a test

The floor only adapts **below** the trigger point (spec §9: "updated only while
not triggered"). The consequence is not obvious: a new persistent noise source
that is *above* threshold — a fridge compressor 15 dB over the old floor — is
never absorbed. It produces one event per cooldown, indefinitely.

That is the intended trade rather than a bug: a genuinely louder room should
alert rather than teach the monitor to ignore it, and recalibration (spec §9.1)
is the remedy, which is why it is a first-class user-triggered operation. A test
now states this explicitly so the next reader does not "fix" it.

A first version of the drift test asserted the opposite and failed. The test's
premise was wrong, not the code — worth noting, because the failure looked at
first like a detector bug.

### Task 4 — negative controls

Both rules that carry the design were checked by breaking them:

| Sabotage | Test that failed |
| --- | --- |
| Remove the cooldown check | `cooldown suppresses a second event` |
| Update the floor while triggered | `a new noise source ABOVE threshold does not silently become the new floor` |

Each broke exactly one test and nothing else.

### Task 5 — the median is a design decision, and a test defends it

`Calibrator` reports the **median** of the sampled window, not the mean. A door
closing during a 30-second calibration would drag a mean upward by several dB
and leave the monitor deaf for the rest of the night.

Two tests hold that line: one feeds a brief bang and asserts the floor is
unmoved, and a second computes what a mean *would* have produced, so the cost of
changing it is stated rather than implied. Negative-controlled — swapping in
`sorted.average()` fails the bang test and nothing else.

Spread is the interquartile range for the same reason: it describes how variable
the room is without being moved by the outliers the median already ignores.

### Task 6 — what the service does, and what is still unproven

Structure, in the order that matters: `startForeground` **first**, before
anything that can block or fail, because a service that misses the window is
killed outright. Then credentials, then the socket, then `AudioRecord`.

Decisions worth knowing:

- **`listening` is tracked separately from `connected`.** A running service
  whose microphone failed to open must not claim the room is monitored, and the
  permanent notice says which is true. A test asserts the two texts differ.
- **`AudioRecord` construction and `startRecording` are wrapped.** A revoked
  permission, or a microphone held by another app, surfaces in the notification
  rather than crashing a service meant to run all night.
- **Detection is suspended during calibration.** The window is supposed to
  measure the room at rest (spec §9.1); detecting through it would defeat that.
- **`start_stream` is declined, not ignored.** M5 does not stream, and replying
  `stream_stopped` stops the parent waiting for a stream that will never begin.

**Negative controls** — each broke exactly the right test:

| Sabotage | Test that failed |
| --- | --- |
| `foregroundServiceType="dataSync"` | both service-type assertions |
| Add a `BOOT_COMPLETED` receiver | `declares no BOOT_COMPLETED receiver` |

**Still unproven, and only a device can settle it:** that capture actually
survives the screen locking, and that Android 17's background-audio hardening
accepts this service. Robolectric never opens a microphone. That is Task 9.

### Task 7 — the UI, and what it deliberately does not do

`QrScanner` is a copy of `:parent`'s, carrying both fixes that one needed on a
real device: the camera is bound in a `LaunchedEffect` keyed on the lifecycle
rather than inside `AndroidView(factory = ...)` (which runs once, so locking the
screen leaves a black rectangle forever), and the screen is kept awake while
scanning. Verified rendering on the Pixel 6 with a live preview.

**Monitoring does not start automatically after pairing.** Capture is something
someone chooses to begin, and the permanent recording notice should never appear
without a deliberate act. The Start button is also the control that has to be
pressed after every reboot (spec §3.2), so it is the largest thing on the
screen, and the text under it says why.

**The level meter is not decoration.** It shows the live reading next to the
room floor and the trigger point, which is the only thing that makes a badly
chosen threshold visible instead of mysterious. The defaults are guesses until
the app has been lived with, and someone standing in the room needs to see what
the phone is actually hearing.

`ACCESS_LOCAL_NETWORK` is requested at runtime alongside camera, microphone and
notifications, seeded from the real permission state rather than assumed denied.

### Task 8 — fake-parent, and why the hub now exposes its watchdog

```bash
npx tsc -b tools/fake-parent
node tools/fake-parent/dist/index.js --host 192.168.1.100 --port 8444 --code <PARENT CODE>
```

Prints each event as it arrives, then reuse the token it gives back:

```
[3:45:32 PM] #12 LOUD_NOISE peak -5.5 dB 2.4s  (monitor 66c8437c)
[3:45:32 PM] #13 MOTION  (monitor 66c8437c)
```

Verified against the deployed hub, not only in tests.

Its value is **isolation**. If `fake-parent` sees an event and the Pixel 8 Pro
does not, the problem is in the parent app rather than the monitor or the hub —
which is the question that would otherwise be guessed at during Task 9.

Two things worth noting:

- **`Hub` now exposes `watchdog`.** The MONITOR_LOST test drives a sweep
  directly instead of waiting out the real 45-second timeout. A test that slow
  is a test people stop running.
- **A revoked token is refused at the HTTP upgrade**, which arrives as
  `unexpected-response` rather than `error`. Without handling it the caller
  hangs instead of learning the device is no longer allowed; there is a test.

### Task 8 — the "unreproduced flake" from M3 was a real race in the hub

`docs/planning/progress-m3.md` recorded one failing run of 116 that sixteen
retries could not reproduce, with the detail lost. It resurfaced here, and the
failing test named itself: `rejects a connection with no token` expected `/401/`
and got *"Client network socket disconnected before secure TLS connection was
established"*.

**The cause was in the hub, not the test.** The websocket rejection path did
`socket.write("HTTP/1.1 401 …")` then `socket.destroy()`. Those race: destroy
can win before the client reads the response, and the client is left with a bare
disconnect instead of the status code saying why it was refused. In production
that turns "your device was revoked" into "the network broke" — for a client
that would otherwise show the user something useful.

Fixed by using `socket.end(response)`, which flushes before closing, for all
three refusals (404, 401, 403). Ten consecutive runs of that suite and six of
the whole suite are clean.

Worth recording the shape: **a flaky test was reporting a real defect in a rare
path.** Retrying until it passed, or loosening the assertion to accept either
outcome, would both have buried it.

## Observations from real use

Threshold tuning is deliberately not a gate (see the plan, Task 9 Step 4). The
defaults are guesses until the app has been lived with. Record what is seen here
so the numbers accumulate somewhere; every value is pushable from the hub via
`PUT /monitors/:id/config` without a rebuild.

_Nothing recorded yet._

## Notes for whoever picks this up

### The Pixel 6 has not been connected yet

Task 3's launch check ran on the `Pixel_8_Pro` AVD, which is **Android 17 /
SDK 37** — the same platform level the app targets, so it is a fair check that
the module builds, installs and starts. It is *not* a substitute for the Pixel 6:
Task 9's real work is capture through a locked screen, thermals over an hour,
and how the thresholds behave in an actual room. None of that is meaningful on
an emulator.

Plug the Pixel 6 in before Task 9.

### Two apps on one device

`:monitor` uses applicationId `com.kidsmonitor.monitor` and the label
"Kids Monitor (Room)", so it can sit alongside the parent app on the same phone
during development without either being mistaken for the other.

### Task 9 — pre-flight: the deployed hub was stale

Before a single device step, `git rev-parse HEAD` on `hub-host` showed
`05e8d27` / `feat/m3-parent-app`. **None of M5 was deployed.** Redeployed from
`feat/m5-monitor-app` (`71fcf3a`) and confirmed the certificate fingerprint was
unchanged, so no pairing was invalidated by the rebuild.

This mattered more than a routine redeploy. Two M5 fixes sit directly on the
paths Task 9 walks: without the `.nullish()` schema fix the hub rejects **every**
monitor event, and without `71fcf3a` the Step 1 refusal check produces exactly
the bare "socket disconnected" that the fix exists to remove. Both failures would
have pointed at the phone. Written up in
[`../lessons-learned.md`](../lessons-learned.md).

**Consequence for the plan:** verifying the deployed revision is now Step 0 of
Task 9, not an assumption.

Also of note: the hub's device list was empty (`no devices paired yet`) — the
Task 8 revocations took effect — so the Pixel 6, the Pixel 8 Pro and
`fake-parent` all need pairing codes. That is **four** codes, not two: a monitor
code, a throwaway parent code for the refusal check, a parent code for the
Pixel 8 Pro, and a parent code string for `fake-parent`. Each is single-use with
a 5-minute TTL, so they are issued just-in-time.

### Task 9 — environment as tested

| | |
| --- | --- |
| Monitor | Pixel 6 (`<pixel-6-serial>`), Android 16 / **SDK 36** |
| Parent | Pixel 8 Pro (`<pixel-8-pro-serial>`), Android 17 / **SDK 37** |
| Hub | `https://192.168.1.100:8444`, `71fcf3a` |

The monitor is SDK 36, so **this run does not exercise Android 17's
background-audio hardening on the monitor side.** The parent is SDK 37, which is
what makes it the device that needs `ACCESS_LOCAL_NETWORK`. Recording this
plainly because "verified on device" otherwise reads as a stronger claim than
the run supports.

### Task 9 — Step 1: install and pair ✅

Paired over adb (`tools/pair-device.sh monitor <pixel-6-serial>`) rather than by
scanning, after the QR proved too slow to iterate on. Hub device list shows the
`monitor` row; the app moved to the status screen.

**A parent code offered to the monitor was refused**, as required — and the
refusal exposed a real defect. The message read *"this is a parent pairing code;
**the parent app** needs a monitor code"*: `requireRole()` was written in M3 when
only the parent app existed, moved into `:shared` during M5 Task 2, and nobody
revisited the text. It names the wrong phone. Fixed, both directions now
asserted, negative-controlled.

Worth recording *why the test suite could not see it*: the existing assertion was
`message.contains("monitor")`, which the wrong message also satisfies — "monitor"
appears in it as the *expected* role. This is the same shape as the missing
CAMERA manifest entry in M3: **an assertion loose enough to pass for the wrong
reason is not coverage.**

### Task 9 — Step 2: capture survives the screen locking ✅

**The load-bearing claim of the whole product, and it holds.** With the Pixel 6
locked, screen off and `mWakefulness=Dozing` for over two minutes:

```
[10:42:10 AM] #14 LOUD_NOISE peak  -8.8 dB 1.5s  (monitor 66f1da12)
[10:43:38 AM] #15 LOUD_NOISE peak -13.6 dB 1.5s  (monitor 66f1da12)
```

Both at the **default `thresholdDb 12`**, before any threshold was lowered — so
the shipped defaults do fire on a real room noise at conversational distance.

Platform state while locked and dozing, all read off the device:

| Check | Reading |
| --- | --- |
| Service | `isForeground=true`, `types=0x000000C0` = CAMERA (0x40) + MICROPHONE (0x80) |
| Microphone | `src:MIC` **`not silenced`** in `dumpsys audio` |
| Hub socket | `lastSeen` current — heartbeats flowing through doze |
| Config from hub | `thresholdDb=12.0 sustainMs=1500`, capture at 16 kHz |

`not silenced` is the reading that actually settles it. Android mutes microphone
input for apps it does not consider entitled to background capture, and it does
so *without an error* — the app keeps reading buffers, they are simply zeros.
`dumpsys audio` states the verdict directly, and it is a far better instrument
than waiting to see whether an event arrives.

**A method note that cost time.** The first attempt played a tone through the
laptop's speakers and produced nothing, which is genuinely ambiguous: a
microphone that hears nothing and a room quieter than the trigger point look
identical from outside and need opposite fixes. There was no level reading to
tell them apart, because the screen — where the meter lives — is off exactly when
it matters. That gap is now closed: the service logs `level / floor / trigger`
every 5 s.

### Task 9 — Step 3: the notice is visible and undismissable ✅

Survived a hard swipe, a second slower swipe, **and the system's own "clear
all"**. The notification record is unchanged afterwards and the service is still
`isForeground=true`. Clear-all is the honest test; a swipe alone can land on the
content intent and open the app instead, which looks like a dismissal failure and
is not one.

**One finding worth knowing.** `originalFlags` shows the app sets
`ONGOING_EVENT` and the *system* adds `NO_CLEAR`, and the channel is
`mImportance=2` (`IMPORTANCE_LOW`). The consequence: the notice is ranked into
the shade's **Silent** section, below everything else, and collapsed to a bare
microphone icon until the shade is scrolled. Expanded it reads correctly —
"Listening to this room · Connected to the hub" — but it is not the first thing
someone picking up the phone sees.

Not changed, deliberately: `IMPORTANCE_LOW` is the right choice for a permanent
notice (anything higher risks alerting on every periodic update), and Android's
own green microphone indicator in the status bar is unsuppressable and is the
real guarantee that capture is visible. Recorded rather than fixed, because the
requirement is met by the platform and the alternative is worse.

### Task 9 — Step 4: calibrating a real room ✅

Recalibration triggered from the hub (`POST /monitors/:id/recalibrate`), 30 s
window, observed live over `adb logcat`:

```
recalibrating for 30000ms
level=-51.3 … level=-48.9 … level=-34.7 … level=-58.8
calibrated: floor=-50.08303077309578 spread=6.6663454329219505
```

**Recorded, not tuned**, per the standing instruction that thresholds are
discovered by living with the app:

| Measurement | Value |
| --- | --- |
| Resting floor, occupied room, daytime | **−50.1 dBFS** |
| Spread (IQR) | **6.7 dB** |
| Trigger point at default `thresholdDb 12` | −38.1 dBFS |
| Observed `LOUD_NOISE` peaks (default threshold) | −8.8, −13.6 dBFS |

Two things this confirms beyond "it ran":

- **The median earns its keep.** A −34.7 dBFS bang landed inside the calibration
  window — 15 dB above the room — and the reported floor was −50.08, essentially
  unmoved. A mean would have been dragged upward and left the monitor deaf.
  That was Task 5's argued design decision; this is it holding on a real room.
- **Detection is genuinely suspended during calibration.** The logged floor sat
  at −50.4 for the whole window and only moved when the result landed (spec
  §9.1).

The hub persisted it — `monitor_config.config_json` carries
`noiseFloorDb: -50.083` with `calibrated_at_ms`, so it survives a monitor
restart rather than being pushed back to the −50 default.

**Note on the peaks:** real events came in around −9 to −14 dBFS against a −50
floor, i.e. **36–41 dB above the room**, where the default threshold asks for 12.
The defaults have a lot of headroom on this room. That is an argument for
leaving them alone until a night in a quiet bedroom says otherwise, not for
tightening them now.

### Task 9 — Step 5: the hour, and an instrument that did not exist

Started 10:49:30 with the **shipped defaults restored** (`thresholdDb 12`,
`sustainMs 1500`, `cooldownS 60`) and the calibrated floor kept. The threshold
was temporarily dropped to 3 dB earlier to make Step 2 easy to trigger; counting
false alarms at a threshold we would not ship would have measured nothing.

Phone locked, screen off, plugged in over USB. **Recorded as tested plugged in
and USB-attached** — representative of the intended deployment for power, but
not of an unattached phone.

**The hour became 3 days 21 hours.** The session paused after starting the run
and the monitor was left alone until 2026-09-15, so Steps 5 and 6 are measured
over ~92 hours of continuous capture rather than one. Nothing was touched in
between.

| Measurement (09-11 10:49 → 09-15 08:31) | Value |
| --- | --- |
| `LOUD_NOISE` events | **38** (~0.4/hour); first hour: **0** |
| `MONITOR_LOST` events | **0** |
| Events between 00:00 and 05:00 | 7 (01:13, 01:16, 01:23, 01:31, 02:37, 04:07, 05:56 is just outside) |
| Floor this morning | −55 to −59 dBFS (calibrated −50.1 on 09-11, daytime) |

**Recorded, not judged** — the standing instruction. What the numbers show, for
whoever tunes this later:

- **Most events are marginal.** The majority peaked between −31 and −45 dBFS
  with a duration of exactly 1500–1520 ms, i.e. just over the trigger point for
  just the sustain window. The loud ones (−10 to −19 dBFS) are few. If the
  monitor ends up feeling noisy, `sustainMs` is the first knob to try, not
  `thresholdDb`.
- **The floor drifts down at night**, from the −50 daytime calibration to
  about −57, which lowers the trigger point with it. That is the adaptive floor
  working as designed, and it is the likely reason the night events exist.
- **A sustained noise fires once per cooldown, as documented.** 09-13 13:04,
  13:05 and 13:06 — exactly 60 s apart, durations 14.3 s, 18.6 s and 10.2 s.
  That is Task 4's recorded trade-off (a noise above threshold is never absorbed
  into the floor) appearing on real hardware.

### Task 9 — the monitor was killed and came back, and nobody could have known ✅⚠️

`dumpsys activity exit-info` shows the process was **SIGKILLed at 2026-09-14
12:00:36** — `reason=2 (SIGNALED) status=9`, `importance=125` (a foreground
service), no description. The service record shows `restartCount=1` with
`restartTime` the same second: `START_STICKY` brought it back.

Checked whether that restart actually recovered capture, because a
background-restarted service is exactly what Android denies while-in-use
microphone access to — and a silenced `AudioRecord` fails *without an error*,
returning zeros:

```
09-14 12:00:36:436 rec release riid:223 … src:MIC not silenced
09-14 12:00:37:871 rec start   riid:287 … src:MIC not silenced
```

New recorder 1.4 s after the kill, **not silenced**; 13 `LOUD_NOISE` events
after it; no `level=-120.0` in the log buffer. **Recovery was complete.**

Two things this is worth knowing for:

1. **The kill was invisible.** It came back well inside the 45 s watchdog, so
   no `MONITOR_LOST` fired. Good for the parent; bad for diagnosis — the only
   record is `exit-info`. A kill that *did not* recover would have surfaced as
   `MONITOR_LOST`, so the product is safe either way, but "zero `MONITOR_LOST`"
   must not be read as "never interrupted". Cause unknown: not low-memory (that
   reports reason 3), not a crash (`crashCount=0`).
2. **This is SDK 36.** `dumpsys audio` on this device already has a
   `## Hardening enforcement` section (empty). On an Android 17 monitor, a
   sticky restart regaining the microphone is **unverified**, and it is exactly
   the kind of thing that hardening targets. If the monitor ever runs on an
   SDK 37 phone, re-check this first.

### Task 9 — Step 6: thermals and battery ✅

After ~92 hours of capture, USB-attached:

| Reading | Value |
| --- | --- |
| `Thermal Status` | **0** (none), every sensor `mStatus=0` |
| Battery temperature | 21.5 °C (`dumpsys battery`: 24.4 °C) |
| Skin / display / quiet thermistors | 22.7–23.3 °C |
| Process CPU time | 38 min over the 20.5 h since the restart |
| Process memory | 31 MB PSS |

**No throttling, cool to the touch.** Audio-only capture was chosen over
continuous camera for thermals; this confirms that decision holds for audio. (The
CPU core readings of 71–87 °C are momentary, taken while adb was running `top`.)

**One reading that looks alarming and probably is not:** `dumpsys battery`
reports `health: 3`, which is `BATTERY_HEALTH_OVERHEAT`, alongside
`status: 4` (not charging) at `level: 80` — with the battery at 21.5 °C. The
likely explanation is Pixel's **Battery Defender**, which holds a phone plugged
in for days at 80 % and reports it this way. Not verified from the device
(`adaptive_charging_enabled` is unset). Recorded because anyone reading the
monitor's heartbeat battery field will see the phone *stop charging* at 80 %
and should not mistake it for a fault.

### Task 9 — found during parent-app verification (2026-09-15)

Two defects, both fixed with red→green tests and negative controls, both written
up in [`../lessons-learned.md`](../lessons-learned.md):

- **Permission requests were silently dropped** (`bd38fbe`). This is the real
  cause of the 09-11 parent pairing hang. Fixed in both apps; verified on the
  Pixel 8 Pro as one request with zero dropped-request warnings.
- **Socket events could lose `Connected`** (`dcf8e94`). This was the combined-run
  "flake" first seen 09-11, and it was a production race, not test noise.
  Combined Android run 5/5 clean afterwards; 194 tests.

**Test-harness note, not a product issue:** on Android 17, adb-injected taps on
the system permission dialog are unreliable (one of about a dozen landed), so
granting on that device needs a person's tap. `pm grant` would work but skips the
request path the permission fix exists to exercise.

### Task 9 — Step 2 completed on the real parent app ✅

Pixel 8 Pro (Android 17), data cleared, fixed build. The permission flow made
**one** `REQUEST_PERMISSIONS` covering camera → nearby devices (local network) →
notifications, with **zero** dropped-request warnings, and all three were granted.
Paired over adb in ~100 ms. (`tools/pair-device.sh` now passes `am start -S`:
when the app was already on screen the intent went to `onNewIntent`, which does
not read the payload, and the code was used up with nothing happening.)

With the parent phone **locked and dozing**, a real noise on the Pixel 6
produced:

```
[8:55:58 AM] #65 LOUD_NOISE peak -34.6 dB 0.3s  (monitor 66f1da12)
```

and the Pixel 8 Pro posted notification id 65, channel `alerts`, importance 4.
Parent service `isForeground=true types=0x40000000` (`specialUse`, spec §3.4).
Threshold lowered to 3 dB for this check only; defaults restored straight after.

**The whole product has now worked end to end on real hardware, both phones
locked.**

**Caveat recorded:** clearing app data also cleared do-not-disturb policy access,
so the `alerts` channel is `mBypassDnd=false` until the user grants it again from
the in-app banner. Alerts on that phone are silenced by Do Not Disturb until then.
Expected behaviour (M3 lesson), but anyone reinstalling the parent app will hit
it.

### Task 9 — Step 7: reboot ✅ (recovery pending an unlock)

Rebooted the Pixel 6 at 08:56:31 with `fake-parent` and the Pixel 8 Pro both
connected:

| Check | Result |
| --- | --- |
| `MONITOR_LOST` | **40 s** after reboot (`#66`), watchdog budget 45 s |
| On the parent phone | notification id 66, channel `monitor_lost`, importance 4 |
| Monitor after boot | `boot_completed=1`, **no process, no service record**: it did not restart itself (spec §3.2) |

The screen is credential-locked after a reboot, so "launch the app, tap Start,
recovers cleanly" needs a person to unlock the phone first. That is the intended
product behaviour: a reboot requires a human, and the parent is told.

### Task 9 — recovery after reboot ✅, and the summary

Unlocked the Pixel 6, opened the app and tapped Start once. No re-pairing:
capture started at 16 kHz with `src:MIC not silenced`, `types=0x000000C0`, the hub
pushed the **persisted calibrated floor −50.1**, and heartbeats resumed.

Cleanup: `fake-parent` revoked (its token had been printed to a log during
testing; now HTTP 401). The Pixel 6 and Pixel 8 Pro stay paired as the working
setup. Screen timeouts restored (30 s / 60 s). Defaults restored
(`thresholdDb 12`, `sustainMs 1500`, `cooldownS 60`).

#### Verified on hardware

| Claim | Evidence |
| --- | --- |
| Capture survives a locked, dozing screen | Alerts while `Dozing`; `dumpsys audio` `not silenced` |
| A real alert reaches a locked parent phone | Alert #65 → Pixel 8 Pro notification id 65, `alerts`, importance 4 |
| Recording notice cannot be dismissed | Survived swipes and clear-all |
| Calibration uses a robust floor and persists | −50.1 dBFS despite a −34.7 dB bang mid-window; survived restart |
| Long-run stability | 92 h, 38 alerts, 0 `MONITOR_LOST`, thermal status 0 |
| Recovery from an OS kill | SIGKILL 09-14 12:00:36, new recorder 1.4 s later, not silenced |
| Reboot behaviour | `MONITOR_LOST` in 40 s; no auto-restart; one tap recovers |
| Android 17 permissions | One request, zero dropped, pairing in ~100 ms |

#### Not verified, and why

- **A monitor on Android 17.** The only monitor device is SDK 36. Background-audio
  hardening, and whether a sticky restart gets the microphone back, are untested
  there.
- **An unplugged monitor.** Every run was USB-attached and charging.
- **Tuned thresholds.** Deliberately not tuned (standing instruction); the
  numbers above are the starting data.
- **DND bypass on the reinstalled parent.** Clearing data cleared the grant, and
  it has not been re-granted.
- **The cause of the 09-14 kill.** `exit-info` records no description.

#### Defects found and fixed during Task 9

Every one has a test that fails when the fix is reverted.

1. Stale hub deployment (M3 code): redeployed; now a pre-flight step.
2. Role-mismatch message named the wrong app.
3. `show-pairing-qr.sh` exited silently.
4. Idle screen showed "threshold +0 dB".
5. Idle screen showed "alerts above −108.0 dB".
6. Permission requests silently dropped (the 09-11 pairing hang).
7. Socket `Connected` event could be lost (the combined-run "flake", a production race).
8. `pair-device.sh` payload swallowed by an already-running app.

Added: `tools/pair-device.sh` (debug-only adb pairing), level logging every 5 s.

**M5 is complete.** Next: merge `feat/m5-monitor-app`, then M4 (live audio/video),
then manual pairing entry per [`roadmap.md`](roadmap.md).
