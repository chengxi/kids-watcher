# M3 Progress and Handoff

**Plan:** `2026-09-09-m3-parent-android-app.md` (kept outside this repository)
**Spec:** `2026-09-09-phase-1-lan-monitor-design.md` (kept outside this repository)
**Branch:** `feat/m3-parent-app`
**Hub under test:** `https://192.168.1.100:8444` (see [`../guides/deploying.md`](../guides/deploying.md))

Anyone picking this up: read the plan, then "Deviations" and "Notes" below, then
start at the first task not marked done. Each task ends with a green test and a
commit.

## Status

| Task | Title | State | Notes |
| --- | --- | --- | --- |
| 1 | Gradle skeleton that builds and launches | **done** | 4 deviations, see below |
| 2 | `:shared` protocol data classes | **done** | |
| 3 | `:shared` fingerprint-pinned TLS | **done** | |
| 4 | `:shared` pairing payload + credentials | **done** | encryption is device-only, see below |
| 5 | `:shared` REST client | **done** | live-tested against the real hub |
| 6 | `:shared` hub socket | **done** | live-tested against the real hub |
| 7 | `:parent` Room cache | **done** | |
| 8 | `:parent` repository + backfill | **done** | |
| 9 | `:parent` notification channels | **done** | DND bypass needed a device to catch |
| 10 | `:parent` foreground service | **done** | runtime check deferred to Task 12/13 |
| 11 | `:parent` pairing screen | **done** | |
| 12 | `:parent` alert list + app shell | **done** | on-device pairing still unverified |
| 13 | On-device verification | **done** | verified on Pixel 8 Pro, Android 17 |

## How to verify where things stand

```bash
cd apps/android && ./gradlew :shared:testDebugUnitTest :parent:testDebugUnitTest
```

## Deviations from the plan

Recorded as they happen, with the reason. A deviation with no reason is a bug.

### Task 1 — four things the plan got wrong about the toolchain

Task 1 existed to burn down toolchain risk before anything depended on it. It
found four problems. The plan has been updated so a re-run starts from the
working configuration.

**1. AGP 9 rejects the `org.jetbrains.kotlin.android` plugin.**
Kotlin support is built into AGP from 9.0, and applying the plugin is a hard
error: *"The 'org.jetbrains.kotlin.android' plugin is no longer required for
Kotlin support since AGP 9.0."* Removed it from both modules and from the root
build file. The `kotlin.serialization`, `kotlin.compose`, and `ksp` plugins are
still applied normally. `kotlin { jvmToolchain(17) }` also went away — with
built-in Kotlin there is no separate `kotlin {}` block, and `compileOptions`
inside `android {}` is enough.

**2. `compileSdk` had to go from 35 to 37.** Not a preference: **28 dependencies
refuse to compile against 35**, including the whole Compose 1.12 stack,
`androidx.core` 1.18, `activity` 1.13, `navigation` 2.10 and `okhttp-android`
5.5. The plan predicted this risk and said "install the platform, do not
downgrade AGP" — that is what happened. `targetSdk` moved to 37 as well.

**3. The installed `android-37.0` platform was corrupt.** Its contents sat one
directory too deep (`platforms/android-37.0/android-37.0/android.jar` instead of
`platforms/android-37.0/android.jar`), so AGP could not find `android.jar`. The
symptom was maximally unhelpful: `Could not determine the dependencies of task
':shared:compileDebugJavaWithJavac' > Cannot query the value of this provider
because it has no value available` — no mention of the SDK at all. Fixed by
moving the broken directory aside and reinstalling with
`android sdk install "platforms;android-37.0"`. The old copy is still at
`platforms/android-37.0.broken` and can be deleted.

Also note `compileSdkMinor = 0` is set in both modules, because the platform
carries a minor version (37.0).

**4. `Theme.Material3.DynamicColors.DayNight` does not exist for a Compose-only
app.** That theme ships with the Material Components *views* library, which this
app has no other reason to depend on. Replaced with a minimal
`Theme.KidsMonitor` in `res/values/themes.xml` (plus a `values-night` variant)
parented off the framework theme. Compose draws everything; the XML theme only
has to hand off cleanly to the first frame.

## Notes for whoever picks this up

### The pin is proven, not assumed

`HubTrustLiveTest` runs against the real hub (skipped when `HUB_URL` and
`HUB_FINGERPRINT` are unset, so CI stays green off-network):

```bash
FP=$(ssh <hub-host> 'docker logs kids-monitor-hub 2>&1 | grep -o "fingerprint: .*" | tail -1 | cut -d" " -f2')
cd apps/android && HUB_URL=https://192.168.1.100:8444 HUB_FINGERPRINT="$FP" \
  ./gradlew :shared:testDebugUnitTest --tests '*HubTrustLiveTest*'
```

Two things were done to keep that test honest:

- It asserts a **`FingerprintMismatchException` specifically**, not merely that
  something threw. The looser version would also pass on a night when the hub
  was unreachable — claiming the pin worked when it was never exercised.
- It was **negative-controlled**: with the comparison sabotaged to accept any
  certificate, the test fails. It is testing what it claims to test.

A third test asserting "the presented certificate equals the pinned one" was
written and then dropped as redundant — the connect test cannot pass unless that
is already true. It also happened to fail, because OkHttp reported an empty
`peerCertificates` list; that is worth knowing if anyone reaches for
`response.handshake` later.

### Cross-language protocol conformance

Kotlin and TypeScript each test themselves; neither proves they agree.
`tools/check-protocol-conformance.mjs` closes that: a Kotlin test dumps every
`ParentToHub` encoding and the script parses each one with the hub's own zod
schemas. All 7 validate.

```bash
cd apps/android && ./gradlew :shared:testDebugUnitTest --tests '*EncodingDumpTest*'
node tools/check-protocol-conformance.mjs
```

### Task 4 — Robolectric cannot reach the Android Keystore

`EncryptedSharedPreferences` throws `KeyStoreException: AndroidKeyStore not
found` under Robolectric: the Keystore is not emulated. Two consequences:

**`Credentials` now takes its `SharedPreferences` from an injected provider.**
The production constructor builds the Keystore-backed encrypted store; tests
pass plain preferences. It is the same class and the same field logic either
way, so save/load/clear/overwrite and every null path are genuinely covered.
**Only the cipher layer is unverified by unit tests** — Task 13 must confirm on a
real device that the token round-trips through `EncryptedSharedPreferences` and
that the on-disk file is not readable plaintext.

**Robolectric was bumped 4.13 → 4.16.1, and the emulated SDK pinned to 36.**
Robolectric refuses to run when `targetSdk` exceeds what it ships images for;
4.13 capped at 34 and even 4.16.1 caps at 36, while this app targets 37. Pinned
globally in `src/test/resources/robolectric.properties` for both modules rather
than annotating every class. Unit tests therefore exercise API 36 behaviour;
anything that actually changed in 37 needs a device.

### Running the live tests

Two suites talk to the real hub and skip themselves when their environment
variables are unset, so CI stays green off-network.

`HubTrustLiveTest` needs only the fingerprint. `HubApiLiveTest` and
`HubSocketLiveTest` each need a **fresh single-use pairing code**, so they have
to be driven by a script — and each consumes its own code:

```bash
FP=$(ssh <hub-host> 'docker logs kids-monitor-hub 2>&1 | grep -o "fingerprint: .*" | tail -1 | cut -d" " -f2')
CODE=$(ssh <hub-host> 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml exec -T hub \
  node services/hub/dist/cli/pair.js parent 2>/dev/null' | grep 'code:' | awk '{print $2}')
cd apps/android && HUB_HOST=192.168.1.100 HUB_PORT=8444 HUB_FINGERPRINT="$FP" HUB_PAIRING_CODE="$CODE" \
  ./gradlew :shared:testDebugUnitTest --tests '*HubApiLiveTest*' --rerun-tasks
```

`--rerun-tasks` matters: without it Gradle caches the previous pass and the test
silently does not run against the new code.

It leaves a real device registered on the hub. Revoke it afterwards:

```bash
ssh <hub-host> 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml exec -T hub \
  node services/hub/dist/cli/devices.js list'
```

### An unreproduced flake in the hub suite

One run of `npx vitest run` reported **1 failed of 116**. Sixteen subsequent
runs — including three with a Gradle build running concurrently, which is what
was happening the first time — were all clean, and the failure detail was not
captured before the output scrolled.

**RESOLVED 2026-09-10, during M5.** It was a real race in the hub's websocket
rejection path — `socket.write()` followed by `socket.destroy()`, where destroy
could win and the client saw a bare disconnect instead of the 401. Fixed with
`socket.end()`, which flushes first. See `progress-m5.md`.

The original note follows.

So: **the hub suite is not known to be deterministic.** Likely suspects are the
timing-sensitive tests that use real timers, real sockets and MockWebServer:
`relay`, `watchdog`, `ws-auth`, `backfill`, `monitor-config`. If it resurfaces,
capture the failing test name immediately — that is the piece that is missing.

Not chased further: it is not blocking, and sixteen clean runs is the point of
diminishing returns without a reproduction.

### Task 13 — verified on the Pixel 8 Pro (Android 17, SDK 37)

Every check below was observed on real hardware against the deployed hub. The
app is paired as `Pixel 8 Pro`, role `parent`.

| Check | Evidence |
| --- | --- |
| Pairing by QR | Hub registry shows the device with a live `lastSeen` |
| Foreground service | `isForeground=true`, `types=0x40000000` (SPECIAL_USE) |
| Token encrypted at rest | `hub_credentials.xml` holds only Tink ciphertext — even the *keys* are encrypted, and no host, URL or fingerprint appears in plaintext |
| Alert with app backgrounded | Notification on `alerts`: "08:53:49 · peak -6.5 dB · 2.6s", matching the values sent |
| **`MONITOR_LOST` through Do Not Disturb** | `zen_mode=1`, screen `Dozing`; notification landed on `monitor_lost` and the screen woke to `Awake` |
| **Overnight-gap backfill (spec §6.5)** | App force-stopped, three events raised while dead, all three present after reopening; 8 events in the app, 8 on the hub |
| Cache with the hub stopped | Full history renders under a red "Not connected — you are not receiving alerts right now" banner |
| Revocation | No crash, process alive, no hot reconnect loop |

**Device settings changed for testing and restored afterwards:**
`screen_off_timeout` (back to 60000ms) and Do Not Disturb (back off). Left in
place because the app needs them: camera and notification permissions, and Do
Not Disturb policy access.

**The parent device was revoked as the final test**, so the app now shows the
disconnected banner. Re-pair with `tools/show-pairing-qr.sh parent` when
convenient.

### An unreproduced flake in the hub suite

One run of `npx vitest run` reported **1 failed of 116**. Sixteen subsequent
runs — including three with a Gradle build running concurrently, which is what
was happening the first time — were all clean, and the failure detail was not
captured before the output scrolled.

**RESOLVED 2026-09-10, during M5.** It was a real race in the hub's websocket
rejection path — `socket.write()` followed by `socket.destroy()`, where destroy
could win and the client saw a bare disconnect instead of the 401. Fixed with
`socket.end()`, which flushes first. See `progress-m5.md`.

The original note follows.

So: **the hub suite is not known to be deterministic.** Likely suspects are the
timing-sensitive tests that use real timers, real sockets and MockWebServer:
`relay`, `watchdog`, `ws-auth`, `backfill`, `monitor-config`. If it resurfaces,
capture the failing test name immediately — that is the piece that is missing.

Not chased further: it is not blocking, and sixteen clean runs is the point of
diminishing returns without a reproduction.

### Still to do on the device: pairing has never completed

Everything is installed and the pairing screen renders correctly on the Pixel 8
Pro, but **no device has ever completed a pairing**. Two things got in the way,
both now fixed:

- The pairing QR carried the container's own address (`172.23.0.2:8443`) instead
  of the LAN one. Fixed in the hub; see the commit for `HUB_PUBLIC_HOST`.
- `android.permission.CAMERA` was missing from the manifest, so the "Grant
  camera access" button did nothing at all. Fixed and regression-tested.

The camera fix is verified in the built APK (`aapt2 dump permissions` lists
CAMERA) and by a test, but **not yet confirmed on the phone** — it disconnected
before the check could run.

To pick this up: plug the Pixel in, unlock it, `./gradlew :parent:installDebug`,
then generate a QR and scan it. The hub must be reachable at
`https://192.168.1.100:8444`.

### Testing a ViewModel that does real IO

`PairingViewModelTest` cost two rounds of debugging, both worth knowing:

- **`advanceUntilIdle()` does not wait for `Dispatchers.IO`.** `HubApi` does its
  network work in `withContext(Dispatchers.IO)`, which the test scheduler does
  not control, so the assertion ran before pairing finished. The tests now await
  a terminal state on the flow rather than advancing virtual time.
- **`withTimeout` inside `runTest` counts *virtual* time**, so a 10-second
  timeout expired instantly against real work. It has to be wrapped in
  `withContext(Dispatchers.Default)`. The exception message says so, which is
  the only reason this took minutes instead of an hour.

A repeat-scan guard is also under test: the camera decodes the same QR many
times a second, and without it every frame would redeem the code again — the
second attempt failing with 403 and overwriting a success that already happened.
Negative-controlled: removing the guard fails that test alone.

### Task 10 — what was and was not verified on the device

**Verified on the Pixel 8 Pro (SDK 37):** Android 17 parsed the
`connectedDevice` service declaration and granted
`FOREGROUND_SERVICE_CONNECTED_DEVICE`. So the type is accepted on the newest
platform this app targets — which was the open question, since foreground
service rules are what keep changing between releases.

**Not verified: the service actually running.** `adb am start-foreground-service`
is refused with `Requires permission not exported from uid ...`, because the
service is correctly `exported="false"`. Nothing but the app itself can start
it, and the app does not start it until pairing exists (Task 12). The runtime
behaviour — `startForeground` succeeding, the socket connecting, the six-hour
mark passing without a stop — is Task 12/13 work.

**The `dataSync` guard is real.** Flipping the manifest to `dataSync` fails both
manifest assertions with the reason in the message. That guard is the whole
defence against someone reintroducing the bug in `docs/lessons-learned.md`.

**A second Keystore seam.** `AlertService` loads credentials, so it hit the same
Robolectric limitation as Task 4. It now goes through
`AlertService.credentialsFactory`, and `:shared` exposes
`Credentials.withPreferences(provider)` — a named factory rather than a public
constructor, documented as never being for a real token.

### Do Not Disturb bypass needs policy access, and only a device can tell you

Written up fully in [`../lessons-learned.md`](../lessons-learned.md). Short
version: `setBypassDnd(true)` is silently ignored without Do Not Disturb policy
access, Robolectric does not model that gate (it reports `canBypassDnd()=true`
with access denied), and a channel's `bypassDnd` cannot be edited after
creation. `createChannels` now rebuilds the channel once access appears.

Verified on the Pixel 8 Pro. Access was granted for testing with:

```bash
adb -s <serial> shell cmd notification allow_dnd com.kidsmonitor.parent
```

**Task 12 must surface this in the UI.** Real users have no adb: the app has to
notice `isDndBypassGranted()` is false and offer
`dndAccessSettingsIntent()`, or the product's most important alert stays muted
by Do Not Disturb on every install.

To inspect the channels on a device:

```bash
adb -s <serial> shell dumpsys notification --noredact | grep -o "mId='monitor_lost'[^}]*"
```

### The upsert must not be a REPLACE, and there is a test for it

`EventDao.upsertAll` is deliberately an insert-ignore followed by a targeted
update that never writes `acked`. The obvious one-liner —
`@Insert(onConflict = REPLACE)` — is wrong here: backfill re-delivers events the
app already holds and has already acknowledged, and REPLACE resets them to
unacked, re-notifying the user for alerts they already dismissed.

Negative-controlled: substituting the REPLACE version fails exactly the two
tests that guard this, and nothing else. If someone "simplifies" that method
later, the suite will say so.

### A MockWebServer gotcha worth remembering

`HubSocketTest` initially failed five tests with
`AssertionError: Gave up waiting for queue to shut down`, reported from
`tearDown`. The tests themselves were fine: MockWebServer refuses to close while
OkHttp still holds a connection, and the failure is attributed to whichever test
happened to run rather than to the cleanup. Teardown now closes the server-side
socket, shuts down the dispatcher's executor, and evicts the connection pool
before closing the server. If a socket test ever fails with a message about a
queue, look at teardown before looking at the test.

### Target device: Pixel 8 Pro, Android 17 (SDK 37)

`<pixel-8-pro-serial>` — a Pixel 8 Pro on Android 17, confirmed installing and
launching the app. This is the device the parent app is actually for, and it
changes the risk picture in two ways.

**It matches `targetSdk` exactly.** Nothing else available does: Robolectric
caps at SDK 36, the emulator image is 36, and the other connected phone (a
Pixel 6) is also 36. So this is the only place where SDK 37 behaviour gets
exercised at all — which matters most for the foreground service in Task 10,
since foreground-service rules are exactly what keeps changing between releases.

**Both target devices are Pixels, so the OEM battery-management risk is not
being chased.** Spec §3.3 rates it the top residual risk, and on stock Google
firmware it is small. Decision (2026-09-09): do not spend development effort on
it. The mitigations already exist and cost nothing to keep — watchdog, backoff
reconnect, backfill on reconnect, visible disconnected banner — so leave them in,
but do not treat a clean night on a Pixel as a reason to remove them either.

Writing this up for other developers is a **publish-time task**, tracked in
[`roadmap.md`](roadmap.md), not a development-time one.

A Pixel 6 on Android 16 (`<pixel-6-serial>`) is also connected and usable as a
second device.

### Building from the command line

`gradle` is not on PATH and `JAVA_HOME` must point at Android Studio's bundled
JBR. The wrapper was bootstrapped from a cached Gradle 9.6.0 distribution:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd apps/android && ./gradlew :parent:assembleDebug
```

Java 25 (the bundled JBR) worked with Gradle 9.7.1 and AGP 9.4.0 — the plan's
JDK 21 fallback was not needed.

### A physical phone is plugged in but unauthorized

`adb devices` shows `<pixel-6-serial>  unauthorized`. Accept the "Allow USB
debugging?" prompt on the phone to use it. Task 13 wants a real device: the
emulator cannot test doze or OEM battery management, which spec §3.3 names as
the highest residual risk in the design.

Verification so far has used the `Pixel_8_Pro` AVD (`emulator-5554`).

### The debug APK is 69 MB

Mostly ML Kit barcode scanning. Fine for sideloading onto family phones, which
is the only distribution this app has.
