# Deploying and updating the hub

Recovering after a power outage or host reboot? Follow
[`recovering-hub.md`](recovering-hub.md) to restore the existing service and
verify paired apps before considering a software update.

The hub is deployed from a git clone, not a file copy. That gives you two things
a copy does not: `git pull` as the update mechanism, and `git rev-parse HEAD` as
an answer to "what is actually running right now?"

You do **not** need GitHub for this. A bare repository on the server itself is
enough, and nothing leaves your network.

## One-time setup

On the server, create a bare repository to push into:

```bash
ssh <server> 'git init --bare --initial-branch=main ~/kids-monitor.git'
```

On your development machine, add it as a remote and push:

```bash
git remote add home <server>:kids-monitor.git
git push -u home main
```

Back on the server, clone it into place and add the config:

```bash
git clone ~/kids-monitor.git ~/kids-monitor
cd ~/kids-monitor
cp .env.example deploy/.env
```

Edit `deploy/.env` — at minimum check `HUB_PUBLISHED_PORT` is free on that box.
`deploy/.env` is gitignored, so it never travels with the repository; each
deployment holds its own.

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

## Updating

From your development machine:

```bash
git push home main
```

On the server:

```bash
cd ~/kids-monitor && git pull && docker compose -f deploy/docker-compose.yml up -d --build
```

That is the whole update path. It is idempotent, it needs nobody's laptop, and it
can be run by a cron job or handed to someone else.

## What survives an update

The `kids-monitor_hub-data` volume holds `hub.db`, `hub.cert.pem`, and
`hub.key.pem`. Rebuilding the image and recreating the container does **not**
touch it, so:

- **The certificate fingerprint stays the same**, and every already-paired device
  keeps working. Verify after an update:

  ```bash
  docker logs kids-monitor-hub 2>&1 | grep fingerprint
  ```

- Devices, events, and per-monitor detection settings all persist.

The compose file pins `name: kids-monitor`, so the volume is keyed to the project
rather than the directory. Moving or re-cloning the source tree does not orphan
your data.

**`docker compose down -v` deletes that volume.** It destroys the certificate and
every paired device in one step, and there is no recovery — every phone has to be
re-paired. Use `down` without `-v`.

## Checking what is deployed

```bash
ssh <server> 'cd ~/kids-monitor && git rev-parse --short HEAD && git status --porcelain'
```

An empty `git status` means the running tree matches a commit exactly. Anything
listed is a local edit that exists nowhere else — commit it or revert it, or the
next `git pull` will fight you.

## Current deployment

| | |
| --- | --- |
| Host | `hub-host` (192.168.1.100), Ubuntu 24.04 x86_64 |
| Docker | 29.6.2, Compose v5.3.1 |
| URL | `https://192.168.1.100:8444` |
| Port | **8444**, because 8443 is taken on that box by an unrelated service |
| Source | `~/kids-monitor`, cloned from `~/kids-monitor.git` |
| Image | `kids-monitor-hub:latest`, ~440 MB |
