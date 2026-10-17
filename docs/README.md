# Documentation

Start with [`ARCHITECTURE.md`](../ARCHITECTURE.md) in the repository root — it
describes the system as it stands today.

## Contents

| Path | What it holds |
| --- | --- |
| [`planning/roadmap.md`](planning/roadmap.md) | Every milestone, its status, and what is planned next. **Start here.** |
| [`planning/onboarding-brief.md`](planning/onboarding-brief.md) | How a stranger goes from `git clone` to a working monitor: every phase, what exists today, and what is still a gap. |
| [`planning/progress-*.md`](planning/) | One per milestone: what was built, every deviation and why, every negative control, and what hardware did and did not verify. |
| [`guides/`](guides/) | Setup, build, pairing, and deployment for people running their own instance. |
| [`guides/ios-parent.md`](guides/ios-parent.md) | Build, pair and test the separate iOS parent; dashboard/live controls, saved history and foreground-only alert delivery. |
| [`guides/pairing-with-qr.md`](guides/pairing-with-qr.md) | Agent-assisted direct QR display: verified Mac-to-iPhone/iPad pairing, Linux/Windows portability limits, permissions, expiry and verification. |
| [`guides/device-connections.md`](guides/device-connections.md) | USB versus Wi-Fi for app installation, developer pairing and hub QR pairing; agent preflight and verified iPad network installation. |
| [`guides/ios-monitor.md`](guides/ios-monitor.md) | Build, pair and verify the iOS monitoring device with the Android parent; platform differences and local tests. |
| [`guides/installing-monitor.md`](guides/installing-monitor.md) | Choose a distinct name, install and pair an additional Android monitor, then verify it from the existing parent. |
| [`guides/recovering-hub.md`](guides/recovering-hub.md) | Power-outage and backend-reboot recovery, preserved pairing, boot checks, and verification of actual app traffic. |
| [`guides/live-view-controls.md`](guides/live-view-controls.md) | Live-view Back, front/back camera switching, charging indicator, monitor information, and gallery snapshots. |
| [`guides/talking-to-monitor.md`](guides/talking-to-monitor.md) | Parent voice button, monitor speaker volume, lifecycle, and hardware verification. |
| [`guides/events.md`](guides/events.md) | Parent Events, filters, history loading, and offline/back-online behavior. |
| [`guides/reading-noise-chart.md`](guides/reading-noise-chart.md) | Moving average, automatic axis, latest adaptive alert reference, and everyday sound examples. |
| [`guides/comparing-monitor-noise.md`](guides/comparing-monitor-noise.md) | Why two monitors in one room disagree: co-location checks, valid cross-monitor statistics, capture-path inspection, clap and step tests, and the adaptive-floor dead band. |
| [`lessons-learned.md`](lessons-learned.md) | Failures and discoveries from development and testing. |

There are no ADRs. Decisions live in the specs, which are dated and never
rewritten, and the reasoning behind the painful ones is in
[`lessons-learned.md`](lessons-learned.md).

## Building the Android apps? Read this first

Five Android platform constraints shape both apps, and **three of them fail
silently** — no exception, no log, no error message:

| Trap | Symptom if you get it wrong |
| --- | --- |
| `ACCESS_LOCAL_NETWORK` (Android 17, SDK 37+) | Every hub connection **times out**, exactly like the server being down. Any screen that talks to the hub must request it — including one that deliberately asks for no other permission |
| Foreground service type | `dataSync` stops after 6h/24h; `connectedDevice` crashes on Android 17 |
| `setBypassDnd` without policy access | Silently ignored — the monitor-offline alarm is muted |
| No `BOOT_COMPLETED` for camera/mic services | Throws on every reboot |
| Capture needs a visible activity to start | `startForeground` refused from the background |

Summarised in [`../ARCHITECTURE.md`](../ARCHITECTURE.md); diagnosed in full, with
the symptoms that misled us, in [`lessons-learned.md`](lessons-learned.md).

## Which file do I write in?

- **A decision about how the system works** → `ARCHITECTURE.md`; if it needed
  arguing, write a dated spec alongside the other design specs kept outside
  this repository, and name it here.
- **A milestone's status** → the table in `planning/roadmap.md`, and the
  milestone's own `planning/progress-*.md`.
- **A plan for work not yet done** → `planning/`.
- **Something that surprised us** → `lessons-learned.md`.
- **Something a stranger needs in order to run this** → `guides/`.

The dated design specs and the per-milestone implementation plans are kept
outside this repository. Documents here refer to them by name where the
reasoning matters.

Specs are historical records. When a design changes, write a new spec that
supersedes the old one; do not rewrite history.
