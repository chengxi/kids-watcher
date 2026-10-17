# M0–M2 Progress and Handoff

**Plan:** `2026-09-09-m0-m2-protocol-hub-fake-monitor.md` (kept outside this repository)
**Spec:** `2026-09-09-phase-1-lan-monitor-design.md` (kept outside this repository)
**Status:** merged to `main` (2026-09-09). Branch `feat/m0-m2-hub` deleted after merge.

**Next work is M3, the parent Android app.** Anyone picking that up: read the
spec, then the "Deviations" and "Notes" sections below. The one piece of M0-M2
still open is Task 13's container build. Each task in the plan is self-contained
and ends with a green test and a commit.

## Status

| Task | Title | State | Commit |
| --- | --- | --- | --- |
| 1 | Workspace scaffold, CI, secret scanner | **done** | |
| 2 | Protocol — binary media frame codec | **done** | |
| 3 | Protocol — control message schemas | **done** | |
| 4 | Hub — config and database schema | **done** | |
| 5 | Hub — token auth and device registry | **done** | |
| 6 | Hub — first-run TLS and fingerprint | **done** | |
| 7 | Hub — pairing, `POST /pair`, QR CLI | **done** | |
| 8 | Hub — websocket server, registry, heartbeats | **done** | |
| 9 | Hub — event ingest, fan-out, backfill | **done** | |
| 10 | Hub — detection config and recalibration | **done** | |
| 11 | Hub — watchdog and event retention | **done** | |
| 12 | Hub — media relay | **done** | |
| 13 | Hub — Docker, compose, setup guide | **done — built and running on the hub host** | |
| 14 | Fake monitor — pairing, events, calibration | **done** | |
| 15 | Fake monitor — media, CLI, end-to-end test | **done** | |

**M0–M2 is complete.** 109 tests pass, and the container is built and running on
the `hub-host` home server (Ubuntu 24.04, Docker 29.6.2) at port **8444**. The system was also driven by hand against a real hub started
with `node` (see the manual verification section below).

## How to verify where things stand

```bash
npx vitest run && npm run typecheck && npm run secrets:scan
```

## Deviations from the plan

Recorded as they happen, with the reason. A deviation with no reason is a bug.

### Task 1 — the secret scanner flagged its own documentation

The plan's detector matched the literal string `-----BEGIN CERTIFICATE-----`.
That string appears legitimately in the plan document, in this scanner's own
source, and will appear in `docs/guides/setup-hub.md`. Running it against the
repo produced two findings, both false.

**Changed:** PEM patterns now require an actual base64 body — the header plus a
line of 40+ base64 characters — so the scanner flags key material rather than
prose about key material. The API-key patterns were already specific enough and
are unchanged.

**Test changes:** the "flags a PEM private key" fixture now uses a realistic key
body, and a new case asserts a bare header quoted in a markdown guide is *not*
flagged. 5 tests instead of 4.

This makes the detector stricter about what counts as a secret, not looser about
what it scans: every tracked file is still read.

## Notes for whoever picks this up

### Dependency audit

`npm audit` reported 1 critical and 1 high, both in `vitest`/`vite`. Bumped
vitest to `^3.2.4`; all 109 tests pass unchanged on it. Two dev-only moderates
remain. None of these packages appear in the production dependency tree, so none
ship in the hub image.

### Task 13 was finished by deploying to `hub-host`

Built and verified on the real home server (192.168.1.100, Ubuntu 24.04 x86_64,
Docker 29.6.2, Compose v5.3.1). Image is ~440 MB. It runs on **port 8444**, not
8443, because `another-service` already owns 8443 on that box.

The first build failed. Three real defects came out of it, all fixed in
`eb26f34` and written up in [`../lessons-learned.md`](../lessons-learned.md):
an inert `.dockerignore` in the wrong directory, a stale `tsconfig.tsbuildinfo`
that made `tsc` skip building the protocol package, and a hardcoded published
port. Plus a fourth gotcha: Compose ignores a `.env` at the repo root when the
compose file lives in `deploy/`.

Verified against the running container, from a Mac across the LAN:

- `/healthz` returns `{"ok":true}`; `/events` and `/monitors` return `401`.
- The certificate fingerprint seen over the network **matches the container's
  startup log exactly** — the pinning story holds across a real network.
- Both devices paired; **replaying a used code returned 403**.
- Config pushed on connect; a `LOUD_NOISE` alert reached the parent with intact
  metadata; **77 media frames relayed**, JPEG and PCM, decoded on arrival.
- A forged 43-character token was rejected with `401`.
- **Certificate and device database survive `docker compose restart`** — pinned
  clients keep working after a reboot.

To reach it: `https://192.168.1.100:8444`.

**Deployed from a git clone, not a file copy.** A bare repo lives at
`~/kids-monitor.git` on that host; the local repo pushes to it as the remote
`home`. `~/kids-monitor` is a clone of it, with `deploy/.env` (gitignored) holding
`HUB_PUBLISHED_PORT=8444`. Updating is `git push home main`, then `git pull &&
docker compose -f deploy/docker-compose.yml up -d --build` on the server. Full
workflow in [`../guides/deploying.md`](../guides/deploying.md).

Rebuilding from the clone was verified to leave the certificate fingerprint
unchanged, so already-paired devices keep working across an update.

### The compose file uses `ports:`, not `network_mode: host`

macOS Docker has no host networking. `network_mode: host` is left commented in
`deploy/docker-compose.yml` for Linux users, who will want it once mDNS lands.
