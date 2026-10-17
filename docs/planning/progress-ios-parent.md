# iOS parent implementation — October 12, 2026

The separate KidsParent target builds for iOS 16+ and is installed on the connected
iPhone 13 Pro. Android's current parent is the UI/feature reference. The existing
hub deployment is unchanged; the iPhone paired as **iPhone 13 Pro Parent** using
its own Keychain namespace, preserving all existing credentials.

## Implemented

- QR/typed parent-role pairing, certificate confirmation and pinned HTTP/WSS.
- Light dashboard, device selection, availability, battery/charging and Status.
  Poll failures mark status unknown. Status reflects offline/low-battery devices.
- SQLite Events and noise history, SQL filters before paging, a bounded 100-row
  event window advancing 50 rows with Older/Newer controls, and an independent
  recovery cursor. Live events cannot skip an older unfilled history gap.
- 1/3/12/24-hour noise chart: raw peaks, cosine-weighted duration average,
  shape-preserving interpolation, exact coverage clipping, automatic axis,
  latest alert threshold, noise-alert markers and historical tap inspection.
- One socket for alerts/controls/media, bounded JPEG decode and PCM playback,
  correlated stream retries with phase/first-picture deadlines and freshness
  feedback, dark portrait/landscape live view, pinch zoom/reset, mute,
  camera switching, explicit Photos snapshots and microphone talkback.
- Viewer/voice cleanup on leaving, background/lock, disconnection, interruption
  and stream termination; viewing and voice do not auto-resume after returning.

## Verification so far

- Swift core: 15 tests pass, including parent role, interval union, missing
  scheduling gaps, overlap clipping, long-gap separation and duration averaging.
- Parent simulator: SQLite persistence/cursor/filter/paging and actual hub
  TLS/WSS Events/noise/media/camera/voice relay tests pass. The loopback fixture
  has disposable data; synthetic microphone PCM avoids relying on simulator
  hardware audio. It does not use real phone tokens.
- Monitor regression: eight simulator pairing/TLS/socket/voice/dashboard tests
  pass, including a 20-second WebSocket heartbeat lifetime check.
- Physical iPhone 13 Pro XCTest against the deployed hub: parent pairing,
  actual Pixel 6 noise data, decoded live JPEGs, room PCM rendered by AVAudioEngine,
  front/back switching/restoration, mute/unmute, physical microphone PCM
  sending after speaker readiness, explicit add-only Photos saving, background cleanup and
  foreground reconnect pass. Device-rendered portrait/landscape layouts were
  inspected from XCTest attachments. Private room images/history screenshots
  remain local test artifacts and are not committed.
- The final normal app launch remains connected: two read-only snapshots 41
  seconds apart confirm advancing iOS parent last-seen and continuing Pixel 6 /
  Pixel 3 last-seen and fresh numeric noise samples. The original hub certificate
  and data volume are unchanged. The iPhone monitor app remains installed with
  its original pairing; it is not currently capturing while this phone is used
  as the parent.
- Secret scan, documentation links and `git diff --check` pass.

## Platform limits and remaining acceptance

Live alert delivery requires the parent app to remain foregrounded. It does not
claim reliable LAN notifications while locked/backgrounded; iOS suspends ordinary
sockets and this installation has no APNs relay. Reopening drains missed history.
The phone cannot serve as an unattended monitor and actively play/capture parent
audio simultaneously; the existing iOS monitor pairing is preserved, but using
this phone as the parent can interrupt its monitor capture. Android monitors and
parent pairing remain independent.

Physical acoustics, notification permission behavior, real four-way
rotation, disconnect/reconnect Wi-Fi, and prolonged reliability still need their
specific acceptance checks. The UI test runner failed before any test
with “Timed out while enabling automation mode,” including a retry after the
operator enabled the Developer UI Automation setting. Button-level UI automation
remains unverified; the independent on-device media/control XCTest passes. Source and operator instructions
are in [the iOS parent guide](../guides/ios-parent.md).
