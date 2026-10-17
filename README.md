# Kids Monitor

Turn the old phones in your drawer into a baby monitor or a room camera.

An old **Android phone or iPhone** becomes the monitoring device: it listens in
the room, raises an alert when it gets loud, and streams live video and audio to
your everyday phone on demand. You can talk back through it, so you can answer a
child from another room.

**Everything stays on your home network.** There is no account to create, no
cloud service, and no telemetry. Audio, video and alerts travel from the phone
to a small server you run yourself and back to your own phone — nothing is sent
to the authors of this project or to anyone else.

## What it does

- **Noise monitoring.** The monitor measures the room continuously and raises
  an alert when sound stays above its threshold. It adapts to the room's own
  background level, and keeps 24 hours of history you can scroll through. -
  **Live video and audio, on demand.** Pull up the feed when you want it. The
  camera runs only while you are watching. The microphone is always listening,
  because that is how noise is detected, but audio is only streamed to you
  while the live view is open. - **Talk back.** Send your voice to the
  monitoring phone's speaker. - **No recording.** The monitor measures sound in
  a reused buffer and discards it; nothing is written to disk and alerts carry
  numbers — how loud, how long — not sound. The one thing that is saved is a
  snapshot you take yourself during live view, which goes to your own phone's
  gallery.

## What you need

- **An always-on Linux box on your home wifi** to run the hub: a Raspberry Pi 4
  or newer, a container-capable NAS, a mini-PC, or an old laptop. It needs
  Docker with the Compose plugin, and nothing else. This is the one piece you
  cannot skip — the phones talk to each other through it, and it holds the
  alert log.
- **A phone to leave in the room.** Android, or an iPhone.
- **Your everyday phone** for the parent app. Android for now.

Old hardware is the point: the monitor runs on phones several Android versions
behind current.

## Getting started

**The easiest route is to let a coding agent do it.** Clone the repository, open
a coding agent in the project directory, and ask it to set the system up
end to end — the hub on your server, the monitor app on the phone you are
retiring, and the parent app on your everyday phone. `AGENTS.md` tells the agent
how this project expects to be worked on, and the guides below are written to be
followed by a person or an agent equally well.

Prefer to do it by hand, or want to know what the agent will do?

| Guide | What it covers |
| --- | --- |
| [`docs/guides/setup-hub.md`](docs/guides/setup-hub.md) | Standing the hub up, its TLS certificate, and issuing pairing codes |
| [`docs/guides/installing-monitor.md`](docs/guides/installing-monitor.md) | Naming, installing and pairing a monitoring phone |
| [`docs/guides/deploying.md`](docs/guides/deploying.md) | Running the hub as a real deployment |
| [`docs/guides/reading-noise-chart.md`](docs/guides/reading-noise-chart.md) | Reading the noise history, and what the numbers mean |
| [`docs/guides/recovering-hub.md`](docs/guides/recovering-hub.md) | Getting back up after a power cut or reboot |

[`ARCHITECTURE.md`](ARCHITECTURE.md) describes how the pieces fit together, and
[`docs/README.md`](docs/README.md) maps the rest of the documentation.

## How the privacy claim is kept

- The hub listens on your LAN only. Both phones dial out to it; neither accepts
  inbound connections.
- Every device authenticates with a token it receives once, during pairing. The
  hub generates its own TLS certificate on first start, and the apps pin its
  fingerprint, so a device will not talk to a hub it has not been paired with.
- The apps use no analytics, crash reporting or push service. Notifications are
  raised locally by the parent app, not delivered through a vendor's servers.
- Nothing is published outside your network unless you deliberately expose the
  hub's port to the internet, which this project does not ask you to do.

## Status

Phase 1: LAN-only. The Android monitor and parent apps are verified on real
hardware, including phones as old as a Pixel 3 on Android 12. The iPhone monitor
app builds, installs, pairs, and streams to the Android parent; extended
acceptance testing on it is still open. There is no iPhone parent app yet.

[`docs/planning/roadmap.md`](docs/planning/roadmap.md) tracks what is done and
what is next.

## Licence

MIT. See [`LICENSE`](LICENSE).
