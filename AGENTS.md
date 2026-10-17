# Working in KidsMonitor

Read `ARCHITECTURE.md` for current behavior and `docs/README.md` for the
documentation map. Operational guides live in `docs/guides/`; historical specs
and milestone plans are not deployment runbooks.

## Backend outage or host reboot

Use [`docs/guides/recovering-hub.md`](docs/guides/recovering-hub.md). It covers
inspection, recovery without a software update, boot configuration, and proof
that the actual paired apps have resumed traffic.

- Establish the deployment before acting. The SSH host, checkout path,
  container name and LAN URL differ for every installation. Read them from this
  install's own deployment notes and confirm them against the host; never carry
  values over from another installation or an earlier session.
- Inspect the deployed revision, working tree, container, persistent volume,
  and certificate fingerprint before changing anything. A reboot recovery uses
  the existing image and configuration; do not pull, switch branches, rebuild,
  or deploy local app edits as part of recovery unless requested.
- Preserve `kids-monitor_hub-data`, `deploy/.env`, the certificate/key, and phone
  credentials. Never use `docker compose down -v`, prune volumes, regenerate
  TLS material, revoke devices, or clear/re-pair apps to fix an ordinary outage.
- `/healthz` alone does not establish app recovery. Require advancing last-seen
  timestamps for the expected active monitor and parent across two checks, plus
  fresh monitor noise samples. Check app services/logs when ADB is available.
- A backend reboot normally recovers automatically. A monitor **phone** reboot
  requires unlocking the phone, opening the monitor, and tapping Start; do not
  add a boot receiver for microphone/camera capture.
- Report any remaining gap explicitly, including an unreachable phone or an
  untested live feed. Keep tokens, private keys, pairing payloads, and database
  contents out of logs and committed documentation.

## Adding a monitor

Use [`docs/guides/installing-monitor.md`](docs/guides/installing-monitor.md).
Before pairing a new monitor, ask the user what to name it, unless they have
already supplied a name. The naming screen prefills the device model; the user
can accept it with **Continue to pairing** or edit it. Offer device names such
as Pixel 6 or iPhone 13 Pro (monitors can move rooms), and check existing names
so two phones of the same model are easy to distinguish. Never submit a default
without confirmation or use an ADB serial as its name.
The app's **Name this monitor** step also applies to `tools/pair-device.sh`:
the payload waits on the phone until the name is confirmed. Verify the chosen
name in the parent's device list after starting monitoring. Preserve existing
pairings; naming a new monitor does not require clearing or re-pairing old ones.

## Changes and verification

Preserve existing uncommitted work. Add reusable operational instructions to
`docs/guides/` and link them from `docs/README.md`. For documentation-only work,
verify the commands against the deployed service, check links and
`git diff --check`, and run `npm run secrets:scan`; app rebuilds are unnecessary.
For source changes, run checks appropriate to the affected components using
the commands and platform constraints documented in the repo.
