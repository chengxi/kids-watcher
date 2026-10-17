# Setting up the hub

The hub is the only component that listens on a port. Both phones dial out to
it. It holds the activity log, authenticates every device, and relays media.

Everything below happens on your own hardware. **No account, no cloud, no
credential is shared with anyone**, including the authors of this project.

## What you need

- An always-on Linux box on your home wifi: a Raspberry Pi 4 or newer, a NAS
  that runs containers, a mini-PC, or an old laptop.
- Docker with the Compose plugin.
- Nothing else. There is no account to create.

## Install

```bash
git clone <your fork or this repository>
cd kids-monitor
cp .env.example deploy/.env
```

**The `.env` must go in `deploy/`, next to the compose file.** Docker Compose
resolves `.env` relative to the compose file's directory, not your shell's. A
`.env` at the repository root is silently ignored, and the first thing you would
notice is the hub coming up on the wrong port.

Edit `deploy/.env` if you want a different published port or shorter retention.

**Change `HUB_PUBLISHED_PORT` if 8443 is already taken on your box.** It is a
popular port; on a machine already running other services the container will
fail to start with `port is already allocated`. Check first:

```bash
ss -ltn | grep ':8443 '
```

```bash
docker compose -f deploy/docker-compose.yml up -d
```

On first start the hub generates its own TLS certificate into the `hub-data`
volume and prints its fingerprint:

```bash
docker compose -f deploy/docker-compose.yml logs hub
```

```
hub listening on port 8443
certificate fingerprint: BB:99:7C:50:...
```

**That certificate is generated on your machine and exists nowhere else.** It is
not in this repository, and it is not in anyone else's install. The `.gitignore`
and the secret scanner in CI both exist to keep it that way.

Deploying to a server you update over time? Use a git clone rather than copying
files — see [`deploying.md`](deploying.md).

### Verified deployment

This has been built and run on Ubuntu 24.04 (x86_64, Docker 29.6.2, Compose
v5.3.1). The image is about 440 MB. Confirmed on that box: the certificate and
the device database persist across `docker compose restart`, so pinned clients
keep working after a reboot.

For host-reboot and power-outage recovery, including checking both paired
phones, see [`recovering-hub.md`](recovering-hub.md).

### Networking note

`deploy/docker-compose.yml` publishes port 8443 with a `ports:` mapping, which
works everywhere including macOS. On Linux you may prefer `network_mode: host`
(commented out in the file) so the hub sees the LAN directly — that will matter
for mDNS discovery when it arrives.

## Check it is alive

```bash
curl -sk https://<hub-ip>:8443/healthz
```

Expect `{"ok":true}`. Everything else returns `401` without a device token:

```bash
curl -sk -o /dev/null -w '%{http_code}\n' https://<hub-ip>:8443/events
```

`-k` is needed because the certificate is self-signed. The apps do **not** skip
verification — they pin the exact fingerprint they were given at pairing, which
is stronger than trusting a public certificate authority.

## Pair a device

Codes are single-use and expire after five minutes. Issue one per device.

For a new monitor, first choose a distinct device name with the user
and compare it with the existing names in the parent or `devices.js list`.
The monitor app starts with **Name this monitor**; accept the prefilled device
model name or edit it (1–64 characters), then tap **Continue to pairing** before
scanning or entering a code. This step also
applies to the ADB helper, which waits for the name before redeeming its payload.
See [installing an additional monitor](installing-monitor.md#choose-a-name).

```bash
docker compose -f deploy/docker-compose.yml exec hub node services/hub/dist/cli/pair.js monitor
docker compose -f deploy/docker-compose.yml exec hub node services/hub/dist/cli/pair.js parent
```

Each prints a QR code plus the same information in text:

```
  host:        192.168.1.234:8443
  code:        803830
  type it as:  803 830
  fingerprint: BB:99:7C:50:4F:86:5C:0D:...
  check:       BB:99:7C  <- the app shows these six characters
```

If the terminal QR is awkward to scan — a small window, or the output has
scrolled — render it as an image instead:

```bash
tools/show-pairing-qr.sh parent
```

That issues a fresh code and opens a 900px PNG. The helper defaults to the
author's SSH host and checkout; override `HUB_SSH` and `HUB_DIR` for your verified
installation. For an iPhone or iPad parent, follow the
[QR walkthrough](pairing-with-qr.md), including private temporary output,
in-app scanning, permissions, expiry and verification.

**There is currently no setup-page URL or QR web endpoint.** A future browser
setup page must authenticate the hub owner before issuing codes; an open LAN
endpoint would let anyone on the Wi-Fi register a device. An authenticated page
is compatible with single-use codes, but owner setup and browser certificate
trust are not implemented today.

For agent-assisted setup, direct QR display is the preferred experience: the
agent prepares the code and opens its image on the user's screen, then verifies
the new device. A browser setup page is not needed for this flow. The current
image helper's launcher is Mac-specific; see the QR walkthrough for platform
limits and Linux/Windows display options.

Scan it with the app. The device exchanges the code for a long-lived token and
pins that fingerprint. Trust stays valid if the certificate is preserved, but
the apps also store the hub's URL: a DHCP address change can break connectivity.
Reserve the hub's LAN address in your router so it stays reachable after reboot.

The role comes from the code, not from the device. A code issued for a monitor
cannot be used to register a parent.

### Pairing without the camera

Tap **Enter a code instead** and type the hub's address, its port, and the six
digits. The app then shows six characters and asks whether the hub shows the
same ones — that is the `check:` line above.

Compare them. The QR carries the fingerprint on its own, but six typed digits
cannot, so this one glance is what distinguishes your hub from anyone else on
the wifi claiming to be it. **Nothing is sent until you answer**, so if the
characters differ, say so: the code stays unused and you can try again at the
right address.

Guessing a six-digit code is held off by the hub itself: five wrong codes in a
minute stop redemption for everyone for a minute, doubling with each repeat up
to fifteen. Over a code's five-minute life that allows roughly 25 guesses
against a million codes — about one in forty thousand.

## List and revoke devices

A lost or replaced phone should be revoked. Its token stops working immediately
and its socket is refused on the next connection attempt.

```bash
docker compose -f deploy/docker-compose.yml exec hub node services/hub/dist/cli/devices.js list
```

```
┌─────────┬────────────────────────────────────────┬───────────┬──────────────────┬────────────────────────────┐
│ (index) │ id                                     │ role      │ name             │ lastSeen                   │
├─────────┼────────────────────────────────────────┼───────────┼──────────────────┼────────────────────────────┤
│ 0       │ '9d6ee388-8426-435e-8b34-337a426ac2d5' │ 'monitor' │ 'Nursery'        │ '2026-09-09T20:21:21.231Z' │
└─────────┴────────────────────────────────────────┴───────────┴──────────────────┴────────────────────────────┘
```

Then revoke by id:

```bash
docker compose -f deploy/docker-compose.yml exec hub \
  node services/hub/dist/cli/devices.js revoke 9d6ee388-8426-435e-8b34-337a426ac2d5
```

Revocation is permanent for that token. Re-pair the device with a fresh code to
bring it back.

## Testing without a phone

`tools/fake-monitor` speaks the monitor half of the protocol. It lets you
exercise the hub, and later the parent app, with no Android device involved.

```bash
docker compose -f deploy/docker-compose.yml exec hub node services/hub/dist/cli/pair.js monitor
```

Take the code from that output:

```bash
npx tsc -b tools/fake-monitor
```

```bash
node tools/fake-monitor/dist/index.js --host <hub-ip> --code <CODE>
```

It prints `paired.` with a token to reuse (`--token <TOKEN>` on later runs, no
new code needed), then `connected.`. Press `n` to raise a loud-noise event, `m`
for motion, `s` for monitor-started, `q` to quit.

To confirm an event landed in the log, pair a parent the same way and read it
back with that token:

```bash
curl -sk "https://<hub-ip>:8443/events?since=0" -H "Authorization: Bearer <PARENT TOKEN>"
```

## Running the hub without Docker

Useful for development, and for verifying things when Docker is unavailable.

```bash
npm install && npx tsc -b services/hub
```

```bash
HUB_DATA_DIR=./hub-data HUB_PORT=8443 node services/hub/dist/index.js
```

Issue pairing codes the same way, without the `docker compose exec` prefix:

```bash
HUB_DATA_DIR=./hub-data node services/hub/dist/cli/pair.js monitor
```

## Where the data lives

Everything is in the `hub-data` Docker volume: `hub.db` (devices, events,
per-monitor settings), `hub.cert.pem`, and `hub.key.pem`.

**No audio or video is ever recorded.** Events carry metadata only — the time,
the type, and the peak level. Live feeds are relayed and not stored.

Events are kept 30 days by default. Change `HUB_EVENT_RETENTION_DAYS` to adjust.
