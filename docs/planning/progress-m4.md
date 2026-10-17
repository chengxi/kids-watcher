# M4 Progress and Handoff

**Plan:** `2026-09-15-m4-live-audio-video.md` (kept outside this repository)
**Branch:** `feat/m4-live-view`
**Hub:** `https://192.168.1.100:8444`

Anyone picking this up: read the plan's Global Constraints, then "Deviations"
below, then start at the first task not marked done.

## Status

| Task | Title | State |
| --- | --- | --- |
| 1 | Rotation in `VideoFormat`, a Kotlin media-frame codec, and cross-language checks | **done** |
| 2 | Binary media on the socket: receive on a lossy flow, send with backpressure | **done** |
| 3 | The relay forwards only the live stream, and never floods a slow viewer | **done** |
| 4 | `fake-monitor` streams real JPEG fixtures at 10 fps; `fake-parent` can watch | **done** |
| 5 | `LiveSession`, the viewer's state machine | **done** |
| 6 | Audio jitter buffer and `AudioTrack` playback | **done** |
| 7 | Wire the live view into the parent app | **done**; the deferred device check and its negative control were done in Task 11 |
| 8 | Pure building blocks: PCM framing, YUV to NV21, frame throttle, video settings | **done** |
| 9 | `StreamController`, the monitor's stream lifecycle | **done** |
| 10 | CameraX capture in the service, and a visible "a parent is watching" | **done**; the camera itself verified on the Pixel 6 in Task 11 |
| 11 | Verify on the Pixel 6 and Pixel 8 Pro | **done** 2026-10-02; see "Task 11 — verified on hardware" |

## Deviations from the plan

Recorded as they happen, with the reason. A deviation with no reason is a bug.

### Task 1 — branch already existed

The controller had already created and checked out `feat/m4-live-view` from
`main` (`d01f0fa`) before this task started, so Step 1's
`git checkout main && git pull --ff-only home main && git checkout -b feat/m4-live-view`
was skipped as a no-op per the SDD ledger. `docs/planning/progress-m4.md` was
still created as specified.

No other deviations: the brief's file paths, schema, Kotlin types and script
changes matched the existing codebase (`packages/protocol/src/binary.ts`
already existed and matched the Kotlin twin's expected wire format exactly).

### Task 2 — `gradlew` lives under `apps/android`, not the repo root

The brief's commands (`./gradlew :shared:testDebugUnitTest ...`) assume
`gradlew` at the repo root. This repo's Android project root is
`apps/android`, so every Gradle command in this task was run from
`apps/android` (e.g. `cd apps/android && ./gradlew :shared:testDebugUnitTest
--tests '*MediaBackpressureTest*'`). No other deviations: file paths, the
`MediaBackpressure` object, the `RoleSocket`/`HubSocket`/`MonitorSocket`
changes, and the test additions matched the brief's code verbatim.

One brief-ordering wrinkle, not a deviation from the delivered code: Step 3's
`MediaBackpressureTest` (in particular `the send path drops a frame...` and
`the send path sends as binary...`) calls the file-level `sendMediaOn`, which
the brief only introduces in Step 6. To get Step 3's "expected: PASS" the
`sendMediaOn` function (and its imports) had to be added to `RoleSocket.kt`
before running that test, ahead of the rest of Step 6 (the `media` flow,
`onMessage(bytes)`, `HubSocket.media`, `MonitorSocket.sendMedia`), which were
added afterward per the brief's Step 4/5 (write the socket tests, confirm RED)
then Step 6 (implement, confirm GREEN).

### Task 3 — test count drifted from the brief's "122 existing + 6 new"

The brief's Step 5 says to expect PASS on "122 existing + 6 new" when running
`npx vitest run` from `services/hub`. Running from `services/hub` gave 90
tests total (84 existing + 6 new: 4 in the new `backpressure.test.ts`, 2 added
to `relay.test.ts`); running `npx vitest run` from the repo root (which is
what `npm run -s typecheck`'s sibling `npm test` actually wires up, and covers
`packages/protocol` and `tools/fake-monitor`/`fake-parent` too, matching the
plan's own multi-package layout) gave 131 total (125 existing + 6 new). Either
way the delta from the new work is the same six tests; the repo has simply
grown more tests than the brief's snapshot since the plan was written. Not a
bug — recorded per the instruction to log any command mismatch.

The brief's fallback for `FRAME_HEADER_BYTES` ("If ... is not exported from
the protocol package's index, export it from `binary.ts` ... and re-export via
`index.ts`") did not apply: `packages/protocol/src/index.ts` already does
`export * from "./binary.js"`, so both `FRAME_MAGIC` and `FRAME_HEADER_BYTES`
were already public. `packages/protocol` is therefore unchanged this task,
despite appearing in the brief's Step 7 `git add` — nothing there to stage.

### Task 4 — no deviations from the brief's code; two environment notes

Step 1's fallback ("if PIL isn't installed, install it into a throwaway venv")
did not apply: the system `python3` (Homebrew, `/opt/homebrew/bin/python3`)
already had Pillow 12.2.0 importable, so `make-frames.py` ran directly with no
venv. All ten fixtures generated as specified (6.4-6.7 KB each).

The brief's Step 6 fallback ("if the JPEG fixtures trip `secrets:scan`,
exclude `tools/fake-monitor/fixtures/*.jpg` in `tools/scan-secrets.mjs`") also
did not apply: `npm run -s secrets:scan` reported "secret scan clean" with the
fixtures staged, unmodified. `tools/scan-secrets.mjs` is therefore unchanged
this task.

Verified (not in the brief, raised by review): `media.ts`'s comment that the
fixtures path "works from both src/ and dist/" depends on a flat `dist/`
layout. `tools/fake-monitor/tsconfig.json` sets `rootDir: "src"`, so
`tsc -b tools/fake-monitor` emits `dist/media.js` (not `dist/src/media.js`),
making `../fixtures/` resolve to `tools/fake-monitor/fixtures/` correctly from
the built output too. Confirmed by running the built module directly:
`node --input-type=module -e "const m = await import('./tools/fake-monitor/dist/media.js'); console.log(m.jpegSize(m.fixtureJpeg(0)))"`
→ `{ height: 480, width: 640 }`.

### Task 5 — no deviations

The brief's file paths, `LiveState`/`StreamGateway`/`LiveSession` shapes, and
the test file matched the repo exactly. `:parent` already resolves
`kotlinx.coroutines.CoroutineScope`/`Job`/`delay`/`flow.*` at compile time via
its existing transitive dependency chain (as used by `AlertService.kt`,
`AlertsViewModel.kt`), so no `build.gradle.kts` change was needed for
`LiveSession.kt`'s coroutines imports. Ran from `apps/android` per Task 2's
recorded `gradlew` location.

### Task 6 — `gradlew` run from `apps/android`; brief's code matched verbatim

Same wrinkle as Task 2/3/5: the brief's `./gradlew :parent:testDebugUnitTest
...` and `./gradlew :parent:assembleDebug` assume repo-root `gradlew`, so both
were run from `apps/android`. No other deviations — `AudioJitterBuffer.kt`,
`AudioJitterBufferTest.kt`, and `PcmPlayer.kt` were created exactly as the
brief specifies, and `com.kidsmonitor.shared.protocol.AudioFormat(sampleRate,
channels)` already existed in `Messages.kt` matching the brief's assumption.
`PcmPlayer` is device-only per the brief and global constraints (Robolectric
cannot play audio); it is compiled by `:parent:assembleDebug` but not
unit-tested, and is verified on hardware in Task 11.

## Negative controls

Recorded as they happen: what was broken on purpose, what was expected to
fail, and what actually happened.

### Task 1 — Step 10: swapped `streamId` endianness in `MediaFrames.encode`

Changed `putShort(frame.streamId.toShort())` to encode `streamId` as
little-endian while leaving the magic/kind/timestamp big-endian.

- Re-ran `./gradlew :shared:testDebugUnitTest --tests '*MediaFramesTest*'`:
  `encodes the 12-byte big-endian header exactly as the hub does` FAILED
  (`ComparisonFailure`, other 7 tests still passed) — expected, since the
  golden hex bytes assume big-endian throughout.
- Re-ran `./gradlew :shared:testDebugUnitTest --tests '*EncodingDumpTest*'`
  then `node tools/check-protocol-conformance.mjs`: the `media frames` section
  reported `REJECTED kind 1 stream 258` and `REJECTED kind 1 stream 1`
  (byte-swapped stream IDs 0x0102 and 0x0001 both differ under the swap).
  `kind 2 stream 65535` still reported `OK` — expected, because 0xFFFF is
  byte-symmetric and the swap is invisible for that one value. Overall:
  `2 of 19 REJECTED — Kotlin and zod have drifted.`
- Restored `putShort(frame.streamId.toShort())` under the single
  `ByteOrder.BIG_ENDIAN` buffer. Re-ran both commands: all green again (see
  Step 9 results below).

### Task 2 — Step 8.1: removed the `shouldSend` check from `sendMediaOn`

Changed `sendMediaOn` to send unconditionally, skipping
`MediaBackpressure.shouldSend`.

- Ran `./gradlew :shared:testDebugUnitTest --tests '*MediaBackpressureTest*'`:
  `the send path drops a frame when the socket is behind, without sending it`
  FAILED (`AssertionError`, the other 5 tests in that class still passed) —
  expected, since the send path no longer consults the policy.
- Restored the `if (!MediaBackpressure.shouldSend(...)) return false` guard.
  Re-ran the same command: all 6 green again.

### Task 2 — Step 8.2: routed a decoded media frame into the events queue instead of `mutableMedia`

Changed the binary `onMessage` handler to
`emit(Event.Disconnected("media frame ${frame.kind}"))` instead of
`mutableMedia.tryEmit(frame)`.

- Ran `./gradlew :shared:testDebugUnitTest --tests '*HubSocketTest*'`: both
  `a media frame does not enter the event queue` FAILED (`AssertionError` —
  the event queue's next item was the injected `Disconnected`, not the
  `heartbeat_ack`) and `surfaces a binary media frame on the media flow`
  FAILED (`TimeoutCancellationException` — `socket.media.first()` never
  received anything because nothing was ever emitted to `mutableMedia`); 9 of
  11 tests in that class still passed — expected, since only the media path
  is affected.
- Restored `mutableMedia.tryEmit(frame)`. Re-ran the same command: all 11
  green again.

### Task 3 — control 1: removed the `readUInt16BE(2) !== stream.streamId` check

Removed the stream-id guard from `Relay.forwardFrame` in `relay.ts`, leaving
only the magic-byte check.

- Ran `npx vitest run test/relay.test.ts` (from `services/hub`):
  `does not forward a frame stamped with another stream id` FAILED
  (`AssertionError: expected 2 to be 1` — the mismatched-id frame was
  forwarded first, so the viewer's payload was `[1]` instead of `[2]`); the
  other 8 tests in the file still passed.
- Restored the `if (frame.readUInt16BE(2) !== stream.streamId) return;` line.
  Re-ran the same command: all 9 green again.

### Task 3 — control 2: made `shouldForward` always return `true`

Changed `shouldForward` in `backpressure.ts` to `return true` unconditionally.

- Ran `npx vitest run test/backpressure.test.ts` (from `services/hub`): 3 of
  4 tests FAILED (`drops video for a slow viewer before it drops audio`,
  `drops audio too once a viewer is far behind`, `drops a frame of a kind it
  does not know` — each asserts `false` and got `true`); `forwards to a
  viewer who is keeping up` still passed, since it only asserts `true` and a
  policy that never drops trivially satisfies it.
- Restored the real limit check. Re-ran the same command: all 4 green again.

Full suite (`npx vitest run` from the repo root) and `npm run -s typecheck`
were re-run after each restoration and stayed green/clean throughout.

### Task 4 — brief's control: made `FakeParent`'s binary handler `return` without counting

Changed `if (isBinary) { this.mediaFrames++; return; }` to `if (isBinary) { return; }` in `tools/fake-parent/src/client.ts`.

- Ran `npx vitest run tools/fake-parent -t "watches a stream"`:
  `watches a stream and counts its binary frames` FAILED
  (`AssertionError: expected +0 to be 3`), exactly as the brief predicted; the
  other 3 tests in the file were skipped by the `-t` filter, not run.
- Restored `this.mediaFrames++`. Re-ran `npx vitest run tools/fake-parent`:
  all 4 green again. Full suite (`npx vitest run` from the repo root, 19 files
  / 133 tests), `npm run -s typecheck`, and `npx tsc -b tools/fake-monitor
  tools/fake-parent` all passed/clean afterward.

### Task 5 — control 1: removed the `abandoned += current.monitorId` branch from `stop()`

Changed the `is LiveState.Requesting` branch in `stop()` to `Unit` (a no-op),
so leaving during a request no longer remembers to stop it later.

- Ran `./gradlew :parent:testDebugUnitTest --tests '*LiveSessionTest*'`:
  `leaving before the stream is ready still stops it when it arrives` FAILED
  (`AssertionError`, `gateway.stopped` stayed empty instead of `[9]`); 13 of
  14 tests in the class still passed.
- Restored `abandoned += current.monitorId`. Re-ran the same command: all 14
  green again.

### Task 5 — control 2: removed `abandoned += monitorId` from `onTimeout`

Deleted the line that records the monitor id as abandoned when the start
timeout fires.

- Ran `./gradlew :parent:testDebugUnitTest --tests '*LiveSessionTest*'`:
  `gives up after the start timeout, and stops a stream that arrives late`
  FAILED (`AssertionError`, `gateway.stopped` stayed empty instead of `[11]`
  once the late `stream_ready` arrived); 13 of 14 tests still passed.
- Restored `abandoned += monitorId`. Re-ran the same command: all 14 green
  again.

### Task 5 — control 3: made `accepts` ignore the stream id

Changed `accepts` to `return current is LiveState.Playing`, dropping the
`current.streamId == frame.streamId` comparison.

- Ran `./gradlew :parent:testDebugUnitTest --tests '*LiveSessionTest*'`:
  `accepts only frames for the stream being played` FAILED (`AssertionError`
  at `assertFalse(live.accepts(frame(8)))`: the frame for a foreign stream id
  was accepted); 13 of 14 tests still passed. *Corrected in the final review:*
  only the foreign-stream-id check failed. The after-`stop()` assertion does
  not depend on the id comparison - the state is `Idle` by then, so the broken
  `accepts` still returns false there.
- Restored the `current.streamId == frame.streamId` check. Re-ran the same
  command: all 14 green again.

Full `:parent` unit test suite (`./gradlew :parent:testDebugUnitTest`) was
re-run after the final restoration: `BUILD SUCCESSFUL`, all tests green.

### Task 6 — control 1: removed the latency-cap eviction loop from `offer`

Deleted the `while (buffered > maxBytes && chunks.size > 1) { ... }` block
from `AudioJitterBuffer.offer`, so buffered audio could grow without bound.

- Ran `./gradlew :parent:testDebugUnitTest --tests '*AudioJitterBufferTest*'`:
  `drops the oldest audio beyond the latency cap` FAILED (`AssertionError` at
  the `droppedBytes` assertion — nothing was ever evicted, so `droppedBytes`
  stayed `0` instead of `10 * 640`); the other 3 tests still passed.
- Restored the eviction loop. Re-ran the same command: all 4 green again.

### Task 6 — control 2: removed the `if (!primed) return null` prebuffer gate from `poll`

Deleted the gate so `poll()` would hand out audio before the prebuffer filled.

- Ran `./gradlew :parent:testDebugUnitTest --tests '*AudioJitterBufferTest*'`:
  both `plays nothing until the prebuffer is full` and `re-primes after an
  underrun` FAILED (`AssertionError` — each expects an early `poll()` to
  return `null` before 120 ms is buffered, and both got a chunk instead); the
  other 2 tests still passed. Both failures are expected: the gate is what
  both tests exercise.
- Restored the gate. Re-ran the same command: all 4 green again.

### Task 6 — control 3: removed `primed = false` from the underrun branch of `poll`

Deleted the line that un-primes the buffer when `poll()` finds it empty, so a
single leftover chunk after a dry spell would play immediately instead of
waiting for a fresh prebuffer.

- Ran `./gradlew :parent:testDebugUnitTest --tests '*AudioJitterBufferTest*'`:
  `re-primes after an underrun` FAILED (`AssertionError` — after the buffer
  ran dry, `primed` stayed `true`, so `poll()` handed out the single 640-byte
  chunk offered post-underrun instead of returning `null` until 120 ms
  refills); the other 3 tests still passed.
- Restored `primed = false`. Re-ran the same command: all 4 green again.

Full `:parent` unit test suite (`./gradlew :parent:testDebugUnitTest`) was
re-run after the final restoration: `BUILD SUCCESSFUL`, 77 tests, all green.
`./gradlew :parent:assembleDebug` also re-run afterward: `BUILD SUCCESSFUL`.

### Task 7 — live view wired in; device check deferred

Steps 1–7 are implemented and committed.

- Deviation: Gradle commands run from `apps/android` (same as Tasks 2/6).
- Deviation (tiny): in `ParentApp.kt` the existing `AlertsViewModel` built its
  own `HubApi` inline; per the brief's instruction it now receives the shared
  `api` instead of building a second one.
- RED: `LiveTextTest` failed to compile (`Unresolved reference 'liveStatusText'`,
  `'rotatedSize'`). GREEN: 3/3 pass after `LiveText.kt`.
- Full suites with `--rerun-tasks`: shared 100 tests / 0 failures, parent 80 /
  0. No compiler warnings in any Task 7 file. `:parent:installDebug` to the
  Pixel 8 Pro succeeded.

### Task 7 — review fix round 1 (controller ruling: fix all four now)

**Deviation from the brief's verbatim `LiveViewModel`:** its logic moved into
a new pure-Kotlin `parent/live/LiveViewer.kt`, and `LiveViewModel` is now a thin
wrapper that only supplies BitmapFactory decoding, `PcmPlayer` (behind a small
`AudioSink` interface) and an off-main release thread. Reason: the four review
findings are all about start/stop/player lifecycle, and moving that logic out
makes it JVM-testable without AudioTrack or BitmapFactory.

1. **Stuck on "Connecting…"** when the screen starts before AlertService
   attaches the session: `start()` now records a `wanted` intent, and a
   collector on `LiveLink.session` starts any session that appears while it
   holds. That covers process death restoring the screen, a service
   recreate, and "Try again" pressed while not connected.
2. **stop() raced the audio collector:** player swap, creation and release all
   happen under one lock, and `onMedia` does nothing once stopped. Release
   runs on a separate thread (PcmPlayer joins for up to 500 ms).
3. **Stale picture after stop():** each stop bumps a `generation`, and a decode
   that finishes under an older generation is dropped.
4. **Player kept across streams:** released when the state leaves `Playing` or
   its `streamId` changes, and the next stream gets a new player built from its own format.

RED: `LiveViewerTest` was first run against a straight port of the brief's
logic into `LiveViewer`: 5 of 7 failed (both "session appears" tests, "audio
arriving after stop", "picture decoded after stop", "player released when the
stream ends"). GREEN after the fix: 7/7. Full `:parent:testDebugUnitTest
--rerun-tasks`: 87 tests, 0 failures.

**Negative controls** (each rule broken on purpose, then restored and checked with `diff`):
- Follower never starts an appearing session → `a session that appears after
  start is started` and `a replacement session is started while watching…`
  FAILED (`expected:<[m1]> but was:<[]>`); the other 5 passed.
- Removed `if (!wanted) return` from `onMedia` → `audio arriving after stop
  opens no player` FAILED (`expected:<0> but was:<1>`); other 6 passed.
- Removed the `generation == gen` check before showing a picture → `a picture
  decoded after stop is dropped` FAILED (`expected null, but was:<late>`);
  other 6 passed.
- Removed the release when the state leaves Playing → `the player is released
  when the stream ends…` FAILED (`expected:<1> but was:<0>`); other 6 passed.

The device check in Task 11 Step 2 below now exercises `LiveViewer` rather than the brief's view model.

### Task 7 — Steps 8 and 9 DEFERRED to Task 11 Step 2 (controller ruling)

**Why:** the Pixel 8 Pro (`<pixel-8-pro-serial>`) was on a secure lock screen
(fingerprint/PIN; `isKeyguardShowing=true` even after `wm dismiss-keyguard`).
The controller ruled not to wait: commit Steps 1–7 and do the device check in
Task 11 Step 2. **The negative control has not been done yet.**

**Already done before the deferral:**
- Hub revision checked: `63900d7` (Merge M5). Local `main` is `d01f0fa`; they
  differ only in docs (the M4 plan + roadmap).
- Fake monitor paired as "Fake Nursery": **device id
  `b1107d61-fb86-4e58-9bbe-fe9eef9dc08b`**. Revoke it at Task 11 cleanup. Its
  token lives only in the Task 7 implementer's scratchpad, so for Task 11 you
  can pair a new one (then revoke both).
- fake-monitor stopped; no background processes left.

**Procedure for Task 11 Step 2** (run from the repo root, phone unlocked,
adb at `<android-sdk-path>/platform-tools/adb`,
`ANDROID_SERIAL=<pixel-8-pro-serial>`):

1. `ssh <hub-host> 'cd ~/kids-monitor && git rev-parse --short HEAD'`. Record it.
2. `cd apps/android && ./gradlew :parent:installDebug`, then
   `adb shell am force-stop com.kidsmonitor.parent` and relaunch the app
   (`adb shell monkey -p com.kidsmonitor.parent 1`). The install alone does
   not restart the app.
3. `npx tsc -b tools/fake-monitor`; get a code with
   `ssh <hub-host> 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml exec -T hub node services/hub/dist/cli/pair.js monitor'`
   and start
   `node tools/fake-monitor/dist/index.js --host 192.168.1.100 --port 8444 --name "Fake Nursery" --code <CODE>`
   (or `--token <TOKEN>`). Start it **before** looking at the phone, because
   Watch is disabled while the monitor is offline.
4. Within 15 s the alerts screen shows **Fake Nursery · Online · Watch**. Tap
   Watch. Expect: "Live", a moving yellow bar (640×480 JPEG fixtures at 10 fps),
   and a steady 440 Hz tone. While it plays, capture
   `adb shell dumpsys audio` showing an active AudioTrack player for the app,
   which serves as audio evidence if nobody can listen.
   If the picture stays blank, check the fixtures in the running fake-monitor
   before touching the decoder.
5. Press Home (`adb shell input keyevent KEYCODE_HOME`). fake-monitor must print
   `stream stopped` within a second. Reopen the app: the stream resumes.
6. **Negative control:** in `LiveViewScreen.kt` comment out
   `Lifecycle.Event.ON_STOP -> viewModel.stop()`, reinstall, force-stop and
   relaunch, tap Watch, press Home. fake-monitor must **keep streaming** (no
   `stream stopped`). Restore the line, reinstall, and confirm Home prints
   `stream stopped` again. Check with `git diff` that the restored file is the
   committed one.
7. Record what you saw here, then stop fake-monitor.

### Task 8 — pure building blocks: no deviations

`PcmFrames.kt`, `Yuv.kt`, `FrameThrottle.kt`, `VideoSettings.kt`, and
`StreamBuildingBlocksTest.kt` were created exactly as the brief specifies, in
the new `com.kidsmonitor.monitor.stream` package under `:monitor`. Ran from
`apps/android` per the recorded `gradlew` location (Task 2/3/5/6/7).

RED: `./gradlew :monitor:testDebugUnitTest --tests '*StreamBuildingBlocksTest*'`
failed to compile — `Unresolved reference` for `PcmFrames`, `Yuv`,
`FrameThrottle`, `VideoSettings` — as expected, since none of the four files
existed yet.

GREEN: same command after implementing all four files: `BUILD SUCCESSFUL`, 6/6
tests passed (`monitor/build/test-results/testDebugUnitTest/*StreamBuildingBlocksTest.xml`:
`tests="6" failures="0" errors="0"`).

Full `:monitor:testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL`, 64 tests
across the module, 0 failures, no compiler warnings in any Task 8 file.
`:shared:testDebugUnitTest` and `:parent:testDebugUnitTest` re-checked
unaffected (up-to-date, green) since this task touches only `:monitor`.

**Negative controls** (brief's Step 4, both against `Yuv.toNv21`):

1. Changed `val base = row * yRowStride` to `val base = row * width` in the Y
   loop. Re-ran `--tests '*StreamBuildingBlocksTest*'`: only
   `YUV_420_888 with row and pixel strides becomes NV21` FAILED
   (`ArrayComparisonFailure`); the other 5 tests passed. Restored
   `row * yRowStride`.
2. Swapped the V/U write order (`out[pos++] = u.get(offset)` then
   `v.get(offset)`) in the chroma loop. Re-ran the same command: the same NV21
   test FAILED (`ArrayComparisonFailure`) — the picture would be blue-tinged
   rather than broken, which is exactly why this needs a test — the other 5
   still passed. Restored the `v` then `u` order.

`git status --porcelain` confirmed the restored `Yuv.kt` matches what was
written (the file is new/untracked this task, so `git diff` had nothing to
compare against; the restoration was verified by re-reading the file and by
both negative controls above and the full suite going back to green).

### Task 9 — `StreamController`: no deviations

`StreamController.kt` and `StreamControllerTest.kt` were created exactly as
the brief specifies, in the existing `com.kidsmonitor.monitor.stream` package
under `:monitor` (alongside Task 8's `PcmFrames`, which this task consumes
via `PcmFrames.littleEndian`). Ran from `apps/android` per the recorded
`gradlew` location (Tasks 2/3/5/6/7/8). `kotlinx.coroutines.sync.Mutex` and
`kotlinx.coroutines.test.runTest` resolved with no `build.gradle.kts` change,
same as Task 6/8's coroutines usage — `:monitor` already gets
`kotlinx-coroutines-core` transitively and `kotlinx-coroutines-test` is
already a `testImplementation`.

RED: `./gradlew :monitor:testDebugUnitTest --tests '*StreamControllerTest*'`
failed to compile — `Unresolved reference` for `VideoSource`, `StreamOutput`,
`StreamController` — as expected, since `StreamController.kt` did not exist
yet.

GREEN: same command after implementing `StreamController.kt`: `BUILD
SUCCESSFUL`, 14/14 tests passed
(`monitor/build/test-results/testDebugUnitTest/*StreamControllerTest.xml`:
`tests="14" failures="0" errors="0"`).

Full `:monitor:testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL`, 78 tests
across the module (64 from before this task + 14 new), 0 failures, no
compiler warnings in either Task 9 file. `:shared` and `:parent` are
unaffected since this task touches only `:monitor`.

**Negative controls** (brief's Step 4, both against `StreamController`):

1. Changed `onDisconnected` to `lock.withLock { }` (dropping the
   `stopLocked()` call). Re-ran `--tests '*StreamControllerTest*'`: only
   `losing the hub turns the camera off without trying to send` FAILED
   (`AssertionError` — the camera stayed `running`); the other 13 tests
   passed. Restored `lock.withLock { stopLocked() }`.
2. Removed the `if (streamId != id) return` guard from `send`. Re-ran the
   same command: only `a frame the camera delivers after stopping is not
   sent` FAILED (`AssertionError` — the late frame after `stop_stream` was
   sent anyway); the other 13 tests passed. Restored the guard.

### Task 9 — review fix round 1 (controller ruling: fix all three now)

Three findings from review, all fixed:

1. **[Important]** `video.start` throwing or being cancelled left the stream
   half-started: `streamId` stayed set, `videoRunning` stayed false, and
   neither `stream_started` nor `stream_stopped` was ever sent — the hub
   would wait forever, a retried `start_stream` for the same id would be
   silently ignored (`streamId == message.streamId` guard), and a
   cancellation landing after the camera actually bound would leave it
   running. Fixed by extracting `startVideo(id)`: an ordinary exception is
   now handled exactly like the camera returning `null` (best-effort
   `video.stop()`, then the existing audio-only-fallback/decline path runs
   unchanged so the hub always gets a reply); a `CancellationException`
   instead cleans up inside `withContext(NonCancellable)` (best-effort
   `video.stop()`, `streamId` and `videoRunning` reset) and rethrows, since
   there is no one left to hear a reply once the coroutine is cancelled.
2. **[Elevated]** The existing `a new stream id replaces the old stream` test
   never proved the old camera was stopped before the new one started —
   deleting `onStartStream`'s `stopLocked()` call left it green. Renamed and
   strengthened it (now `a new stream id replaces the old stream, stopping
   the previous camera first`): `FakeCamera` gained a `calls` log, and the
   test now asserts `calls == ["start", "stop", "start"]`, the
   `onStreamingChanged` sequence `[true, false, true]`, and the
   `StreamStarted` control message for the new id.
3. **[Elevated]** If `video.stop()` threw inside `stopLocked`,
   `onStreamingChanged(false)` was skipped entirely (the throw happened
   before that line ran) — a `stop_stream`/replacement/`shutdown` call could
   then leave `onStreamingChanged` stuck reporting `true` forever. Fixed with
   `try { ... } finally { if (wasStreaming) onStreamingChanged(false) }`, and
   the `video.stop()` call itself is now `runCatching { ... }` (best-effort):
   a failing stop must not block a replacement `start_stream` or leave the
   hub without its `stream_stopped` reply either. Both the ordinary-failure
   cleanup in `startVideo`'s exception branch and this one wrap
   `video.stop()` in `runCatching`/`withContext(NonCancellable)` for the same
   reason — a broken camera driver must never become KidsMonitor's problem.

**TDD evidence.** All new/changed tests added to `StreamControllerTest.kt`
first (18 tests total, up from 14), then run against the *pre-fix*
`StreamController.kt` from commit `f07a306`:

```
./gradlew :monitor:testDebugUnitTest --tests '*StreamControllerTest*'
```
→ 4 of 18 FAILED:
- `a camera whose start throws falls back to sound only, and the camera is
  stopped` — `java.lang.IllegalStateException` (uncaught; finding 1).
- `a camera whose start throws, with no audio asked for, declines with a
  reason, and the camera is stopped` — `java.lang.IllegalStateException`
  (uncaught; finding 1).
- `cancelling a start in progress stops the camera and clears the stream` —
  `AssertionError` on `camera.stopped` (finding 1, cancellation case).
- `stop_stream still turns streaming off when the camera fails to stop` —
  `java.lang.IllegalStateException` (uncaught; finding 3).

The strengthened replace-stream test (finding 2) was **not** part of this RED
set — it already passed before the fix, since `onStartStream` already called
`stopLocked()` before starting a new stream. Finding 2 is a coverage gap, not
a pre-existing bug; its regression-catching value is demonstrated by its
negative control below instead.

GREEN after implementing the three fixes: same command, `BUILD SUCCESSFUL`,
18/18 pass.

**Negative controls**, each broken on purpose against the fixed code, then
restored:

1. **Finding 1, ordinary exception** — deleted the `catch (e: Exception)`
   branch from `startVideo` (kept the `CancellationException` branch). Re-ran
   `--tests '*StreamControllerTest*'`: exactly the two throwing-start tests
   FAILED (`java.lang.IllegalStateException`, now propagating uncaught since
   nothing catches it), 16/18 still passed. Restored the branch.
2. **Finding 1, cancellation** — removed the `withContext(NonCancellable) { ... }`
   cleanup block from the `CancellationException` branch, keeping the
   `throw e`. Re-ran the same command: only `cancelling a start in progress
   stops the camera and clears the stream` FAILED (`AssertionError` on
   `camera.stopped`, exactly as expected), 17/18 still passed. Restored the
   cleanup block.
3. **Finding 2** — removed the `stopLocked()` call from `onStartStream`
   (kept the same-id guard above it). Re-ran the same command: only
   `a new stream id replaces the old stream, stopping the previous camera
   first` FAILED (`AssertionError` — `camera.calls` was `["start", "start"]`
   instead of `["start", "stop", "start"]`), 17/18 still passed. Restored the
   call.
4. **Finding 3** — reverted `stopLocked` to the plain, unguarded sequence
   (`if (videoRunning) { videoRunning = false; video.stop() }` then
   `if (wasStreaming) onStreamingChanged(false)`, no `try`/`finally`, no
   `runCatching`). Re-ran the same command: only `stop_stream still turns
   streaming off when the camera fails to stop` FAILED
   (`java.lang.IllegalStateException`, uncaught since `video.stop()` throws
   with nothing to catch it), 17/18 still passed. Restored the
   `try`/`finally`/`runCatching` version.

Full `:monitor:testDebugUnitTest --rerun-tasks` after the final restoration:
`BUILD SUCCESSFUL`, 82 tests across the module (78 from before this round +
4 new), 0 failures, no new compiler warnings (the `MonitorPairingViewModelTest.kt`
opt-in warnings are pre-existing from Task 7/8 and unrelated to this change;
the new `cancelling a start in progress...` test's own use of
`runCurrent`/`advanceUntilIdle` is annotated `@OptIn(ExperimentalCoroutinesApi::class)`
to keep the build warning-free).

### Task 10 — CameraX in `MonitorService`: four deviations, all with reasons

Files as the brief lists, plus `StreamController.kt`/`StreamControllerTest.kt`
for controller ruling 1. Run from `apps/android`.

1. **`stopLocked` stops the camera non-cancellably** (controller ruling 1):
   `withContext(NonCancellable) { runCatching { video.stop() } }`. Without it, a
   suspending `stop()` in a cancelled coroutine, or under `onDestroy`'s
   `withTimeoutOrNull(2_000)`, threw `CancellationException` into `runCatching`,
   which swallowed it with the camera still open. Two new tests:
   `shutdown from an already-cancelled scope still releases a camera whose stop
   suspends` (fake `stop()` does `yield()`) and `a shutdown timeout does not
   abort a camera stop in progress` (fake `stop()` does `delay(5_000)` under a
   2 s timeout, the production shape). Note: `Mutex.withLock` does not check
   cancellation when the lock is free (fast path), so the cancelled-scope test
   really reaches `stopLocked`; it was RED on `camera.stopped` before the fix.
   **`onDestroy`**: kept the brief's `runBlocking { withTimeoutOrNull(2_000) {
   stream?.shutdown() } }` as the first lines, before `scope.cancel()`.
   `runBlocking` is a new root job, not a child of `scope`, so the service's
   scope cancellation cannot abort it; only the timeout can, and the timeout can
   only abandon waiting for the lock, never a release already in progress.
   Backstop: `cameraOwner.destroy()` moves the LifecycleOwner to DESTROYED,
   which makes CameraX unbind everything bound to it even if `shutdown()` timed
   out (e.g. a start holding the lock while waiting for a CameraX callback on
   the main thread that `runBlocking` is blocking).
2. **`CameraJpegSource.start` never throws for a camera failure** (controller
   ruling 2). The brief's single `catch (e: Exception)` also caught real
   coroutine cancellation (Kotlin's `CancellationException` is an `Exception`).
   Now a `CancellationException` is rethrown only if
   `currentCoroutineContext().isActive` is false; while active it came from
   CameraX cancelling its own future and returns `null`.
3. **No executor leak on a failed start.** The brief assigned
   `provider`/`executor`/`analysis` only after `bindToLifecycle`, so a bind that
   threw left the `live-camera` thread running, and `StreamController` does not
   call `stop()` after a `null` start. Fields are recorded as they are built,
   and both failure branches call a shared `release()` (also used by `stop()`).
4. **`start_stream`/`stop_stream`/disconnect launch with
   `CoroutineStart.UNDISPATCHED`.** A plain `scope.launch` on
   `Dispatchers.Default` gives no ordering, so a `start_stream` quickly followed
   by `stop_stream` could take the lock stop-first and leave the camera on with
   nobody watching. Undispatched, each call reaches the controller's lock on
   the single collector in arrival order, and `Mutex` is fair (FIFO). Not JVM
   tested (it needs a live socket in the service); recorded here for review.

Not a deviation: `ProcessCameraProvider.awaitInstance` **does** exist in
CameraX 1.6.2 (`ProcessCameraProviderExtKt`, import
`androidx.camera.lifecycle.awaitInstance`), so the brief's call compiles. The
comment in `monitor/ui/QrScanner.kt` saying otherwise is stale; left untouched.
`stream` is `@Volatile` because the capture loop reads it on another thread.
No manifest change (`camera|microphone` already declared).

RED: `./gradlew :monitor:testDebugUnitTest --tests '*MonitorServiceTest*'`
failed to compile (`No parameter with name 'watching' found`). GREEN: 7/7.
RED (ruling 1): `--tests '*StreamControllerTest*'` 20 tests, 2 failed
(`AssertionError` at `assertTrue(camera.stopped)` in both new tests). GREEN:
20/20.

Full run: `:shared` 100, `:monitor` 85, `:parent` 87 tests, 0 failures
(`--rerun-tasks`); no new compiler warnings. `npx vitest run` 133 passed;
typecheck clean; secret scan clean; `EncodingDumpTest` passed; conformance:
all 19 Kotlin encodings validate.

Device-only, not tested on the JVM (Task 11): `CameraJpegSource`,
`JpegEncoder`, `ServiceLifecycleOwner`, the on-screen banner, and the capture
loop's `stream?.onAudioWindow(buffer, read)` tee.

**Negative controls:**
1. In `stopLocked` only, reverted to `runCatching { video.stop() }` (first
   attempt's `sed` also hit the identical line in `startVideo`; redone with
   the 16-space indent anchored, `diff` showed one line changed). Result: exactly
   the two new shutdown tests FAILED, 18/20 passed. Restored, `cmp` identical.
2. In `buildOngoing`, `val text = status` (no watching prefix). Result: only
   `the ongoing notice says when a parent is watching` FAILED, 6/7 passed.
   Restored, `cmp` identical.
3. Removing `stream?.onAudioWindow(buffer, read)` from the capture loop: not
   done. The controller side is covered (`audio windows go out as
   little-endian PCM while streaming`), but the tee line is unreachable from
   `MonitorServiceTest`: no test saves credentials, so `onStartCommand` stops
   at "not paired" before building the stream, and RECORD_AUDIO is not granted,
   so the capture loop never runs. Reaching it would need a paired socket and
   a working AudioRecord, which Robolectric does not provide. Task 11 must hear
   audio in a live stream.
4. UNDISPATCHED ordering (deviation 4) has no control either. It relies on
   `kotlinx.coroutines.sync.Mutex` being FIFO-fair (documented behaviour),
   which is not verified here.


## Final review fixes

Whole-branch review of `d01f0fa..20d0406`
(`.superpowers/sdd/2026-09-15-m4-live-audio-video/final-findings.md`), fixed in
one wave. All twelve findings were confirmed on inspection; none was declined.
Full per-finding evidence (RED, GREEN, controls):
`.superpowers/sdd/2026-09-15-m4-live-audio-video/final-fix-report.md`.

| Finding | Fix | Covering tests |
| --- | --- | --- |
| I1 stream contents crash the parent | zod bounds (width/height <= 4096, sampleRate 8000-48000) with Kotlin twin constants checked by the conformance script; `LiveViewer` builds the player in a try (no sound for that stream, tried once, logged); unplayable formats skipped; `LiveViewModel.decode` reads the JPEG header and `LiveMediaLimits.decodable` refuses > 4096 px or > 2x the advertised size before allocating; a decoder exception skips one picture | `messages.test.ts` "stream format bounds"; `LiveMediaLimitsTest`; `LiveViewerTest` (player cannot be built, new stream retries, unplayable format, decode throws, decoder gets the format); conformance "protocol constants" |
| I2 vanished parent keeps camera on | `Watchdog.sweep` terminates any connection of either role silent past `LIVENESS_TIMEOUT_MS`; `close` then runs `dropViewer`. MONITOR_LOST unchanged | `watchdog.test.ts` "silent connections"; `relay.test.ts` "stops the stream when the last viewer goes silent...", "keeps a viewer inside the window" |
| I3 camera fails after bind | `StreamController`: `VIDEO_START_TIMEOUT_MS` (5 s) around the whole camera start, then `FIRST_FRAME_TIMEOUT_MS` (3 s) for a first frame or a CameraState error (`VideoSource.start(onJpeg, onError)`); otherwise stop the camera and fall back. Media goes out only after `stream_started`. `CameraJpegSource` observes CameraState | `StreamControllerTest`: never a frame, error while opening, frame within window, start hangs, audio during a pending start not sent |
| I4 audio advertised when not capturing | `StreamController(audioAvailable)`, wired to `record != null` in `MonitorService`; decline names camera and microphone; the parent's status reads "Live · picture only, the microphone is not available" when `audio` is null | `StreamControllerTest`: mic not capturing, neither, audio-only with no mic; `LiveTextTest` "picture but no sound" |
| I5 release during write | `PcmPlayer.release`: stop loop, pause+flush, join, release only if the thread exited; loop body is `PlaybackLoop`, which stops on a negative write | `PlaybackLoopTest` |
| T2 binary garbage test proved nothing | Subscribes UNDISPATCHED on `video`, sends garbage then a valid frame, asserts the first collected is the valid one | `HubSocketTest` |
| T7 one media buffer for both kinds | `MediaFeed` (shared): separate video (8) and audio (64) SharedFlows; used by `RoleSocket`/`HubSocket` (`video`, `audio`) and `LiveLink`; `AlertService` forwards each with its own collector; `LiveViewer` collects each. No merged flow | `MediaFeedTest`; `LiveViewerTest` "audio and video arrive on their own flows" |
| M2 no frame size cap | `maxPayload` 2 MiB; `forwardFrame` drops frames with payload > 1 MiB; **plus** an `error` listener on every hub socket (see deviations) | `relay.test.ts` 1009 close, oversized frame not forwarded |
| M3 request to a parent id | `relay.request` requires `role === "monitor"` | `relay.test.ts` "request naming a parent" |
| M4 stream ids ignored | `onStreamStarted`/`onStreamStopped` take the stream id and ignore a stale one; monitor socket close passes `null` (ends any stream) | `relay.test.ts` stale `stream_started`, stale `stream_stopped` |
| M5 stop regardless of monitor | `LiveSession.stop(monitorId)` no-ops when the session is on another monitor; `LiveViewer.stop` uses it | `LiveSessionTest` x3; `LiveViewerTest` "leaving one monitor's screen..." |
| M7 docs | ARCHITECTURE.md live-media section; this doc: Task 11 title, Task 5 control 3 corrected, Task 5 note moved out of the Task 6 section; MonitorService `onDestroy` comment corrected | - |

### Deviations

1. **Hub socket `error` listener (not in the findings).** Once `maxPayload` was
   set, an oversized frame made `ws` emit `error` on the server-side socket,
   which had no listener: vitest reported an uncaught `RangeError: Max payload
   size exceeded`. In production that is an uncaught exception in the hub
   process, i.e. one paired device could take the hub down. Added
   `ws.on("error", () => {})`; the close handler does the cleanup.
2. **`fake-parent` MONITOR_LOST test changed.** It swept at `Date.now() + 60 s`,
   which now also makes its own connected parent look silent and terminates
   it before the event arrives. It now ages only the monitor device's
   last-seen time and sweeps at the real clock.
3. **`Hub.relay` exposed** on `startHub`'s return, like `watchdog`, so relay
   tests can assert stream state the wire does not show.
4. **`VideoSource.start` gained `onError`**, and `StreamController` calls
   `stop()` after a start that returned null or timed out (releasing twice is
   safe in `CameraJpegSource`). The camera's proving first frame is not sent:
   media now waits for `stream_started`.
5. **Kotlin constants rather than Kotlin decode validation** for I1's "mirror
   in Kotlin". Throwing from `VideoFormat`/`AudioFormat` would make
   `decodeHubToParent` fail, which `RoleSocket` swallows by design, turning an
   out-of-range `stream_ready` into a silently dropped control message and a
   parent stuck on "Connecting". The hub rejects bad formats; the parent
   re-applies the same constants where it decodes and plays;
   `EncodingDumpTest` now dumps the shared constants and
   `check-protocol-conformance.mjs` compares them with `packages/protocol`.
6. **Gradle build cache and the conformance dump.** After a negative control,
   re-running `EncodingDumpTest` without `--rerun` was a cache hit that did not
   rewrite the dump file, so the conformance script read the stale NC dump.
   Run the dump with `--rerun` (or `--rerun-tasks`).
7. **Not fixed at the hub:** a parent joining a stream that is not ready yet
   still receives nothing until the monitor answers. With I3 the monitor
   always answers within 8 s (5 s start + 3 s first frame, under the parent's
   10 s), so the stream is no longer stuck; no hub-side pending timeout added.

### Device-only, for Task 11

- `PcmPlayer`: leaving the live screen mid-sound does not crash (release while
  a write is blocked); a player that fails to build (e.g. audio focus / output
  unavailable) leaves the picture running and logs `live audio unavailable`.
- `LiveViewModel.decode`: `inJustDecodeBounds` reports real sizes for the
  monitor's JPEGs (640x480 frames are shown, rotated).
- `CameraJpegSource`: with another app holding the camera, a Watch falls back
  to sound only within ~3 s and logs `camera state ... error`; a camera lost
  mid-stream is only logged (the picture freezes; not reported to the parent).
- Hub liveness: put the parent phone in airplane mode while watching; the
  monitor's camera turns off within ~60 s (45 s window + up to one 15 s sweep).
- Mic denied / held on the monitor: a Watch shows "Live · picture only, the
  microphone is not available" rather than plain "Live" over silence.

### Negative controls

Each rule was broken on purpose after green, the intended test failed, and the
code was restored (file copied back, suite green):

- Hub (`npx vitest run services/hub`, 99 tests): no `terminate()` -> 2 failed
  (watchdog unit + relay silent viewer); no `maxPayload` -> 1009 test failed;
  no `error` listener -> "Errors 1 error" (uncaught RangeError, non-zero exit);
  no role check -> parent-id test failed; no id check on `stream_started` ->
  stale-started test failed (`expected 320 to be 640`); no id check on
  `stream_stopped` -> stale-stopped test failed; no frame size check ->
  oversized-frame test failed.
- Protocol: `sampleRate` back to `positive()` -> 2 failed (sample rate bounds,
  stream_ready bounds). Kotlin `MAX_SAMPLE_RATE = 44100` -> conformance
  `REJECTED MAX_SAMPLE_RATE: Kotlin 44100, TypeScript 48000`, exit 1.
- Shared: `MediaFeed` with one buffer for both kinds -> `a burst of video does
  not drop audio` failed (`expected [0, 1] but was [0, 193, ..., 200]`) and
  `each kind arrives on its own flow` failed. Binary `onMessage` removed ->
  the rewritten T2 test (and `surfaces a binary media frame`) failed;
  undecodable bytes rethrown -> T2 test failed.
- Parent: `openAudio` exception not caught -> 3 failed; failure not remembered
  -> `expected 1 but was 10` attempts; no `playable` check -> 1 failed; decoder
  exception not caught -> `expected good but was null`; `LiveViewer.stop` calls
  unguarded `stop()` -> `expected [] but was [9]`; `LiveSession.stop(monitorId)`
  unguarded -> 2 failed; no advertised-size bound -> 1 failed; negative write
  ignored -> `expected 1 but was 5` writes; picture-only status line removed ->
  `LiveTextTest` failed (`expected Live · picture only... but was Live`).
- Monitor: `audioAvailable` ignored -> 3 failed; no first-frame proof -> 3
  failed; `onError` ignored -> `expected 0 but was 3000`; no start timeout ->
  `start still hanging`; media sent before `stream_started` -> 8 failed.
  Audio during a start still waiting for the camera is held back by two
  guards (`audioWanted` is only set after `stream_started`, and `announced`);
  with `audioWanted` set before the camera start *and* `announced` applied to
  JPEG only -> exactly `audio captured while the camera is still starting is
  not sent` failed. (Defeating either guard alone passes: they overlap.)

### Final run

`cd apps/android && ./gradlew :shared:testDebugUnitTest :monitor:testDebugUnitTest :parent:testDebugUnitTest --rerun-tasks`:
BUILD SUCCESSFUL (94 tasks executed); `:shared` 103 (4 skipped live tests), `:monitor` 93,
`:parent` 107, 0 failures. `npx vitest run`: 19 files, 146 tests passed;
`npm run -s typecheck` clean; `npm run -s secrets:scan` clean;
`npx tsc -b packages/protocol tools/fake-monitor tools/fake-parent` clean;
`EncodingDumpTest --rerun` then `node tools/check-protocol-conformance.mjs`:
all 24 Kotlin encodings and constants validate, exit 0.

## Task 11 — verified on the Pixel 6 and Pixel 8 Pro (2026-10-02)

Run from the controller session, not a subagent: the steps need a person at
the phones. Hub and branch both at `febf66b`; certificate fingerprint
unchanged (`D8:52:26:…`), so no re-pairing. Monitor FGS types `0x000000C0`
(camera|microphone) as M5 declared.

### Verified on hardware

| Claim | Evidence |
| --- | --- |
| The camera opens on a locked, dozing monitor | Pixel 6 asleep 45 s (`mWakefulness=Dozing`), Watch tapped on the Pixel 8 Pro: `camera started: 640x480 rotation=90` 3 s later, `dumpsys media.camera` shows `com.kidsmonitor.monitor` holding camera 0 |
| The picture arrives and is upright | Parent screenshot shows the room the right way up; the monitor sends `rotationDegrees=90` and the parent rotates |
| Audio plays | `AudioPlaybackConfiguration … type:android.media.AudioTrack state:started … sampleRate=16000`, threads `live-audio` + `AudioTrack` in the parent process |
| Glass-to-glass video latency ≈ 0.1–0.2 s | Millisecond clock on the laptop screen, `screencap` of the parent: request at 09:20:53.545 vs 09:20:53.455 on screen (90 ms), and 09:20:50.584 vs ~09:20:50.4 (≈150 ms). Both are upper-bound-free lower bounds: `screencap` grabs after the request |
| Pressing Home releases the camera | `camera stopped` 4 s after Home; `Device 0 is closed, no client instance` |
| Locking the parent releases the camera | Power key: `camera stopped` within 6 s, camera closed |
| Killing the parent releases the camera | `am force-stop`: `camera stopped` within 6 s. On relaunch the app opens on the alerts list (force-stop clears `rememberSaveable`), so there is no stuck "Connecting…" |
| A parent that vanishes releases the camera | Wifi off on the Pixel 8 Pro **mid-stream**: `camera stopped` 51 s later — the hub's new silent-connection termination (45–60 s). This is the final review's I2 fix doing its job; before it, a half-open socket held the camera for ~15 min |
| Two viewers share one camera | `fake-parent --watch` streaming (~57 frames/s), then Watch on the Pixel 8 Pro: **no second `camera started`**, both received frames. Home on the phone: fake-parent's count kept climbing, no `camera stopped`. Stopping fake-parent: `camera stopped` |
| Detection keeps working during a stream | Sustained speech at the Pixel 6 → `level=-27.5 dBFS` (trigger -44.1), hub event 262 `LOUD_NOISE` at 16:04:35Z, "Noise in the monitor" notification on the Pixel 8 Pro, camera still streaming |
| The monitor says it is being watched | Status screen: "A parent is watching live — the camera is on."; notification text: "A parent is watching live. Connected to the hub. Alerts are being delivered."; both cleared when watching stopped |
| A weak link does not take the monitor offline | Parent carried to the edge of wifi and back: no `MONITOR_LOST` in the hub (last one 15:37:22Z, before testing). The live stream stopped and restarted twice as the link dropped |
| Thermals, ~10 min of streaming | `Thermal Status: 0` before and after; battery temperature 31.7 °C → 32.4 °C; level 75 % → 74 % (plugged in) |
| Foreground-only (the owed Task 7 Step 9 negative control) | `Lifecycle.Event.ON_STOP -> viewModel.stop()` commented out, reinstalled: Home left the camera **running** (0 `camera stopped` in 15 s, camera still open). Restored and reinstalled: Home stopped it again in 8 s |

### Findings, not failures

- **First Watch after wifi returns can time out.** Right after the parent's
  wifi came back, Watch ended with "Stopped: the monitor did not start
  streaming within 10 seconds"; the next tap connected in 2 s. Reconnect
  timing, not a wedge: the retry path recovered on its own. Worth a look if it
  repeats.
- **"Try again" works.** After the wifi drop the screen showed "Stopped: lost
  the connection to the hub" with a working Try again button — the Task 7 fix
  round behaving as designed.
- **Live view holds the screen on** (`keepScreenOn` in `LiveViewScreen`), so
  `KEYCODE_SLEEP` is overridden (`SCREEN_BRIGHT_WAKE_LOCK … ON_AFTER_RELEASE`).
  A real power-button lock still stops the stream. Intended for watching, but
  it is a battery cost nobody wrote down before.
- **Landscape crops rather than fits.** Rotating the parent restarts the
  stream (activity recreation: `camera stopped` then `camera started` 0.1 s
  later) and the picture then fills the width and is cut off top and bottom.
  Portrait is correct. Cosmetic; deferred.
- Audio was confirmed by `dumpsys audio`, not by ear.

### Not verified

- A monitor on Android 17 (the Pixel 6 is SDK 36).
- The monitor on battery; everything here was plugged in.
- A second **real** parent phone (fake-parent stood in as the second viewer).
- (resolved) The locked-parent alert soak: Pixel 8 Pro locked and dozing for
  13 minutes — its `lastSeen` at the hub stayed current (heartbeats kept
  firing through Doze), so the new silence termination never touched it. The
  monitor was then force-stopped; `MONITOR_LOST` was raised 53 s later and the
  "the monitor is offline" notification reached the still-dozing parent within
  ~10 s. The risk the final review parked is closed.
- `fake-monitor` regression (plan Step 2): skipped deliberately — the real
  monitor exercised the same decode and audio path end to end.
- Camera-busy and mic-denied fallbacks on the device.
