# Onboarding brief — from `git clone` to a baby monitor you trust

**Status: design brief, not a guide.** It describes the adoption path end to
end, phase by phase, and marks each step **Today** (exists, with the file or
command) or **Gap** (does not exist yet). Nothing here has been built as a
single flow. The runnable parts of it live in
[`../guides/setup-hub.md`](../guides/setup-hub.md) and
[`../guides/deploying.md`](../guides/deploying.md).

Audience: whoever writes the onboarding path, and the coding agent that will
walk a stranger through it.

---

## The journey in fifteen lines

| Phase | What happens | The gate it must pass |
| --- | --- | --- |
| 0 | The agent reads the repo and knows what to do | **a root README and an `AGENTS.md` exist** — they do not yet; this is gap #1 |
| 1 | Clone; agent reports before installing anything | the user knows what goes on which machine first |
| 2 | Preflight: JDK, SDK 37, `adb`, Docker, phone API levels, same-network check | a go/no-go report, with **monitor needs Android 13+** checked first |
| 3 | Decide which machine is the always-on hub | the user has said out loud which box never sleeps |
| 4 | Hub up in Docker; `.env` in `deploy/`; static address | `{"ok":true}` from `/healthz`, `401` from `/events`, fingerprint saved |
| 5 | Build and install both apps, one per phone | both apps launch and show a pairing screen |
| 6 | Pair — adb handoff first, typed code second, QR optional | **parent paired first**, then the monitor appears online in it |
| 7 | Heartbeat → voice test → sensitivity → live video | an alert reaches a locked phone, and live video plays |
| 8 | Mains power, the manual-Start-after-reboot contract, DND access | the user knows the monitor never restarts itself |
| 9 | It's yours: extend it, no store policy in the way | — |

Three biggest gaps, in order: **no agent entry point (Phase 0)**, **no app build
guide (Phase 5)**, **no sensitivity UI (Phase 7)**.

---

## The pitch, and the one honest sentence under it

You have an old phone in a drawer. It has a better microphone, a better camera,
and more compute than any £150 baby monitor on the shelf — and unlike the one on
the shelf, it will not be streaming your child's bedroom through someone else's
datacentre on a certificate that expires in 2027.

The honest sentence: **this is not an app, it is a repository.** There is no
Play Store listing, no installer, no support line. Getting it running means
building two Android apps and standing up a small server on your own hardware.
That used to be a weekend. With a coding agent reading this repo it should be an
evening — and the point of this brief is to make it an evening rather than a
weekend.

### What "local-only" means here, exactly

Worth being precise, because the vague version of this claim is what every
vendor says.

**At runtime:** audio, video, noise levels, and alerts travel from phone to hub
to phone over your own wifi, over TLS, pinned to a certificate your hub
generated on first boot and that exists nowhere else. The hub listens on one
port on your LAN. Neither app accepts an inbound connection. There is no
account, no login, no telemetry from this project's own code, and no
server operated by this project — there is no server operated by this project *to* phone home to.
No audio or video is ever recorded; events carry a timestamp, a type, and a peak
level ([`../../ARCHITECTURE.md`](../../ARCHITECTURE.md), security model).

**At install time this is not true, and pretending otherwise would be silly.**
`npm` fetches from the npm registry, Docker pulls a base image, and Gradle pulls
from Google's Maven. Both apps also link Google's ML Kit barcode scanner
(`com.google.mlkit:barcode-scanning`) for the QR pairing path. The model is
bundled and decodes on-device, but ML Kit builds are generally understood to
register an initialisation provider and pull in Google's datatransport
libraries — **unverified for this build; nobody has read the merged manifest.**
So **do not claim it is inert just because nobody scanned a QR code** until
someone has checked that manifest and ML Kit's own data disclosure. **Gap:** verify it,
then offer a build flavour with the QR scanner compiled out, so anyone who wants
that claim can have it structurally rather than behaviourally.

The defensible, fully true version: *your child's audio and video never leave
your network, and no server belonging to this project exists.*

---

## Phase 0 — The front door (Gap #1, and it blocks everything)

The entire premise of this brief is "the user points an agent at the repo and
the agent takes it from there." That premise currently fails at step one.

- There is **no root `README.md`**. A stranger cloning this repo sees
  `ARCHITECTURE.md` and a `docs/` tree and has to infer the rest.
- There is **no `AGENTS.md` or `CLAUDE.md`**. Claude Code, Codex and Antigravity
  all look for one. They will find nothing and start guessing.
- There is **no Android build-and-install guide at all**. `docs/guides/` covers
  the hub and nothing else, yet two thirds of the work is the two apps.
- `docs/README.md`'s own routing rule says *"something a stranger needs in order
  to run this → `guides/`"*. The guide a stranger needs most is missing.

Everything below assumes these exist. **This is the highest-value thing to
build, and it is also the cheapest.** It is not built here; this brief is the
input to it.

---

## Phase 1 — Intrigue → clone

**User:** reads the README, thinks "I have a Pixel 4 in a drawer and a NUC under
the telly," clones the repo or tells an agent to.

**Agent:** clones, then **does not start installing anything**. It reads
`AGENTS.md` → this brief → runs preflight (Phase 2) and reports back before
touching the system.

**Gate:** the user knows what will be installed on which machine *before*
anything is installed.

**Today:** clone works. `npm install && npm run typecheck && npm test` is the
whole Node-side build and is exercised in CI
([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), Node 22).

**Gap:** the agent entry point of Phase 0.

---

## Phase 2 — Preflight and discovery

The agent's first real job: find out whether this is going to work *before*
spending the user's evening on it. Everything in this phase is read-only, and
everything in it is currently a **Gap** — there is no preflight script in the
repo. These are the checks it needs.

### On the development machine

| Check | How | Why it matters |
| --- | --- | --- |
| JDK present and recent | `java -version` | AGP 9.4.0 / Kotlin 2.4.20 need a current JDK |
| Android SDK located | look for `ANDROID_HOME`, `~/Library/Android/sdk`, `~/Android/Sdk` | `apps/android/local.properties` is **gitignored**, so a fresh clone has no `sdk.dir` and the agent must write one |
| SDK **platform 37** installed | `sdkmanager --list_installed` | both apps set `compileSdk = 37`; a missing platform is a long confusing Gradle failure |
| `adb` on PATH | `adb version` | needed for install, log reading, and the best pairing path |
| Node >= 22 | `node -v` | `package.json` `engines` |
| Docker + Compose plugin | `docker compose version` | only on the hub box |

### On the hub box

| Check | How |
| --- | --- |
| Reachable, and **always on** | ssh in; ask the user directly whether this machine ever sleeps or travels |
| Port free | `ss -ltn \| grep ':8443 '` — 8443 is popular; `HUB_PUBLISHED_PORT` exists precisely because of this |
| Same subnet as the phones | compare the agent's own LAN address with the hub's |
| Static address or DHCP reservation | see the warning in Phase 4 |

### On each phone (the one that disqualifies the most hardware)

| Check | How | Requirement |
| --- | --- | --- |
| API level | `adb shell getprop ro.build.version.sdk` | **monitor: 33 or higher (Android 13+)**; parent: 29+ (Android 10+) |
| Model / OEM | `adb shell getprop ro.product.model` | drives the battery-manager warning below |
| Developer mode + USB debugging | device shows in `adb devices` as `device`, not `unauthorized` | the user must accept the on-screen RSA prompt; the agent cannot |

### Are all three actually on the same network?

The user's own requirement — detect the gap, then explain it — and the one most
likely to produce a mysterious timeout later.

```bash
adb -s <serial> shell ip -4 addr show wlan0     # compare with the hub's subnet
adb -s <serial> shell ping -c 2 <hub-ip>
```

**The ping is also a diagnostic fork, which is why it is worth running even when
the subnets obviously match.** The adb shell should not be subject to the app's
`ACCESS_LOCAL_NETWORK` grant (**verify this on an Android 17 device before
relying on it**), so: ping succeeds but the app times out ⇒ a
permission problem (Phase 5). Ping fails ⇒ a network problem. That distinction is
otherwise invisible, and this project has misread it three times.

Common causes, one line each, which the agent should offer rather than let the
user guess: the phone is on a **guest SSID**, the access point has **client
isolation** enabled, a **mesh system** has put devices on separate VLANs, the
phone has silently fallen back to **mobile data**, or a **VPN on the phone** is
capturing LAN traffic.

**The monitor's `minSdk 33` is the tightest constraint in the whole system and
the one most likely to end the evening early.** A great many drawer phones are
Android 11 or 12. Preflight must check this *first* and say so plainly: "that
phone is Android 12, the monitor app needs 13 — do you have another, or shall we
look at what lowering `minSdk` would cost?" Before offering that, find the
reason for 33 in the M5 spec and plan; do not assume it is arbitrary.

**OEM battery managers.** On a Pixel, the parent app's persistent socket is
fine; this project's own two devices are Pixels and this risk was deliberately
not chased. On Xiaomi, Samsung, Oppo, Huawei and friends, aggressive app-killing
is the top residual risk to alert delivery
([roadmap](roadmap.md), "Before making the repository public"). Preflight should
name the OEM and set expectations rather than let the user discover it at 3am.

**Permission to act.** Before installing an SDK platform, a JDK, Docker, or
sideloading an APK, the agent asks once, lists exactly what it will install
where, and remembers the answer. Nobody enjoys discovering that an agent
installed 4 GB of Android SDK on a laptop with 6 GB free.

---

## Phase 3 — Which box is the hub?

The hub is **the trust anchor, the only stateful component, and the media
relay**. If it is asleep, you do not have a baby monitor; you have two phones
with nothing to say to each other.

**Agent:** proposes a role assignment and makes the user confirm it.

- **Best:** a separate always-on Linux box — Raspberry Pi 4+, NAS that runs
  containers, mini-PC, or a retired laptop with the lid-close action set to
  "do nothing."
- **Acceptable:** the development machine *is* the hub, if it is a desktop that
  stays on.
- **Refuse politely:** a laptop that goes in a bag. The agent should say the
  quiet part out loud — "this will stop monitoring your child the moment you
  close the lid" — and offer to continue only as a test setup, with an explicit
  "move the hub later" note.

**Gate:** the user has stated, in words, which machine stays on, and the agent
has recorded that choice.

**Gap:** a documented hub-on-the-dev-machine mode. The hub runs fine on macOS
via the published-port compose mapping, but the guides assume `ssh <hub-host>`,
and both `tools/pair-device.sh` and `tools/show-pairing-qr.sh` hardcode
`HUB_SSH=user@hub-host` as a default. Same-machine needs those to work without ssh.

---

## Phase 4 — Install the hub, and prove it is alive

**User:** watches a short sequence of commands and then sees `{"ok":true}`.

**Today**, all of it ([`../guides/setup-hub.md`](../guides/setup-hub.md)):

```bash
cp .env.example deploy/.env
```

Three things in that file matter, and two of them have bitten people:

- `HUB_PUBLIC_HOST` — **required** under Docker. The container cannot see its own
  LAN address, and a pairing QR built from the container's view sends the phone
  nowhere.
- `HUB_PUBLISHED_PORT` — change it if 8443 is taken. The current home deployment
  runs on 8444 for exactly this reason.
- The file **must live in `deploy/`**, next to the compose file. Compose resolves
  `.env` relative to the compose file, not your shell. A root `.env` is silently
  ignored.

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml logs hub
curl -sk https://<hub-ip>:8443/healthz
```

**Gate:** `{"ok":true}` from the healthz endpoint, `401` from `/events` (proving
no endpoint is unauthenticated), and the certificate fingerprint captured from
the logs. The agent should save that fingerprint — every later trust decision
compares against it.

**Two warnings to put in front of the user now, not later:**

1. **Give the hub a static address or a DHCP reservation.** `ARCHITECTURE.md`
   says a DHCP change "breaks nothing", and that is true *of the certificate
   pin* — trust follows the fingerprint, not the address. But each app stores
   the hub's URL verbatim (`HubCredentials.baseUrl` / `wsUrl` in
   `apps/android/shared/.../store/Credentials.kt`) and there is no mDNS
   discovery yet. If the hub's lease moves, every paired device simply stops
   finding it. Pin the address at the router.
2. **Never `docker compose down -v`.** That deletes the volume, which is the
   certificate and every paired device, and there is no recovery — every phone
   re-pairs from scratch. Plain `down` is safe.

**Gap, worth fixing before this gets popular:** mDNS/`_kidsmonitor._tcp`
discovery, which would make the address a non-issue and let a moved hub be
re-found rather than re-paired.

**Doc inconsistency to resolve:** [roadmap](roadmap.md) still lists "the Docker
image build has never been run" as open from M0–M2, while
[`../guides/setup-hub.md`](../guides/setup-hub.md) records a verified Ubuntu
24.04 deployment and [`../guides/deploying.md`](../guides/deploying.md) names a
running instance. One of those is stale. An onboarding user reading the roadmap
first will not trust the thing they just successfully installed.

---

## Phase 5 — Build and install the two apps

**User:** plugs both phones into the development machine, taps "allow" on two
USB-debugging prompts, and waits.

**Agent:**

1. Writes `apps/android/local.properties` with the discovered `sdk.dir`.
2. Builds and installs **one phone at a time, by serial**:

   ```bash
   ANDROID_SERIAL=<parent-serial>  ./gradlew :parent:installDebug
   ANDROID_SERIAL=<monitor-serial> ./gradlew :monitor:installDebug
   ```

   `installDebug` ignores `adb -s` and installs to **every** connected device,
   so with both phones plugged in each one gets both apps. `ANDROID_SERIAL` is
   what Gradle honours.
3. **Debug builds, deliberately.** The adb pairing handoff in Phase 6 is gated
   on `BuildConfig.DEBUG`, and `adb logcat -s KidsMonitor` is the only
   diagnostic channel there is.
4. Grants nothing by hand. The permission requests must come from the apps, in
   one batch, on the device — see the warning below.

**Gate:** both apps launch and show their pairing screen.

**Gap:** this entire phase is undocumented. No build guide, no per-device
install recipe, no "which module goes on which phone" note. Second only to
Phase 0 in value.

**The permission trap that will eat an hour if nobody writes it down.** On
Android 17 (SDK 37), `ACCESS_LOCAL_NETWORK` is a mandatory runtime permission,
and a denied one makes every hub connection **time out** instead of failing.
The symptom is identical to "the hub is down." This project has misdiagnosed it
as a slow network three separate times
([lessons-learned](../lessons-learned.md), 2026-09-10 and 2026-10-02). Any
troubleshooting flow must check the permission *before* it looks at the network:

```bash
adb shell dumpsys package com.kidsmonitor.parent | grep -i local_network
```

The same lesson covers the other half of it: permissions requested back-to-back
while another dialog is open are **silently dropped**, with only a logcat
warning. Both apps now request their whole set through one launcher. Do not
"tidy" that.

---

## Phase 6 — Pairing

For each new monitor, ask the user for a recognizable name unless they have
already provided one. Suggest a room such as Nursery or Playroom and compare
with the existing names; do not silently use a model such as Pixel 6. The app's
**Name this monitor** step precedes all three paths below, including ADB payload
redemption. Complete it within the code's five-minute lifetime. The current
procedure lives in [installing-monitor.md](../guides/installing-monitor.md#choose-a-name).

This is where the user decides whether to trust the software, so it deserves the
most care — and the user's instinct is right: by this point their phone is in
developer mode, wired to their desktop, with an RSA key they personally
approved. **Demanding a camera scan at that moment is asking them to re-establish
trust they have already established, through a channel they trust less.** Three
paths exist. Rank them this way.

### 1. The adb handoff — default when a cable is plugged in

```bash
tools/pair-device.sh parent   # then: tools/pair-device.sh monitor
```

It issues a fresh single-use code on the hub and delivers the **exact same
payload the QR carries, fingerprint included**, straight into the app over adb.
No camera, no typing, no screen-pointing-at-screen. The certificate pin is
unchanged — this removes the camera, not the check. It is already the path used
during development and it is the one that fits a wired, trusted phone.

And it does not need a cable: **wireless debugging** (`adb pair` /
`adb connect`, Android 11+) means the monitor can sit in its final mount, on the
wall or by the cot, and still be paired this way — no camera, no typing, nothing
to point at a screen.

Caveats, all currently **Gaps**:
- Debug builds only — `MainActivity` drops the extra unless `BuildConfig.DEBUG`.
- Defaults to `HUB_SSH=user@hub-host`, the author's machine. Needs to work for a
  local hub with no ssh at all.
- The script force-stops the app (`am start -S`) on purpose: an already-running
  app receives the intent via `onNewIntent`, `MainActivity` reads the payload
  only in `onCreate`, and the single-use code burns with nothing happening.
  Worth fixing properly rather than documenting.

### 2. Typed code plus a six-character check — no camera, no cable

Tap **Enter a code instead**, type the host, port, and six digits. The app then
connects with a trust-nothing client, shows six characters derived from the
certificate, and asks whether the hub shows the same ones. The hub printed them
next to the code on the `check:` line.

This is SSH's and Bluetooth's numeric-comparison model, and the reasoning is
worth repeating to the user because it is the one moment where their attention
genuinely matters: six digits cannot carry a 256-bit fingerprint, so **that one
glance is what distinguishes your hub from anyone else on your wifi claiming to
be it.** Nothing is sent until they answer — if the characters differ, saying
"they do not match" sends nothing and the code survives for a real retry.

Six digits are safe because the hub rate-limits redemption: five failures in a
rolling minute lock redemption hub-wide, doubling to fifteen minutes. Over a
code's five-minute life that is roughly 25 guesses against a million — about one
in forty thousand. Verified on hardware, including that a monitor code typed
into the parent app is refused with 409 and stays unused.

### 3. QR — available, never required

```bash
tools/show-pairing-qr.sh parent
```

Strongest in theory: it carries the fingerprint with no human in the loop. Worst
in practice at exactly this moment, as the user suspected — the phone may be
tethered to a desk, holding a device steady at a terminal window is awkward, and
being asked for camera access to set up a privacy-first baby monitor is a
reasonable thing to flinch at. Keep it. Default to paths 1 and 2.

### Order matters

**Pair the parent first, then the monitor.** Then the user's very next action is
watching the monitor appear as *online* in their own parent app — the system
proving itself, rather than a terminal claiming success. That is the "it's alive"
moment and it is free.

### Be straight about the camera

"No camera needed" applies to **pairing**. The monitor app needs camera
permission to do its job — live video is the feature. It also needs the
microphone, which is the whole point. What it does not need is a camera scan to
establish trust, and the monitor's own screen says plainly when a parent is
watching so that nobody in the room is unaware the camera is on.

**Also tell them how to undo it.** `devices.js list` and
`devices.js revoke <id>` are in [`../guides/setup-hub.md`](../guides/setup-hub.md).
Revocation drops the socket immediately. Knowing you can revoke is part of
agreeing to pair.

---

## Phase 7 — Hello, world: four tests that must pass

The user needs to stop trusting the documentation and start trusting the device.
These are the tests, in order, and each one has a way to fail that looks like a
bug but is not.

### Test 1 — Heartbeat

Monitor shows "Listening to this room" with a live level meter. Parent shows
the monitor **online**, with a battery percentage.

Behind it: the monitor heartbeats every 15 s; the parent polls `/monitors` every
15 s while visible, so a changed battery reading can take ~30 s to appear. Not
a bug, and the onboarding copy should say so before the user reports it.

**Gap:** an onboarding checklist screen that walks these four tests and shows
pass/fail. Today the user is reading a doc and interpreting two separate app
screens.

### Test 2 — The voice test

**First, let the noise floor settle — and know which kind of settling you are
waiting for.** The trigger is **floor + 12 dB** (`thresholdDb`), and there are
two ways the floor gets set:

- **Nothing calibrates on Start — not on the first one either.**
  `MonitorService` creates a `Calibrator` only when the hub sends `recalibrate`
  (`MonitorService.kt`, the `HubToMonitor.Recalibrate` branch), and the hub
  sends that only from `POST /monitors/:id/recalibrate`. What the hub *does* do
  on every monitor connect is push the stored config
  (`services/hub/src/ws/server.ts`), which sets the detector's floor to
  `noiseFloorDb`: **-50 dBFS on a monitor that has never been calibrated**, or
  the last calibrated floor on one that has. From there it drifts toward the
  real room on a 60-second exponential moving average, but only while the level
  stays *below* threshold.

  So on a brand-new monitor, give it a minute of a normal, quiet room before
  testing. The status screen prints `alerts above <value>` from the first
  second, because the service publishes the stored floor as soon as capture
  starts — in that first minute the number is a guess about your room, not a
  measurement of it. The honest readout is the log, which prints
  `level= floor= trigger=` on a throttle: `adb logcat -s KidsMonitor`. Watch
  `floor` stop moving, then test.

  **Gap, and the hub already has the hook for it:** `MonitorConfigRepo` has a
  `calibratedAt()` method that **nothing in the hub calls**. "Calibrate the
  first time a monitor ever connects" is a few lines away, and it is exactly
  what onboarding wants.
- **An explicit 30-second calibration** happens when someone runs
  `POST /monitors/<id>/recalibrate`. Then the screen shows "Calibrating — keep
  the room as it normally is…", and **detection is deliberately switched off
  for that whole window** (spec §9.1). A voice test during calibration cannot
  fire, and talking into it raises the measured floor and desensitises the
  detector afterwards. Wait for the banner to clear.

Then: **talk at the phone, at normal volume, for about five seconds.**

Not two. Not a clap. The detector requires the level to stay above the trigger
for a **sustained 1.5 s window** — that is what stops a door closing or a cough
from waking a parent at 2am. Clapping produces peaks well above the trigger and
no alert at all, which has already been mistaken for a broken detector once
([lessons-learned](../lessons-learned.md), 2026-10-02). Reproduce the *shape*
the detector looks for, not just the amplitude. Five seconds of normal speech is
comfortably over the line and leaves no ambiguity.

**Gate:** an alert arrives on the parent's phone, including when it is locked.
The alert also lands on the Alerts screen's 24-hour noise timeline as a marked
peak.

### Test 3 — Adjust the threshold, then test again

**Mind the 60-second cooldown.** `cooldownS` defaults to 60. A retest inside a
minute of the first alert produces **no alert, correctly**, and looks exactly
like a threshold change that broke something. The onboarding flow must either
wait out the cooldown or say, in large letters, to wait a minute.

**This is the biggest UX gap in the product.** There is no settings UI in either
app, and **no `GET` for the config either** — the hub registers exactly
`PUT /monitors/:id/config` and `POST /monitors/:id/recalibrate` and nothing that
reads the current values back (`services/hub/src/http.ts`). Changing sensitivity
today means a `curl` against the hub with a parent token, composing the whole
object blind:

```bash
curl -sk -X PUT https://<hub>:8443/monitors/<monitor-id>/config \
  -H "Authorization: Bearer <PARENT TOKEN>" -H 'content-type: application/json' \
  -d '{"thresholdDb":9,"sustainMs":1500,"cooldownS":60,"noiseFloorDb":-50,
       "motionEnabled":false,"motionThreshold":12,"calibrationWindowMs":30000}'
```

Four sharp edges in that one command:

- **It is a full replace, not a patch, and there is nothing to read first.**
  Omit a field and it reverts to whatever you typed. With no `GET`, the agent
  cannot merge against the live values; it has to track them itself, or accept
  the defaults from `DEFAULT_MONITOR_CONFIG` in
  `packages/protocol/src/messages.ts` as its baseline.
- **It will wipe the learned noise floor.** `noiseFloorDb` lives in the same
  object, the hub writes the calibrated value into it
  (`MonitorConfigRepo.recordCalibration`), and on the phone
  `NoiseDetector.updateConfig` assigns `noiseFloorDb = config.noiseFloorDb` and
  resets. So the `-50` in the command above throws away a floor that may have
  taken a minute or a 30-second calibration to establish. **Always follow a
  `PUT` with `POST …/recalibrate` and wait for the banner to clear**, or accept
  that the first few minutes after a config change are measured from a guess.
- **It needs a parent token**, which a desktop agent does not have. It can get
  one by pairing itself with `tools/fake-parent` — in which case it should give
  that device an obvious name and **revoke it when onboarding finishes.** An
  un-revoked "Setup Agent" device is a live bearer credential for a camera in a
  child's bedroom. Not a footnote.
- `POST /monitors/<id>/recalibrate` returns `409` if the monitor is offline, and
  `202` otherwise — `202` means "started", not "finished". Thirty seconds.

**Gap, prioritised:** a sensitivity control in the parent app. Even three
presets — *Light sleeper / Normal / Only real crying* mapping to `thresholdDb`
and `sustainMs` — would remove the only step in this entire onboarding flow that
requires a terminal. It wants a `GET /monitors/:id/config` to exist, a
calibrate-after-change step built in, and a visible "calibrating" state, so that
none of the four edges above are the user's problem.

### Test 4 — Live video

From the parent's Alerts list, tap through to live view.

Expect: picture within a couple of seconds, upright regardless of how the
monitor is propped (orientation follows gravity, and works with the monitor's
screen locked), glass-to-glass latency around 0.1–0.2 s, and sound. The
monitor's screen says "A parent is watching live — the camera is on."

Leaving the screen or locking the parent's phone stops the stream and releases
the monitor's camera. That is intended: continuous video cooks an old phone in a
closed room, and detection is audio-driven anyway. Audio is always on; video is
on demand.

Cold start retries up to three times on its own, with phase deadlines, and falls
back to sound-only if video cannot be prepared. If it fails, the parent app
distinguishes hub trouble from a monitor that has not responded from video that
would not prepare — read what it actually says before blaming the network. And
check `ACCESS_LOCAL_NETWORK` first. Always check `ACCESS_LOCAL_NETWORK` first.

### Honest limits, told up front

Verified on a Pixel 6 monitor (Android 16) and a Pixel 8 Pro parent (Android 17):
92 hours of continuous capture without throttling, capture through a locked
dozing screen, alerts to a locked phone, `MONITOR_LOST` in ~40 s after a reboot.

**Not verified:** a monitor on Android 17, **a monitor running on battery**, a
second real parent phone, a locked parent left idle beyond the hub's 45–60 s
silence window, and the camera-busy / microphone-denied fallbacks on a device.
A stranger deserves that list before they rely on this overnight. See
[progress-connection-recovery.md](progress-connection-recovery.md) and
[progress-cold-start-retries.md](progress-cold-start-retries.md).

---

## Phase 8 — Power, permanence, and the 3am contract

Four things to tell the user once everything works, before they walk away.

**1. Mains power. A cable, not a power bank.** The monitor is a phone running a
camera and microphone forever; it will out-eat any battery you own. Plug it into
the wall. A power bank is a battery with extra steps and a worse failure mode —
and "monitor on battery" is explicitly in the **not verified** list, so the
honest advice is a wall socket and a cable long enough to reach the cot without
becoming a cot toy. Phones in warm closed rooms also appreciate not being under
a blanket.

**2. The monitor does not start itself after a reboot. Ever.** Android 15+
refuses to let `BOOT_COMPLETED` start a camera or microphone foreground service.
Someone must physically tap **Start**. This is not a bug and it cannot be worked
around; the system is designed around it, which is why a missing monitor raises
an *alarm* rather than a silent gap. Practical consequences: don't reboot the
monitor at bedtime, and expect `MONITOR_LOST` on the parent about 40 s after any
restart. The monitor's own screen says this in plain text, which is the right
place for it.

**3. Let the alarm through Do Not Disturb.** The monitor-offline alarm needs
notification-policy access. Without it, `setBypassDnd` is **silently ignored** —
the setting appears to apply and does nothing. The parent app shows a banner for
this; onboarding should make the user clear it rather than dismiss it.

**4. Updating is a `git pull`.** `git push home main` from the dev machine,
`git pull && docker compose up -d --build` on the hub
([`../guides/deploying.md`](../guides/deploying.md)). The data volume survives,
so the certificate fingerprint survives, so every paired phone keeps working.
Verify with `docker logs kids-monitor-hub | grep fingerprint`. And once more, in
case it has been five minutes: not `down -v`.

---

## Phase 9 — It's yours now

The closing message, and the actual reason to prefer this over the thing on the
shelf.

There is no App Store review, no Play Store policy, no "background microphone
access is not permitted for this app category", no SDK deprecation forcing a
rewrite, and nobody's growth team deciding your monitor now needs an account and
a subscription. It is your phone, your network, your code. The unglamorous
corollary is that there is also no support line, and if you break it you fix it
— but you *can* fix it, which is more than the shelf offers.

Things that are entirely reasonable to build on top, none of which anyone can
stop you from building:

- A sensitivity preset UI (please — see Phase 7).
- Motion detection: already in the protocol, `motionEnabled: false` by default
  and unexercised.
- A third monitor. Multiple monitors and multiple parents are supported in the
  protocol already; events fan out to every parent, and acknowledgement is
  per-device.
- A wall-mounted tablet that is permanently the live view.
- Pushing noise history into whatever home dashboard you already run.
- A smart bulb that glows when the room gets loud, because nothing in a local
  system is stopping you.
- Phase 2 (iOS parent) and Phase 3 (internet relay) are scoped in the
  [roadmap](roadmap.md), with the transport already behind an interface.

The extension points are real, not aspirational: `packages/protocol` generates
both the TypeScript and the Kotlin message types from one source, so the hub and
the apps cannot drift. `tools/fake-monitor` and `tools/fake-parent` let you
develop against the hub with no phone and no Android SDK at all. Adding a
message type is one schema edit and a regenerate.

Good luck. Your child will one day find out their nursery was monitored by a
Pixel 4 running software their parent compiled, and will be either extremely
impressed or extremely embarrassed. There is no third outcome.

---

## Prioritised build list

In this order. Each line is roughly "what it unblocks."

1. **Root `README.md` + `AGENTS.md`.** Unblocks the entire agent-led premise.
   Cheapest item on the list.
2. **`docs/guides/building-the-apps.md`.** The one missing guide, covering
   `local.properties`, SDK 37, per-device install, and the
   `ACCESS_LOCAL_NETWORK` symptom.
3. **`tools/preflight.sh`** (or a skill the agent runs): the Phase 2 table, as
   one read-only script that ends in a go/no-go report. The `minSdk 33` check
   alone saves wasted evenings.
4. **Sensitivity presets in the parent app**, plus the
   `GET /monitors/:id/config` that has to exist for them to be editable rather
   than overwritable. Removes the only mandatory terminal step from the happy
   path, and the only step that can silently destroy a calibrated noise floor.
5. **Make `pair-device.sh` and `show-pairing-qr.sh` work for a local hub** (drop
   the `HUB_SSH=user@hub-host` assumption), and fix the `onNewIntent` payload bug
   so pairing does not need a force-stop.
6. **An in-app onboarding checklist**: heartbeat → voice test → live view, with
   the calibration wait and the 60 s cooldown handled by the UI rather than by a
   footnote in a document.
7. **mDNS discovery.** Turns "give your hub a static IP" from a requirement into
   an optimisation, and lets a moved hub be re-found rather than re-paired.
8. **An OEM battery-management section** for non-Pixel devices — already flagged
   as a prerequisite for making the repository public.
9. **Reconcile the roadmap's stale "Docker build never run" line** with the two
   guides that document a running deployment, and **verify the ML Kit claim** in
   the merged manifest so the local-only promise can be stated without a hedge.
10. **Hub-on-the-dev-machine mode**, documented, for people with exactly one
    always-on computer.

---

## Appendix — what a stranger can actually run today

```bash
# hub
cp .env.example deploy/.env                 # edit HUB_PUBLIC_HOST, port
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml logs hub          # fingerprint
curl -sk https://<hub>:8443/healthz                           # {"ok":true}

# pairing codes
docker compose -f deploy/docker-compose.yml exec hub \
  node services/hub/dist/cli/pair.js parent                   # then: monitor
tools/show-pairing-qr.sh parent                               # PNG instead
tools/pair-device.sh parent                                   # over adb

# devices
... exec hub node services/hub/dist/cli/devices.js list
... exec hub node services/hub/dist/cli/devices.js revoke <id>

# no phone required
npx tsc -b tools/fake-monitor && node tools/fake-monitor/dist/index.js --host <hub> --code <CODE>
npx tsc -b tools/fake-parent  && node tools/fake-parent/dist/index.js  --host <hub> --code <CODE>

# apps  (from apps/android; needs local.properties with sdk.dir)
ANDROID_SERIAL=<serial> ./gradlew :parent:installDebug
ANDROID_SERIAL=<serial> ./gradlew :monitor:installDebug
adb logcat -s KidsMonitor

# sensitivity (no GET exists; PUT replaces the whole object, then recalibrate)
curl -sk -X PUT  https://<hub>:8443/monitors/<id>/config      -H "Authorization: Bearer <PARENT TOKEN>" ...
curl -sk -X POST https://<hub>:8443/monitors/<id>/recalibrate -H "Authorization: Bearer <PARENT TOKEN>"

# repo checks
npm install && npm run typecheck && npm test && npm run secrets:scan
```
