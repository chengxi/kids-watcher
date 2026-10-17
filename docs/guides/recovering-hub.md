# Recovering the hub after a reboot or power outage

Use this guide to restore the existing backend and verify the paired phones.
For a software update, use [deploying.md](deploying.md). Commands below assume
Bash or Zsh on the operator's machine and SSH access to the Linux hub.

## 1. Inspect before changing anything

Set these for your installation. The defaults are the current home deployment:

```bash
HUB_SSH=user@hub-host
HUB_URL=https://192.168.1.100:8444
ssh -o BatchMode=yes -o ConnectTimeout=10 "$HUB_SSH" '
  hostname
  uptime
  systemctl is-active docker
  systemctl is-enabled docker
  cd ~/kids-monitor || exit 1
  git rev-parse --short HEAD
  git status --porcelain
  docker compose -f deploy/docker-compose.yml ps -a
  docker inspect kids-monitor-hub --format "status={{.State.Status}} started={{.State.StartedAt}} policy={{.HostConfig.RestartPolicy.Name}} image={{.Image}} mounts={{json .Mounts}}"
  docker logs --since 10m --tail 50 kids-monitor-hub
'
```

Record the revision, image ID, start time, certificate fingerprint, and data
mount. The container must mount the existing `kids-monitor_hub-data` volume at
`/data`. Local edits or a revision different from your laptop are not a reason
to deploy during recovery. `deploy/.env` holds this installation's port and
address; do not overwrite it or print secrets.

Docker should be **active** and **enabled**, and the hub restart policy should
be **unless-stopped** (as specified in `deploy/docker-compose.yml`). That policy
restarts the container after a host reboot, except when it was explicitly
stopped beforehand. Docker's restart count alone does not measure host reboots;
use host uptime and the container start time.

If SSH fails, first confirm host power, router/Wi-Fi recovery, and the host's
address using the router or local console. Do not recreate the backend on a
different machine to work around an unreachable host.

## 2. Restore the existing service

If Docker is inactive, start it on the hub. If it is disabled, enable startup:

```bash
ssh "$HUB_SSH" 'sudo systemctl start docker'
ssh "$HUB_SSH" 'sudo systemctl enable docker'
```

These may require the operator's sudo authentication (use an interactive SSH
session if needed). Do not restart a healthy Docker daemon: the host runs other
services too.

If the hub container exists but is stopped:

```bash
ssh "$HUB_SSH" 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml start hub'
```

If it is already running and health and app checks below pass, automatic boot
recovery succeeded. If a restart is requested or the running process needs
recovery, restart only the hub:

```bash
ssh "$HUB_SSH" 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml restart hub'
```

If the container is missing, first verify the existing data volume with
`docker volume inspect kids-monitor_hub-data`, the existing image with
`docker image inspect kids-monitor-hub:latest`, and the installation's
`deploy/.env`. Only with those present, recreate using the existing image:

```bash
ssh "$HUB_SSH" 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml up -d --no-build --pull never hub'
```

If the data volume is missing, investigate backups and the original mount
before starting a new instance. A new empty volume generates a new certificate
and device database, breaking existing pairing.

Never use `down -v`, volume pruning, TLS regeneration, device revocation, or app
data clearing for routine recovery. Keep the deployed image/revision unchanged;
`git pull` and `up --build` belong to an intentional update.

## 3. Check backend health and preserved trust

```bash
curl -kfsS --connect-timeout 5 --max-time 10 "$HUB_URL/healthz"
curl -ksS --connect-timeout 5 --max-time 10 -o /dev/null -w '%{http_code}\n' "$HUB_URL/events"
ssh "$HUB_SSH" 'docker logs --since 5m --tail 30 kids-monitor-hub'
```

Expect `{"ok":true}`, HTTP **401** without a token on `/events`, and the same
certificate fingerprint recorded before recovery. Allow a few seconds for
startup before retrying a failed health probe. `-k` is only a diagnostic for the
self-signed certificate; apps continue to require their pinned fingerprint.

If health works on the hub but not from the operator's LAN, inspect the actual
published port, host address, firewall, and LAN routing. Phones must reach that
same address and port. Their stored URL does not follow DHCP address changes;
restore the expected address/reservation where possible. Changing
`HUB_PUBLIC_HOST` changes new pairing output, not existing phones' stored URLs.
Do not clear pairing to diagnose a routing or fingerprint failure.

## 4. Prove the paired apps resumed traffic

Wait up to 90 seconds for normal reconnect backoff and connection attempts.
Run this read-only snapshot twice, at least 20 seconds apart:

```bash
ssh "$HUB_SSH" 'cd ~/kids-monitor && docker compose -f deploy/docker-compose.yml exec -T hub node --input-type=module' <<'NODE'
import Database from 'better-sqlite3';
import { readFileSync } from 'node:fs';
import { X509Certificate } from 'node:crypto';
const db = new Database('/data/hub.db', { readonly: true, fileMustExist: true });
const now = Date.now();
console.log('server UTC:', new Date(now).toISOString());
console.log('database:', db.pragma('quick_check', { simple: true }));
console.log('fingerprint:', new X509Certificate(readFileSync('/data/hub.cert.pem')).fingerprint256);
console.table(db.prepare('SELECT id, role, name, last_seen_ms FROM devices WHERE revoked = 0 ORDER BY role, name').all().map(d => ({
  id: d.id, role: d.role, name: d.name,
  lastSeen: d.last_seen_ms === null ? 'never' : new Date(d.last_seen_ms).toISOString(),
  ageSeconds: d.last_seen_ms === null ? null : Math.round((now - d.last_seen_ms) / 1000),
})));
console.table(db.prepare('SELECT d.name, MAX(n.end_ms) AS latest, COUNT(*) AS recentSamples FROM devices d LEFT JOIN noise_samples n ON n.monitor_id = d.id AND n.end_ms >= ? WHERE d.role = ? AND d.revoked = 0 GROUP BY d.id').all(now - 120000, 'monitor').map(r => ({
  name: r.name,
  latestSample: r.latest === null ? 'none in last two minutes' : new Date(r.latest).toISOString(),
  recentSamples: r.recentSamples,
})));
db.close();
NODE
```

Require `database: ok`, unchanged fingerprint, and advancing `lastSeen` for
each expected active monitor and parent. Both roles send 15-second heartbeats;
healthy timestamps should generally be less than 30 seconds old. Advancing
noise sample times additionally prove monitor audio processing is reaching the
backend. Sample times use the phone's clock, so small skew is possible. A paired
device in the table with an old timestamp is not proof of a current connection.
No bearer tokens or private keys are read by this diagnostic.

With ADB available, discover the current serials with `adb devices -l` and check:

```bash
adb -s <monitor-serial> logcat -d -v time -s KidsMonitor
adb -s <parent-serial> logcat -d -v time -s KidsMonitorParent
adb -s <monitor-serial> shell dumpsys activity services com.kidsmonitor.monitor
adb -s <parent-serial> shell dumpsys activity services com.kidsmonitor.parent
```

Look for recent `hub=connected` / `hub=Connected`, continuing monitor levels,
and active foreground `MonitorService` / `AlertService`. Log buffers can rotate;
use advancing server timestamps even when the reconnect log is absent. If ADB
is not on PATH, use `sdk.dir` in `apps/android/local.properties` to locate
`platform-tools/adb`; this machine-specific file is gitignored.

If a phone remains disconnected, confirm its home Wi-Fi, the stored hub address,
runtime LAN permission, and connection error. Open the parent and use Refresh
to retry. If the **monitor phone** rebooted or capture stopped, unlock it, open
the monitor, and tap Start. Backend-only restarts do not normally require this.
Android requires a visible activity to start microphone/camera capture after
a phone reboot; a boot receiver is not a recovery solution.

For a user-facing check, open the parent, confirm a fresh online monitor status,
and optionally use Watch to check live picture/audio, then leave Watch to release
the camera. Heartbeats and noise samples establish connectivity and monitoring;
they do not establish that live video playback was tested. State that distinction
in the recovery report.

## Verified recovery: October 7, 2026

After a power outage, `hub-host` had about two minutes of uptime. Docker was
active and enabled, and `kids-monitor-hub` had automatically started with
`unless-stopped` and its original data volume. Deployed revision was `81e1d33`.
Both paired Pixels had already reconnected before intervention.

A requested hub-only restart at **15:50:41 PDT** preserved the image, data volume,
and certificate fingerprint. The Pixel 6 logged reconnect at **15:50:42 PDT**.
At **15:51:00 PDT**, server last-seen ages were 0 seconds for the Pixel 6 and
3 seconds for the Pixel 8 Pro; noise samples were fresh and SQLite quick-check
returned `ok`. Both phone foreground services were active. Subsequent snapshots
confirmed continuing traffic. Live video playback was not exercised in this run.
