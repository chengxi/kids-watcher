# Connection recovery and long-running monitoring

Date: 2026-10-03

The parent showed “Not connected to the hub” and marked the Pixel 6 offline
after a long idle period, then recovered roughly ten seconds later. The old
UI had two distinct problems: a socket boolean could not describe a connection
attempt, and an unsuccessful `/monitors` request rewrote every cached monitor
as offline. That failure provides no evidence that the monitor itself is down.

## Implemented

- Shared transport state reports stopped, connecting, connected, reconnecting,
  and waiting to retry, including the retry interval and failure reason.
  Parent banners and ongoing notifications distinguish those states. Connection
  attempts show a progress indicator; interrupted delivery still warns that
  live alerts may be delayed.
- Monitor rows show checking, unknown while awaiting the hub, unavailable after
  a failed status request, online, or offline explicitly reported by the hub.
  Unknown and offline rows include the last report time when available. Watch
  and battery readings require a successful current status request and a
  connected alert socket. Cached names and alert history remain available.
- Status polling refreshes immediately on foreground/resume, socket recovery,
  and Refresh. It runs every 15 seconds only while the activity is STARTED;
  it stops when the phone is locked or the app leaves the foreground. Refresh
  also asks the service to skip a pending reconnect backoff. It preserves a
  healthy socket and does not interrupt an in-progress connection attempt.
- Reconnect backoff resets after a successful connection for both roles. It
  previously accumulated across successful sessions, eventually reaching a
  60-second delay after enough unrelated interruptions.
- HTTP requests now have a maximum ten-second whole-call timeout, preserving
  a caller's shorter timeout, and cancellation closes the underlying call.
  The socket client deliberately has unlimited read timeout; previously HTTP
  inherited it, so an accepted request with a stalled response could hold up
  polling or event backfill indefinitely. WebSocket timeout/heartbeat settings
  remain unchanged. Cancelled/retired socket attempts clean up their transport
  and heartbeat job and ignore late connection callbacks.
- Parent state changes and monitor connects/disconnects log the reason,
  device-idle state, battery saver, and battery-optimization exemption, so the
  next long test can distinguish observed restrictions from guessed sleep.

## Investigation and battery assessment

Read-only checks on the paired hardware and home server:

- `hub-host` had 44 days of uptime, no entries for the systemd suspend or
  hibernate services this boot, and a running hub container with zero restarts
  since October 2. There is no app-defined idle/sleep timer in the hub. Docker
  restart policy cannot prevent the host from suspending, but there is no
  observed server suspend to fix here.
- Both phones were charging and in device-idle ACTIVE at inspection time. That
  does not establish their power state during the earlier screenshot.
- The Pixel 6 continuously logged audio levels. `dumpsys power` showed a
  system-held `AudioIn` partial wake lock attributed to the monitor, held for
  about three hours. An additional manual CPU wake lock is not justified by
  this observation. The parent had no app-held persistent CPU wake lock.
- No intentional 20-minute timeout was found. Current transport settings are
  a 15-second application heartbeat, 20-second WebSocket ping, ten-second
  connection timeout, and 1/2/4/8/16/32/60-second reconnect backoff. The hub
  considers a monitor lost after 45 seconds without traffic, checked every
  15 seconds (so notification can occur after roughly 45–60 seconds).

| Option | Battery and reliability implications | Decision |
| --- | --- | --- |
| Existing heartbeats and pings | Each phone sends 240 application heartbeats and up to 180 protocol pings per hour; radio cost depends on Wi-Fi and OS scheduling. | Keep the intervals. |
| Foreground status polling | Up to 240 HTTP polls/hour while visible; zero scheduled polls while hidden after this change. Resume/reconnect adds an immediate request. | Implemented; reduces unnecessary background traffic. |
| Reset backoff after recovery | Faster next-outage recovery; repeated failures still back off to 60 seconds. | Implemented for both roles. |
| Permanent manual wake lock | Keeps CPU awake, increasing battery use/heat; a wake lock alone cannot restore Doze-blocked networking. Monitor already has an audio wake lock. | No new wake lock. |
| Battery-optimization exemption | Can allow network and partial wake locks in Doze, with more overnight energy use. Relevant to a LAN-only alert socket if Doze is confirmed. | Assess with an unplugged test before changing settings. |
| Longer heartbeat/loss timeout | Fewer network operations but slower failure detection; lengthening only the loss timeout masks gaps without restoring alerts. | Keep the 45-second loss threshold. |
| Always-on screen or camera | Adds display/camera power and heat continuously. | Keep the screen free to sleep and video on demand. |

Android documents that Doze suspends networking and ignores ordinary wake
locks; partially exempt apps can use networking and partial wake locks.
A foreground service avoids App Standby classification but does not by itself
establish immunity to all device-idle restrictions. See
[Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
and [keeping a device awake](https://developer.android.com/develop/background-work/background-tasks/awake).

There is no measured battery-drain percentage for these changes. The phones
were plugged in; a charging battery reading cannot establish overnight drain.
The original interruption's root cause remains unconfirmed. The observed
recovery could include network/socket recovery and the old 15-second poll lag;
the code defects above are established independently of that hypothesis.

## Verification and next soak test

Regression coverage exercises recovery/backoff reset over real WebSockets,
foreground retry, HTTP timeout with unlimited read timeout, HTTP cancellation,
failed polls retaining identity without claiming offline, explicit hub offline
reports, reconnect state propagation, in-flight status invalidation, cancellation,
and retry notification wording. All three Android module suites and both debug
APK builds are run together.

Verification completed locally: 342 Android tests passed (shared 121, monitor
102, parent 119; four environment-dependent live tests skipped). Both debug
APKs built successfully. The additional ViewModel transition assertions and
final polling coordination change also passed the parent suite and build.
`git diff --check` and `npm run secrets:scan` passed. APKs are ready in each
module's `build/outputs/apk/debug/`. The later noise-history work brought the
combined passing Android total to 354. Both debug apps were installed in place
on October 3 on the Pixel 8 Pro (parent) and Pixel 6 (monitor), preserving
pairing and event history. The parent logged Connecting → Connected and, during
the hub update, WaitingToRetry(1000 ms) → Reconnecting → Connected. The monitor
resumed capture and connected successfully. Both reported idle=false,
batterySaver=false, exempt=false while charging. This verifies installation
and short recovery, not a full long-running or unplugged soak test.

For the next unplugged 30–60-minute test, capture `KidsMonitorParent` on the
parent and `KidsMonitor` on the monitor along with `dumpsys deviceidle`,
`dumpsys power`, battery saver/exemption settings, and hub last-seen values at
the gap. Lock the parent, then return to Alerts and verify immediate checking
or reconnect feedback, followed by a fresh online/offline result. Repeat with
the monitor locked; a missing report must still raise MONITOR_LOST. Compare
equal-duration battery runs with matched screen/video use before adopting an
exemption or changing heartbeat intervals. Do not label a monitor “sleeping”
without telemetry proving that state.


## Follow-up: cold video startup

The subsequent [cold-start retry work](progress-cold-start-retries.md) adds
three-attempt foreground status recovery, staged hub/monitor/video feedback,
automatic video retries and reconnect recovery, pre-ready camera cancellation,
and a decoded first-picture deadline. Reconnect history now runs independently
of socket control messages with a persisted recovery cursor. These changes were
installed on both paired Pixels and deployed to the home hub on October 3.
The final combined validation passed 373 Android tests and 177 TypeScript
tests, with both debug APKs built. Unplugged battery measurement remains pending.

A subsequent 12-minute charging, locked-phone test showed recent heartbeats
from both paired Pixels, 49 continuous noise summaries (largest gap 4 ms), and
no MONITOR_LOST event. After the parent was unlocked, Watch succeeded on its
first attempt (550 ms to stream ready). With the monitor subsequently locked,
a controlled hub restart recovered live video automatically in about 1.4
seconds. Leaving Watch released the camera while microphone processing
continued. See the detailed hardware observations in the cold-start progress
note; this run does not exercise unplugged deep Doze or establish the original
intermittent failure's root cause.
