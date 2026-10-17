# Automatic cold-start recovery and staged live feedback

Date: 2026-10-03

The parent previously made one live-stream request and ended after ten seconds.
It also awaited reconnect history HTTP inside the sole socket event consumer:
a stalled history request could delay `stream_ready`, even after the hub had
sent it. Socket reconnection already retried, but live-view intent did not
survive that reconnection and status HTTP waited for the next periodic poll.
These are established code defects; they do not prove the cause of every
reported interruption after ten minutes away.

## Implemented behavior

- Main-screen status requests retry transient network and server failures up
  to three times with 1/2-second waits, within foreground polling. Each HTTP
  attempt retains the ten-second whole-call cap. Feedback includes failure,
  upcoming attempt, and retry wait. Cached identity/history remain available;
  a failed hub request means unknown status, not an offline monitor.
- Live-view intent waits for a connected hub before spending a video attempt.
  A lost hub connection returns to automatic network recovery and requests the
  video again on recovery, while the screen remains active.
- The hub sends `stream_waiting` with `contacting_monitor` immediately after
  accepting a request. The monitor sends `stream_preparing` before its
  serialized camera operation; only then does the hub report `preparing_video`.
  The parent distinguishes hub/network delay, monitor response delay, and
  confirmed feed preparation. It never infers that the child is sleeping.
- Startup makes at most three attempts. Each stage has a 15-second deadline;
  only advancing to a new acknowledged stage extends that deadline. Repeated
  progress cannot hide a stalled startup. Failed attempts wait 2/4 seconds;
  hub-reported offline monitors wait 5/10 seconds to allow recovery.
- A format announcement starts a separate 12-second deadline for a successfully
  decoded first picture. Receiving invalid JPEG bytes or audio alone cannot
  cancel that deadline. A stale decode cannot paint a picture after the
  session disconnects or changes stream. The screen shows a spinner and the
  observed stage, followed by cause and automatic retry information.
- If the hub fails to acknowledge a request on a supposedly connected socket,
  that socket is cancelled and reconnected rather than repeatedly sending to
  a stale transport. Alert delivery resumes via the normal reconnect path.
- Each attempt has a request id, echoed in waiting/ready/offline responses.
  Replies from an older attempt cannot replace the current one. A new
  `cancel_stream_request` releases this parent even before stream ready, and
  a delayed cancellation cannot cancel a newer attempt. Other viewers retain
  their stream. Old apps remain compatible with the updated hub.
- Leaving live view or locking the parent cancels retries and viewing intent.
  It releases the pending or playing viewer so the monitor camera can stop.
  Camera failures automatically retry; after three attempts, available audio
  remains in an explicitly labeled sound-only view with a Retry video button.
- Reconnect history runs separately from socket control processing. Its
  captured cursor is persisted per paired device and advances only after all
  history pages have loaded. New live events cannot skip the missing gap,
  including across a process restart during failed recovery. Existing event
  acknowledgement state is retained.
- Parent logs include live phases, attempts, stream ids, and terminal causes,
  alongside the previously added hub/power diagnostics.

No wake lock, battery-optimization exemption, background camera, new capture
loop, or faster heartbeat was added. Short status and camera retries add
bounded work after a foreground failure, comparable to manual retries. The
microphone remains on the existing capture path; the camera remains on demand.
Charging-device tests cannot measure unplugged battery drain.

## Verification

- 177 TypeScript tests passed and type checking passed. Real WebSocket hub
  tests verify stage acknowledgements, per-viewer correlation, pre-ready
  cancellation, late replies/cancellation, and preserving another viewer.
- 373 Android tests passed: shared 124, monitor 105, parent 144. Four additional
  environment-dependent live tests were skipped. Both debug APKs built.
- All 32 cross-language encodings, binary frames, and constants validated.
  Additional Kotlin decoding tests cover correlated hub progress/ready/error
  and legacy replies without request ids.
- Regression cases include a third-attempt status recovery, cold viewing
  intent awaiting the socket, slow acknowledged startup, repeated progress
  bounded by timeout, unresponsive monitor versus delayed video, stale
  responses, missing/invalid first pictures, camera fallback, cancellation
  while leaving, reconnecting a stale real socket, and complete paginated
  history recovery despite newer live arrivals.
- `git diff --check` and the secret scan passed.

## Deployment

Hub revision `81e1d33` is running on `hub-host`; `/healthz` succeeds and its
pinned certificate is unchanged. The previous hub image is retained as
`kids-monitor-hub:before-live-retry-20261003`. Both debug APKs were installed
in place on the Pixel 8 Pro (parent) and Pixel 6 (monitor), preserving pairing
and history. The parent logged automatic reconnection after the hub restart.
The Pixel 6 resumed 16 kHz listening and connected to the hub after Start
monitoring was tapped.

The real Alerts chart, preserved history, cold Watch startup, automatic live
recovery, and camera release were subsequently verified on the paired phones
as recorded below. The original intermittent failure was not reproduced in
this run; its root cause and unplugged battery impact remain unconfirmed.

## Charging, locked-phone soak

A follow-up on October 3 checked the installed APK hashes against the tested
build artifacts; both matched. Both phones were charging. The parent was on
its lock screen; the monitor screen was explicitly put to sleep while its
foreground microphone service continued. The monitor had no active camera
client. Android device-idle state remained ACTIVE while charging, so this
does not test unplugged deep Doze or measure battery drain.

From 17:52:28 to 18:04:43 PDT (12 minutes 15 seconds), hub snapshots continued
to show recent reports from both phones. The hub received 49 noise summaries;
the largest internal interval gap was 4 ms, below the existing 250 ms capture
jitter tolerance. No MONITOR_LOST event occurred in the interval. At its end,
the monitor's last report was 10.9 seconds old and the parent's was 11.6 seconds
old, within the existing 15-second heartbeat cadence. There was no observed
long connection interruption during this charging, screen-off test.

The Pixel 8 Pro remained locked after the interval until the user unlocked it
for the following Watch checks. The Pixel 6 continued listening while locked.
This result verifies continuous background reporting in this run, not
elimination of the original intermittent cold-attempt failure.

## Watch startup, locked monitor, and automatic recovery

On October 3, after the parent was unlocked, Alerts showed Pixel 6 online with
100% monitor battery. The real 24-hour chart displayed recent measurements,
grey pre-collection history, and historical alert markers above the preserved
event list. Watch succeeded on its first attempt: the request at 18:23:34.581
PDT advanced through hub, monitor, and video preparation to stream ready at
18:23:35.131 (550 ms). A decoded live picture was verified on screen. This
timing measures stream ready, not the time of the first decoded picture. The
monitor was awake at that first startup.

The Pixel 6 was then explicitly put to sleep. It entered Dozing while video
continued. A controlled hub restart during Watch produced WaitingForHub /
WaitingToRetry, then Reconnecting and the acknowledged startup stages. The
parent returned from failure at 18:24:53.794 to stream ready at 18:24:55.146
(1.352 seconds) without any manual retry. A decoded live picture was verified
after recovery, and the monitor remained Dozing: this also verifies starting
the camera again with the monitor locked. Hub health remained healthy.

Leaving Watch stopped the camera at 18:26:03.777; a subsequent camera-service
check showed no active camera clients. Microphone level processing continued.
The parent was left on Alerts with fresh online status and the chart visible.
Its original settings remained unchanged (USB stay-awake disabled, screen
timeout 60 seconds). Both phones remained plugged in. No additional source
change was needed after these hardware checks.
