# Lessons Learned

Failures and discoveries from building and testing this system. The point is to
stop us paying twice for the same surprise.

**Write an entry when:** something failed in a way the design did not predict, a
platform behaved differently from its documentation, a debugging session took
more than an hour, or a decision turned out to be wrong.

**Do not write an entry for:** ordinary bugs caught by tests, or anything already
captured in the architecture doc or the spec.

Newest first.

## Format

```
## YYYY-MM-DD — Short title

**What happened.** The observable failure or surprise.

**Why.** The actual cause, once known.

**What we changed.** Code, design, or documentation. Link the commit.

**How to avoid it.** The generalisable lesson, if there is one. Say so if there
isn't — "this was just an expensive typo" is a legitimate entry.
```

---

## 2026-10-05 — Returning after idle needs automatic recovery and honest connection states

**What happened.** During long-running testing, returning to the parent app
after 10–20 minutes away repeatedly showed "Not connected to the hub" and an
offline monitor. Refresh could take roughly ten seconds; opening Watch could
time out after ten seconds and succeed only after two or three manual retries.
Both Pixels were plugged in. A connection attempt, an unreachable hub, a
monitor that had stopped reporting, and a camera still preparing all looked
like the same failure to the user.

**Why.** The exact trigger for the original intermittent interruption remains
unconfirmed. We found several independent defects that made recovery slower
or misleading:

- Reconnect backoff accumulated across successful sessions instead of resetting,
  so unrelated interruptions could leave the next attempt waiting 60 seconds.
- A failed hub status request marked cached monitors offline, although it gave
  us no current evidence about the monitors. A boolean socket state also could
  not describe connecting or waiting to retry, and status could wait for the
  next 15-second poll after returning to the app.
- Live startup made one request with a ten-second deadline and did not preserve
  viewing intent through a socket reconnect. Hub contact, monitor response,
  camera preparation, and the first decoded picture were not tracked separately.
- Reconnect history HTTP ran inside the sole socket event consumer. A stalled
  history request could prevent processing `stream_ready` even after it arrived.
  HTTP also inherited the socket's unlimited read timeout.

No intentional 20-minute sleep timer or observed home-server suspension was
found. Charging and a locked screen alone do not establish Android deep Doze;
we cannot attribute the failure to the child sleeping without telemetry.

**What we changed.** Android revision `8fe9896` and hub revision `81e1d33` add
explicit connection/retry states, reset backoff after success, bounded and
cancellable HTTP, immediate foreground status checks, and automatic transient
status/video retries. Live startup reports acknowledged hub, monitor, and video
stages, checks for a decoded first picture, and reconnects a socket that fails
to acknowledge a request. Attempt ids reject stale replies; leaving Watch
cancels retries and releases the camera. History backfill runs independently,
with a persisted cursor that advances only after all pages succeed. Failed
status checks preserve identity and show unknown/unavailable instead of offline.
No new wake lock or faster heartbeat was added.

See [connection recovery and battery assessment](planning/progress-connection-recovery.md)
and [cold-start retry implementation and hardware results](planning/progress-cold-start-retries.md).
On October 3, a 12-minute charging, locked-phone check retained reporting with
no monitor-loss event. Watch then succeeded on its first attempt; a controlled
hub restart with the monitor locked recovered video automatically in about
1.4 seconds. These results verify those recovery paths, not elimination of the
original intermittent failure or unplugged battery impact.

**How to avoid it.** Treat return from idle as a required startup path. Recheck
current status on resume, recover automatically within bounded attempts, and
show the stage actually observed. Failure to reach the hub means monitor status
is unknown; only current hub evidence can justify offline. Keep history I/O
out of the control-message consumer, reset backoff after success, and correlate
retries so late responses cannot replace a newer session. Test after realistic
idle intervals and deliberate network/hub interruption, including first-picture
delivery, cancellation, and missed-event recovery. Record power state and
timestamps before proposing wake locks or exemptions; measure battery cost
with matched unplugged runs.

---

## 2026-09-15 — Permission requests fired back to back were silently dropped

**What happened.** On the Pixel 8 Pro (Android 17) the parent app's pairing hung
forever: payload accepted, "contacting the hub", then nothing. On the Pixel 6 the
monitor had only CAMERA after first launch, with RECORD_AUDIO and notifications
still denied.

**Why.** Both pairing screens called `launch()` on two or three permission
launchers in a row inside one `LaunchedEffect`. Android shows one permission
dialog at a time and **drops** any request made while one is open. The only
evidence is a warning in the device log:
`W Activity: Can request only one set of permissions at a time`. The first
request got a dialog and the rest never happened. On the parent the dropped one
was `ACCESS_LOCAL_NETWORK`, which on Android 17 turns into a connection that
times out rather than fails, so a permissions bug looked like the network.

A second surprise: Android 17 shows that permission as *"find, connect to, and
determine the relative position of nearby devices"*. Nothing in the wording says
"your home server", and a user could easily decline it.

**What we changed.** `RuntimePermissions` in `:shared` builds each role's
missing list for the running SDK; each screen has exactly one
`RequestMultiplePermissions` launcher (`bd38fbe`). Verified on the Pixel 8 Pro
after a data clear: one `REQUEST_PERMISSIONS`, camera then nearby-devices, zero
dropped-request warnings.

**How to avoid it.** One launcher per screen, one request per moment. The guard
counts launchers in the source file, because Robolectric shows no dialogs and so
cannot observe the drop. That makes it a check on how the code is built, not a
behaviour test, and it is labelled that way. The general lesson is the one this
file keeps repeating: when Android refuses something here it logs a warning at
most, and you only see it if you read the device log.

---

## 2026-09-15 — The "flaky" socket test was a production race, and the first theory was wrong

**What happened.** `HubSocketTest` failed only when the `:shared`, `:monitor` and
`:parent` suites ran together, never alone. It was first noticed on 09-11, passed
on re-run, and was blamed on OkHttp's platform initialiser printing
`Log.isLoggable not mocked`.

**Why.** That log line was harmless: it appears on suites with zero failures.
The real failure was a 5 s timeout waiting for `Connected`. Socket events were a
`SharedFlow(replay = 0)`, which drops anything emitted while nobody is
collecting, and every caller launched a collector and then connected at once.
Launching a coroutine does not start it collecting immediately. When the TLS
handshake was faster than the collector starting, which happens under load,
`Connected` was lost. The same pattern was in the socket wrappers **and in both
production services**: the parent could skip backfill (spec §6.5) and either app
could show "Not connected" over a working connection.

**What we changed.** Events are now a queue (`Channel` + `receiveAsFlow`); the
wrappers `map` it instead of forwarding through a second collector (`dcf8e94`).
A test waits on `isConnected` (a `StateFlow`, which cannot drop a value) before
collecting, so it fails every time on the old code, without depending on
timing. The combined run went from 0/2 to 5/5.

**How to avoid it.** A test that passes on re-run has still failed once, and
that failure is information. Here the failure was a real race in a rare
production path, the same shape as the hub's `socket.write()`/`destroy()` race in
M3. Two specific rules: a `Thread.sleep` placed before an assertion usually means
a race is being covered up (the message tests had three), and "the log mentions
X" is not a diagnosis until you've read the actual assertion that failed.

---

## 2026-09-11 — The hub we had been testing against was three weeks of work out of date

**What happened.** At the start of M5 Task 9 — the on-device verification the
whole milestone exists for — a pre-flight check found the container on
`hub-host` running `05e8d27` on branch `feat/m3-parent-app`. Every M5 commit
was absent. 122 hub tests and 171 Android tests were green on the laptop against
code that was not the code answering on port 8444.

**Why.** The deployment is a git clone that updates by `git pull`, and nothing
pulls it but a person. The branch it sat on was whatever was checked out the last
time someone deployed. There is no drift *alarm* because there is no drift
*detector*: a stale clone serves requests perfectly happily, and the version it
is serving is invisible from the client side.

**What this would have cost.** Two of the M5 fixes live exactly on the paths
Task 9 exercises, so the failures would have been loud but misdirected:

- The `.nullish()` schema fix. Against the M3 hub, **every event the Android
  monitor sends is rejected** — the monitor works, the hub works, and no alert
  ever arrives. The obvious suspect is the brand-new monitor app.
- The `socket.end()` refusal flush (`71fcf3a`). Task 9 Step 1 deliberately feeds
  the monitor a *parent* code and expects a readable refusal. The M3 hub races
  and yields a bare "socket disconnected" instead — the exact symptom the fix
  removes, on the exact step that checks for it. The natural reading is that the
  fix did not work.

Both would have been debugged on the phone, which is the one place the bug
wasn't.

**What we changed.** Redeployed from `feat/m5-monitor-app` before touching a
device, and confirmed `git rev-parse HEAD` matched the branch tip. The
certificate fingerprint was unchanged across the rebuild, as
[`guides/deploying.md`](guides/deploying.md) promises — the volume persists — so
no device needed re-pairing on account of the redeploy.

**How to avoid it.** **Check what is deployed before you trust a device test.**
A passing test suite is evidence about the working tree, and nothing else; the
deployed binary is a separate artifact that no amount of local green says
anything about. The deploy guide already documents `git rev-parse --short HEAD`
for this — the gap was that nothing *required* running it. Verifying the
deployment is now the first step of any on-device task, and the version check
belongs in the plan rather than in someone's memory.

The wider version of the same lesson, which this project keeps relearning: the
expensive failures are the ones where **a correct component is blamed for a
stale one's behaviour**. Pin down what is actually running before interpreting
what you see.

---

## 2026-09-10 — Android 17 blocks the local network, and it looks exactly like the server being down

**What happened.** Pairing failed three times on a real Pixel 8 Pro running
Android 17. The app logged `failed to connect to /192.168.1.100 (port 8444) after
10000ms`. From the same phone, `ping` and `nc -z 192.168.1.100 8444` both
succeeded. Routing was correct for the app's uid. The hub was healthy.

**Why.** Android 17 makes `ACCESS_LOCAL_NETWORK` a **mandatory runtime
permission** for apps targeting SDK 37+. `INTERNET` no longer covers the local
network. This entire product exists to talk to a hub on the home LAN, so nothing
in it could work.

The failure mode is what made it expensive: **blocked TCP connections time out
rather than erroring.** There is no `SecurityException`, no permission denial in
the log, nothing naming the permission. It is indistinguishable from the server
being unreachable. The `adb` probes all passed because shell runs as a system
uid and is not gated — so every quick check said the network was fine.

**What we changed.** Declared `ACCESS_LOCAL_NETWORK`, requested it at runtime on
the pairing screen alongside the camera, and added a manifest regression test.

**How to avoid it.** When a symptom says "cannot reach the host" but every probe
says the host is reachable, **stop trusting the symptom and check what differs
between the probe and the app.** Here it was the uid. `adb shell` is not a proxy
for app behaviour on anything permission-gated, and Android keeps moving more
things behind permissions — this was the third such trap in this project, after
the `dataSync` timeout and the Do Not Disturb gate.

---

## 2026-09-10 — `connectedDevice` needs a Bluetooth permission it never occurred to us to want

**What happened.** With the local-network permission fixed, the app crashed
instantly on launch:

```
SecurityException: Starting FGS with type connectedDevice targetSDK=37
requires ... all of [FOREGROUND_SERVICE_CONNECTED_DEVICE]
and any of [BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN,
            CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE,
            CHANGE_WIFI_MULTICAST_STATE, NFC, TRANSMIT_IR, UWB_RANGING, ...]
```

**Why.** `connectedDevice` had been chosen because its documentation covers
devices reached "over a network", and because `dataSync` is time-capped. But the
permission gate reveals what the type actually means: peripherals — Bluetooth,
NFC, USB, IR. A server on the wifi is not what Android has in mind, and on
Android 17 the type demands one of those capability permissions.

**What we changed.** Moved to `specialUse`, with the justification stated in a
manifest `<property>`. Verified against the timeout documentation first: only
`dataSync`, `mediaProcessing` and `shortService` are time-limited, so this does
not reintroduce the six-hour problem. Confirmed on the device — the service runs
with `types=0x40000000`.

The tempting fix was to declare `CHANGE_NETWORK_STATE` and keep the type. That
would have passed the check by claiming a capability the app never uses, in a
manifest users can read.

**How to avoid it.** *When a platform gates a category behind a permission,
the permission list is the real definition of that category* — better evidence
than the prose description. And when the honest answer is "my case is not in
your list", platforms usually provide an explicit escape hatch; `specialUse`
exists for exactly this, and using it costs nothing but a written justification.

---

## 2026-09-09 — A missing manifest permission looked like a dead button

**What happened.** On the pairing screen, tapping "Grant camera access" did
nothing. No dialog, no error, no log entry. `adb shell pm grant ... CAMERA`
also reported success and changed nothing.

**Why.** `android.permission.CAMERA` was never declared in the manifest. It was
in the plan, and got dropped when the manifest was rewritten to add the
foreground-service permissions.

Android's handling of that is quiet in both directions: requesting an undeclared
runtime permission returns *denied* immediately without showing a dialog, and
`pm grant` exits 0 while doing nothing. From the user's side the button is
simply dead.

**Why no test caught it.** There *was* a manifest test — it asserted four
permissions were declared and CAMERA was not among them. The test enumerated
what the author remembered to list, so it could only ever confirm the
already-known.

**What we changed.** Declared `CAMERA` and the optional `android.hardware.camera`
feature, and added a regression test naming the symptom so the next reader knows
why it exists.

**How to avoid it.** Two things generalise.

*An allow-list test only checks the entries you thought of.* It cannot fail for
the item you forgot, which is the only item likely to be wrong. Where the real
requirement is "the app can do X", prefer a test that exercises X.

*Silence is a failure mode.* This bug produced no exception, no log, and a
success exit code from the tooling. When something does nothing at all, suspect
a declaration that was never made rather than logic that ran and chose wrong.

---

## 2026-09-09 — A green test said Do Not Disturb was bypassed; the device said otherwise

**What happened.** The parent app's `MONITOR_LOST` channel calls
`setBypassDnd(true)` — spec §6.4, because a monitor that stopped reporting means
nobody is watching the child, and that alert has to survive Do Not Disturb. A
Robolectric test asserted `channel.canBypassDnd() == true` and passed. Dumping
the channel on a real Pixel 8 Pro showed **`mBypassDnd=false`**.

**Why.** Two compounding causes.

`setBypassDnd()` is silently ignored unless the app holds Do Not Disturb policy
access. That is not a runtime permission — there is no dialog to show; the user
grants it from a Settings screen. Android does not warn, throw, or log: it drops
the request and reports the channel as not bypassing.

And **Robolectric does not model that gate at all.** A diagnostic confirmed it
returning `policyAccessGranted=false` and `canBypassDnd()=true` in the same
breath. The test could not have failed, whatever the code did.

There was a third trap underneath: **a channel's `bypassDnd` cannot be edited
after creation.** The natural sequence — app creates channels on first launch,
user grants access later — leaves the channel permanently stuck, because the
later `createNotificationChannel` call silently does nothing.

**What we changed.** Declared `ACCESS_NOTIFICATION_POLICY`. Added
`isDndBypassGranted()` and an intent to the Settings screen so the UI can ask.
`createChannels` now deletes and rebuilds the monitor-lost channel when it finds
it not bypassing *and* access has since been granted. Verified on the device:
granting access and relaunching flips it to `mBypassDnd=true`. The misleading
unit test was renamed to say it asserts the *request*, not the effect, with the
reason in a comment.

**How to avoid it.** *A passing test on a simulator is evidence about the
simulator.* Robolectric, Room's in-memory database and MockWebServer are all
worth having, but each models a subset of the platform and none of them says
which subset. For anything that depends on a permission gate, a system service's
policy, or an OEM's behaviour, the only honest verification is the device — and
the cheapest time to look is right after writing the code, not at the end.

The tell here was structural, and it generalises: **the test asserted a property
of an object the code had just constructed.** `channel.canBypassDnd()` reads
back what `setBypassDnd()` wrote; nothing in that round trip involves Android
deciding anything. When a test's subject and its oracle are the same object, it
is checking that the setter works.

---

## 2026-09-09 — The container build was broken three ways, and local tests could not see any of them

**What happened.** 109 tests, a typecheck, a secret scan, and a manual drive
against a hub started with `node` were all green. The first real
`docker compose build`, on the target Ubuntu server, failed immediately — and
then failed twice more on the way to running.

**Why.** Three independent defects, none of which any local check could reach:

1. **`.dockerignore` was inert.** It sat in `services/hub/`, next to the
   Dockerfile. Docker reads `<context>/.dockerignore` or
   `<dockerfile-path>.dockerignore`; the context is the repository root, so the
   file matched neither and had never done anything.
2. **A stale `tsconfig.tsbuildinfo` reached the build context.** `tsc -b` read
   it, concluded the protocol package was already built, skipped it, and never
   emitted `dist/index.d.ts`. Every hub source file then failed with
   `Cannot find module '@kids-monitor/protocol'`. The error pointed at the hub;
   the cause was a skipped dependency.
3. **The published port was hardcoded**, and 8443 was already taken on the
   target box by an unrelated service. `port is already allocated`.

A fourth, milder one: Compose resolves `.env` relative to the compose file's
directory, so a `.env` at the repository root was silently ignored and the hub
came up on the default port anyway.

**What we changed.** Moved `.dockerignore` to the context root and extended it.
Switched the build to `tsc -b --force`, so no incremental state can affect a
container build regardless of what leaks in. Made the port
`HUB_PUBLISHED_PORT`. Documented that `.env` belongs in `deploy/`.

**How to avoid it.** *A deployment target is a test environment, not a
formality.* Everything about this build was green locally and none of it
exercised the packaging. Build the image on the real host early, while the
architecture is still cheap to change — the three defects here were each a
one-line fix found in twenty minutes, and each would have been someone's
confusing first hour with the repository.

Two smaller transferable lessons. **A path-based config file that is in the
wrong place fails silently, not loudly** — `.dockerignore` and `.env` both did
exactly that here, so verify they are read rather than assuming they are.
And **incremental build state is poison in a container**: always build with
`--force` or equivalent where the whole point is a reproducible tree.

---

## 2026-09-09 — `dataSync` would have silently killed alerts every night

**What happened.** Caught in design review, before any code: the parent app's
alert socket was going to live in a foreground service typed `dataSync`, which is
the obvious choice for "keeps a connection open".

**Why.** Android 15+ caps `dataSync` and `mediaProcessing` foreground services at
6 hours per rolling 24 hours. On exhaustion the system calls `onTimeout()`, stops
the service, and refuses to start another until the user manually foregrounds the
app. A 24/7 alert socket exhausts that quota daily. The failure mode is the worst
possible one for this product: alerts just stop arriving, with no error anywhere
the user would look.

**What we changed.** Parent service is typed `connectedDevice`
(`FOREGROUND_SERVICE_CONNECTED_DEVICE`), which has no time limit and honestly
describes maintaining a network connection to the monitor. Recorded as spec §3.4.

**How to avoid it.** For anything that must run indefinitely, check the *type's*
duration limits, not just whether the type is permitted. "It starts successfully"
and "it still runs six hours later" are different questions, and only the second
one matters for a monitor.

---

## 2026-09-09 — The monitor cannot restart itself after a reboot

**What happened.** Found during design, before any code: the plan assumed the
monitor app would relaunch on `BOOT_COMPLETED` so a power blip would self-heal.

**Why.** Android 15+ throws `ForegroundServiceStartNotAllowedException` when a
`BOOT_COMPLETED` receiver starts a foreground service of type `camera`,
`microphone`, `mediaPlayback`, `mediaProjection`, or `phoneCall`. There is no
supported workaround — it is a deliberate privacy restriction.

**What we changed.** Dropped auto-restart from the design entirely. Added
`MONITOR_LOST` as a first-class event on a high-priority alarm channel, so the
parent is told loudly when the monitor stops reporting.

**How to avoid it.** Verify platform restrictions on capture, background
execution, and push delivery *before* the architecture depends on them. Every
one of the three constraints in the Phase 1 spec was checked against current
documentation rather than recalled; the two that were recalled correctly cost
nothing to confirm, and this one would have cost a rewrite.

---

## 2026-10-02 — A viewer that vanishes is not a viewer that disconnects

**What happened.** M4's live view released the monitor's camera on every exit
we had thought about: Back, Home, lock, process death. Then the final review
asked what happens when the parent phone simply disappears — airplane mode, a
dead battery, walking out of wifi range. Nothing in the hub noticed. The
viewer's TCP connection sat half-open, `close` never fired, and the camera
stayed on until Linux gave up retransmitting, about 15 minutes later.

**Why.** The hub released viewers only on the socket's `close` event, and its
watchdog swept *monitors* for silence, never parents. A comment in the server
even claimed the walk-out-of-range case was handled. Every test we had used a
clean disconnect, which fires `close` immediately, so the gap was invisible.

**What we changed.** The watchdog now terminates any connection of either role
that has been silent longer than the liveness timeout; `close` then runs the
existing viewer-release path. On the Pixel 6, turning off the parent's wifi
mid-stream now releases the camera in 51 s instead of ~15 min.

**How to avoid it.** When a resource is held on behalf of a peer, ask what
happens when the peer stops existing rather than when it leaves politely. A
clean close is the easy half; silence is the half that costs battery, heat and
privacy. Test it by killing the network, not the app.

---

## 2026-10-02 — Claps do not trigger a sustained-noise detector

**What happened.** While verifying that noise detection keeps working during a
live stream, clapping near the monitor produced peaks above the trigger but no
alert. Talking at the phone for a few seconds fired one immediately.

**Why.** The detector asks for the level to stay above the trigger for a
sustained window (1.5 s by default), which is what keeps a door closing or a
cough from waking a parent. A clap is a few milliseconds of energy; it moves
the 5-second level log and nothing else.

**How to avoid it.** When testing a detector by hand, reproduce the *shape* the
detector looks for, not just its amplitude. A failed hand test can mean the
test was wrong, so check the detector's own definition before filing a bug.

---

## 2026-10-02 — "Asks for no permissions" is not a safety property

**What happened.** Manual pairing exists partly so someone can connect a phone
without granting camera access. The plan turned that into a rule — the typed
screen requests no permissions at all — and a test enforced it by reading the
screen's source for permission launchers. Both apps shipped that way, green.

On a freshly installed app on the Pixel 8 Pro, typed pairing then failed with
"could not reach the hub: failed to connect … after 10000ms". The hub was fine;
the parent app simply had no `ACCESS_LOCAL_NETWORK`, which Android 17 denies by
default and which makes a LAN connection *time out* rather than refuse.

**Why.** The real requirement was "never ask for the camera". "Ask for nothing"
was a tidier-sounding rule that happened to also forbid the one permission the
feature needs. The test enforced the wrong rule faithfully, and the JVM cannot
tell the difference: only a device with the permission denied can.

**What we changed.** `RuntimePermissions.PARENT_TYPED` and `MONITOR_TYPED` list
what the typed path needs — local network, notifications, and on the monitor the
microphone — with the camera deliberately absent, requested through one
launcher. The screen tests now assert the camera is never requested and that
there is exactly one launcher.

**How to avoid it.** When a feature's selling point is *not* needing something,
write the rule as the exclusion ("never X"), not as an absolute ("nothing at
all"). And remember that a missing Android permission often shows up as a
timeout rather than an error: this is the third time in this project that
`ACCESS_LOCAL_NETWORK` has presented as "the network is slow".
