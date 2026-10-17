#!/usr/bin/env bash
# Pairs a plugged-in device without a camera, by handing the app the same
# payload the QR carries over adb.
#
#   tools/pair-device.sh <monitor|parent> [adb-serial]
#
# Debug builds only: MainActivity drops the extra unless BuildConfig.DEBUG.
# The payload is identical to the QR's, fingerprint included, so the
# certificate pin is unchanged -- this removes the camera, not the check.
#
# Requires: ssh access to the hub host, adb on PATH.
set -euo pipefail

ROLE="${1:-}"
SERIAL="${2:-}"
HUB_SSH="${HUB_SSH:-}"
HUB_DIR="${HUB_DIR:-~/kids-monitor}"

if [ -z "$HUB_SSH" ]; then
  echo "error: HUB_SSH is not set. Please set HUB_SSH to your hub host (e.g. export HUB_SSH=user@hub-host)" >&2
  exit 1
fi

case "$ROLE" in
  monitor) PKG="com.kidsmonitor.monitor" ;;
  parent)  PKG="com.kidsmonitor.parent" ;;
  *) echo "usage: $0 <monitor|parent> [adb-serial]" >&2; exit 2 ;;
esac

ADB=(adb)
if [ -n "$SERIAL" ]; then ADB=(adb -s "$SERIAL"); fi

# Fail here rather than after burning a single-use code on an absent device.
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "no device${SERIAL:+ $SERIAL} reachable over adb" >&2
  exit 1
fi

echo "issuing a fresh $ROLE pairing code on $HUB_SSH ..."
OUTPUT=$(ssh -o BatchMode=yes "$HUB_SSH" \
  "cd $HUB_DIR && docker compose -f deploy/docker-compose.yml exec -T hub \
     node services/hub/dist/cli/pair.js $ROLE" 2>&1)

HOSTPORT=$(echo "$OUTPUT"    | grep 'host:'        | awk '{print $2}')
CODE=$(echo "$OUTPUT"        | grep 'code:'        | awk '{print $2}')
FINGERPRINT=$(echo "$OUTPUT" | grep 'fingerprint:' | awk '{print $2}')

if [ -z "$HOSTPORT" ] || [ -z "$CODE" ] || [ -z "$FINGERPRINT" ]; then
  echo "could not read the pairing details from the hub:" >&2
  echo "$OUTPUT" >&2
  exit 1
fi

PAYLOAD=$(printf '{"v":1,"host":"%s","port":%s,"fingerprint":"%s","code":"%s","role":"%s"}' \
  "${HOSTPORT%:*}" "${HOSTPORT##*:}" "$FINGERPRINT" "$CODE" "$ROLE")

# JSON contains double quotes but never single ones, so single-quoting it for
# the *device* shell is safe. adb joins argv and re-parses it on the device.
#
# -S force-stops the app first. Without it, an app already on screen receives
# the intent through onNewIntent, MainActivity reads the payload only in
# onCreate, and the code is burned with nothing happening.
"${ADB[@]}" shell am start -S -n "$PKG/.MainActivity" \
  --es pairing_payload "'$PAYLOAD'" >/dev/null

echo "  host:   $HOSTPORT"
echo "  role:   $ROLE"
echo "  sent to $PKG on ${SERIAL:-the only device}"
if [ "$ROLE" = monitor ]; then
  echo "  On the phone, choose a distinct monitor name and tap Continue to pairing."
  echo "  Pairing waits for this step; the code expires after five minutes."
fi
echo
echo "watch it land:  adb ${SERIAL:+-s $SERIAL }logcat -s KidsMonitor"
