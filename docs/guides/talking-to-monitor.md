# Talking to the monitor

Install compatible hub, parent, and monitor versions. Existing pairing and
history are preserved; no re-pairing is necessary. Build both Android APKs using
`./gradlew :parent:assembleDebug :monitor:assembleDebug` in `apps/android/`.
Update the hub using [deploying.md](deploying.md). After installing a monitor
update, open the monitor and tap Start while its activity is visible.

## Use

1. Open the parent and tap **Watch live** for the monitor.
2. Tap **Talk to monitor** and allow microphone access when asked.
3. Wait for **Microphone on · monitor speaker on**, then speak normally.
4. Tap **Stop talking** to hear the room again. Leaving or locking the live
   view also stops the microphone and speaker.

Both monitors use their built-in speaker. Android temporarily raises media
volume to at least 80% and restores it afterward unless someone adjusted it.
Other media playing on an Android monitor shares that media-volume setting.

The iOS monitor uses normal audio-session processing while playing parent voice,
then restores measurement mode for room monitoring when speech ends. Measurement
mode reduces speaker output level, so it must not stay active for talkback.
iOS system volume is set with the phone's buttons; the app reports it but does
not change it. Keep the iPhone monitor app visible for talkback and video. See
[iOS setup and platform differences](ios-monitor.md#ios-differences).

The speaking parent's room audio pauses while talking to reduce feedback;
video and noise detection continue. The monitor may detect speech played by
its own speaker as room noise. No voice audio is saved. Only one parent can
speak to a monitor at a time; other parents may continue watching.

## Recovery and verification

If the button reports a timeout, check that the hub and both apps were updated,
that the monitor is running, and that both devices are connected. A failed
request restores room audio and never leaves the parent microphone recording.
Voice does not resume automatically after reconnecting: tap the button again.

For an on-device check, watch the monitor, enable Talk, and confirm audible
speech from its speaker. Also confirm Stop talking, Home, and screen locking
stop capture, restore room audio as applicable, and restore the prior monitor
media volume. Use the actual paired phones rather than a health endpoint alone.

With ADB, `logcat -s KidsMonitorTalk KidsMonitor` on the monitor reports speaker
activation and the actual AudioTrack route (`route=2` is the built-in speaker)
when PCM is written. `dumpsys audio` and `dumpsys media.audio_flinger` establish
recording/playback activity, route, and media volume without logging audio or
authentication tokens. Hardware route and frame checks establish the transport;
listening establishes audibility and perceived volume.

On an iOS debug build, the attached device console reports `KidsMonitorTalk`
metadata once a second: received/rendered buffers, queue depth, dropped buffers,
output route and system volume. No PCM or pairing material is logged. A ready
speaker message alone does not prove rendering or audibility. Rendering callbacks
free queued-audio capacity; speaker-presentation latency must not fill the queue
and discard fresh voice. Hardware tests in `AudioHardwareTests` exercise the
speaker with both lenses and verify microphone windows continue after returning
to measurement mode. They play a short synthetic tone, not stored speech.

Automated coverage exercises viewer authorization, exclusivity, readiness,
binary-frame validation, cancellation and stale replies, disconnection cleanup,
permission/capture failure handling, and the monitor's three-second voice-frame
watchdog. Run hub tests from the repository root and Android tests using the
commands in the build guides. These tests do not establish physical loudness.

## Current home rollout (October 8, 2026)

Hub revision `7a3591c` on `codex/parent-voice-backend` is deployed on
`hub-host`; the preceding deployed revision was `81e1d33`. The running
container retains `kids-monitor_hub-data`, the original TLS fingerprint, and
a healthy SQLite database. The voice APKs were installed as updates on the
paired Pixel 8 Pro parent and Pixel 6 monitor. Both phones reconnected and the monitor resumed capture after it was unlocked
and started.

Hub tests passed (183 tests), Android tests passed (with four existing skipped
shared tests), and all 36 Kotlin wire encodings passed protocol conformance.
Diff whitespace and secret scanning passed. The non-TLS probe test fixture now
sends an immediate plain response instead of waiting for an HTTP request from
a TLS client, eliminating its intermittent read timeout.

At 19:59 PDT, the paired phones completed two voice sessions. Pixel 6 logs
confirmed PCM writes of 640 bytes with actual built-in-speaker route `2`.
Android audio diagnostics confirmed media volume rose from 18/25 to 20/25 and
returned to 18/25 after each session. Pixel 8 Pro recording diagnostics showed
unsilenced `VOICE_COMMUNICATION` capture and subsequent recording stops;
room-audio players were recreated after speech. Locking the parent stopped
speech and the camera while monitor audio capture continued. Another test at
maximum media volume preserved that setting. The operator confirmed that voice
was clear and loud enough from the Pixel 6 speaker.

At 20:00 PDT, hub last-seen ages were 0 seconds for the monitor and 5 seconds
for the parent, and noise samples were fresh. Follow-up snapshots established
continuing traffic. The original certificate fingerprint and pairing persisted.
Bluetooth/headset route changes, a second physical speaking parent, and sustained
speech during network interruption were not exercised on hardware; automated
tests cover exclusivity and connection cleanup.
