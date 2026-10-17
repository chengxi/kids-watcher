# Pairing an iPhone or iPad parent with a QR code

The intended setup is agent-assisted: the coding agent checks the deployment,
generates a short-lived QR and displays it directly on the user's computer.
The person using the new parent scans it with **Kid Monitor Parent**. No setup
URL or browser tab is needed. The Mac flow below has been verified; Linux and
Windows display paths have not yet been tested. Existing devices stay paired.

## What the agent handles

The agent discovers the actual installation, checks network reachability,
installs any missing local rendering dependencies, issues the correct device
role and opens the QR at a readable size. It should finish dependency setup and
get the phone's scanner ready before issuing the five-minute code. Render the
payload locally with a QR library; do not send it to an online QR service.

The user's steps are to unlock the phone, confirm a new monitor's name when
applicable, scan the QR and grant app permissions. The agent then verifies
registration and advancing heartbeats, and removes its temporary QR artifacts.
If the agent runs remotely or without a desktop, it must arrange to display the
image on the user's screen through its available artifact/viewer tools; opening
a viewer on the server is not sufficient.

## Linux and Windows portability

QR generation and redemption do not depend on the computer's OS. The existing
`tools/show-pairing-qr.sh` launcher does: it requires Bash/Unix tools, defaults to
`/tmp`, and ends by calling macOS `open`. It is not a native PowerShell helper.
On a system without `open`, it can produce the PNG but exit unsuccessfully at
the display step. Do not treat its current launcher as verified cross-platform
support.

An agent can generate the same PNG and use the host's image viewer:

| Host | Local display method | Verification status |
| --- | --- | --- |
| macOS | `open <image-path>` | Real QR pairing verified below. |
| Linux desktop | `xdg-open <image-path>` | Supported display mechanism; not tested in this installation. |
| Windows desktop | PowerShell `Invoke-Item -LiteralPath <image-path>` | Supported display mechanism; not tested in this installation. |

[`xdg-open`](https://portland.freedesktop.org/doc/xdg-open.html) requires a
desktop session and opens PNGs in the preferred image application.
[`Invoke-Item`](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/invoke-item)
uses the associated application on Windows. Both require a working image viewer.
WSL or a remote Linux shell needs a deliberate handoff to the user's desktop.

A portable QR helper with native temporary paths, safe argument handling,
private output and separate generation/display results remains a tooling gap.
An agent may adapt the current procedure with its available tools, but must
report which OS was actually exercised. Building the iOS app still uses the
Mac/Xcode workflow in [ios-parent.md](ios-parent.md); displaying its pairing QR
on Linux or Windows does not replace that build step.

## Before generating the QR

App installation needs an authorized developer connection, which can be Wi-Fi;
the QR scan itself needs no USB or developer connection. See
[device connections](device-connections.md) for first-time setup limits.

1. Install and open the **KidsParent** app using [ios-parent.md](ios-parent.md).
   Keep the iPhone or iPad unlocked. If launching from Xcode or `devicectl` fails
   because the device is locked, unlock it and retry the launch.
2. Put the new parent on the same home network as the hub. Have a Mac screen
   available to display the QR: a device cannot scan its own screen. The native
   Camera app is not the pairing scanner; use the scanner inside the parent app.
3. Establish the deployment using [recovering-hub.md](recovering-hub.md): confirm
   SSH host, absolute checkout path, running container, published LAN address,
   data volume and certificate fingerprint. Check `/healthz` from the Mac.
   Do not update or rebuild the hub just to add a parent.
4. In the parent setup screen, tap **Scan a QR code**. The app initially shows
   manual address/code entry. Allow camera access when asked. Get the scanner
   ready before issuing the five-minute code.

## Display and scan

From the repository root on the Mac, substitute the verified SSH host and
absolute deployed checkout path:

```bash
QR_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kids-monitor-qr.XXXXXX")"
(
  umask 077
  HUB_SSH=<actual-ssh-host> HUB_DIR=<absolute-deployed-checkout> \
    OUT="$QR_DIR/pairing.png" bash tools/show-pairing-qr.sh parent >/dev/null
)
```

The helper requires Node/npm and SSH access. It installs its QR renderer into
the ignored `.qr-tool/` directory if needed, issues a fresh **parent** code,
and opens the PNG on macOS. Suppressing stdout keeps the code out of captured
terminal output. The private temporary directory protects the image, which
contains pairing material; do not attach it to issues, logs or documentation.

Point the parent app's scanner at the QR on the Mac. The QR includes the address,
port, certificate fingerprint and role, so there is no manual entry or separate
six-character confirmation. Allow **Local Network** access if iOS asks. A
successful pairing saves credentials and opens the dashboard automatically.

Leave the parent open while verifying. Check the newly registered parent name
and advancing last-seen twice, at least 20 seconds apart, using the read-only
diagnostic in [recovering-hub.md](recovering-hub.md#4-prove-the-paired-apps-resumed-traffic).
Confirm that the expected active monitor appears online in the dashboard.
Opening Watch live is a separate picture/audio check; registration alone does
not prove live playback.

After success or expiry, close the QR image and remove only the temporary
artifacts created above:

```bash
rm -- "$QR_DIR/pairing.png"
rmdir -- "$QR_DIR"
```

## If pairing does not finish

- **Expired or already used code:** close/delete the old image, issue a fresh
  parent QR and scan again. Each code works once and expires after five minutes.
- **Wrong role:** generate a parent QR, not a monitor QR.
- **No camera access:** use **Grant camera access**. If permission was previously
  denied, enable Camera in the app's Settings; leave and re-enter the scanner
  if its permission view does not refresh. Manual pairing remains available.
- **Hub connection fails:** check home Wi-Fi, Local Network permission and the
  actual published address/port. Reissuing a code does not fix network routing.
  Preserve the hub certificate and all existing pairings.
- **Only one screen available:** use manual entry with the hub's six-character
  certificate check. Opening a pairing link directly in the app is not supported
  today.

## Flow improvements identified during the walkthrough

QR pairing should be a clear first-choice action next to manual entry, rather
than requiring users to find the scanner below the address/code fields.

The current app tells users to get a code “from your hub” but does not explain
where to get it. The preferred flow should explain **your setup agent displays
a QR on the computer → scan here → dashboard appears**, with manual entry as
an alternative. The agent handles dependencies, hub access, expiry/reissue and
connectivity checks; the user should not need to run shell commands.

The next tooling improvement is reliable direct display on macOS, Linux and
Windows. A browser setup page is optional future work, not a prerequisite for
agent-assisted pairing. Same-device app links are also optional; this flow uses
the computer's screen and the phone's in-app scanner.

## Verified walkthrough: October 14, 2026

The current workspace's Debug parent was installed on a connected iPad Pro.
Installation succeeded while locked, but launching required unlocking and a
second launch attempt. The existing deployed hub was inspected and left running
with its original image, certificate and persistent volume.

A fresh parent QR was generated with the existing helper and displayed privately
on the Mac. The iPad registered as a new parent, and its last-seen advanced across
two checks more than 20 seconds apart. The active Android monitor continued to
send fresh noise samples and the existing Android parent continued heartbeats.
No debug pairing-payload transfer was used in this walkthrough. The user
confirmed that the dashboard appeared after scanning the QR.
The iPad's existing developer connection was over the local network, so this
installation and launch also exercised Wi-Fi rather than USB.

This establishes QR redemption, arrival at the dashboard and continuing parent
connectivity. Dashboard layout, live picture/audio, denial/recovery of
permissions, expired-code handling
and a same-device browser handoff were not independently exercised by the
operator in this run. Linux and Windows display remain untested.
