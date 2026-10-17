#!/usr/bin/env bash
# Issues a fresh pairing code on the hub and opens it as a large QR image.
#
# The hub prints a QR to the terminal, which scans fine but is awkward if the
# terminal is small or scrolled. This renders the same payload as a PNG and
# opens it full-size.
#
#   tools/show-pairing-qr.sh [parent|monitor]
#
# Requires: ssh access to the hub host, and `npm install qrcode` somewhere.
set -euo pipefail

ROLE="${1:-parent}"
HUB_SSH="${HUB_SSH:-}"
HUB_DIR="${HUB_DIR:-~/kids-monitor}"
QR_MODULE_DIR="${QR_MODULE_DIR:-$(pwd)/.qr-tool}"
OUT="${OUT:-/tmp/kids-monitor-pairing.png}"

if [ -z "$HUB_SSH" ]; then
  echo "error: HUB_SSH is not set. Please set HUB_SSH to your hub host (e.g. export HUB_SSH=user@hub-host)" >&2
  exit 1
fi

if [ ! -d "$QR_MODULE_DIR/node_modules/qrcode" ]; then
  echo "installing the qrcode renderer into $QR_MODULE_DIR ..."
  mkdir -p "$QR_MODULE_DIR"
  # Written directly rather than via `npm init -y`, which derives the package
  # name from the directory and then rejects it: the default QR_MODULE_DIR is
  # `.qr-tool`, and npm refuses a name starting with a dot. That failure used to
  # be discarded by `>/dev/null 2>&1`, so `set -e` killed the script one line
  # after printing "installing ...", with no error and no QR.
  cat > "$QR_MODULE_DIR/package.json" <<'JSON'
{ "name": "kids-monitor-qr-tool", "version": "1.0.0", "private": true }
JSON
  if ! (cd "$QR_MODULE_DIR" && npm install --silent qrcode); then
    echo "could not install the qrcode renderer into $QR_MODULE_DIR" >&2
    exit 1
  fi
fi

echo "issuing a fresh $ROLE pairing code on $HUB_SSH ..."
OUTPUT=$(ssh -o BatchMode=yes "$HUB_SSH" \
  "cd $HUB_DIR && docker compose -f deploy/docker-compose.yml exec -T hub \
     node services/hub/dist/cli/pair.js $ROLE" 2>&1)

HOSTPORT=$(echo "$OUTPUT" | grep 'host:'        | awk '{print $2}')
CODE=$(echo     "$OUTPUT" | grep 'code:'        | awk '{print $2}')
FINGERPRINT=$(echo "$OUTPUT" | grep 'fingerprint:' | awk '{print $2}')

if [ -z "$HOSTPORT" ] || [ -z "$CODE" ] || [ -z "$FINGERPRINT" ]; then
  echo "could not read the pairing details from the hub:"
  echo "$OUTPUT"
  exit 1
fi

HOST="${HOSTPORT%:*}"
PORT="${HOSTPORT##*:}"

PAYLOAD=$(printf '{"v":1,"host":"%s","port":%s,"fingerprint":"%s","code":"%s","role":"%s"}' \
  "$HOST" "$PORT" "$FINGERPRINT" "$CODE" "$ROLE")

QR_MODULE_DIR="$QR_MODULE_DIR" OUT="$OUT" PAYLOAD="$PAYLOAD" node -e '
const qrcode = require(process.env.QR_MODULE_DIR + "/node_modules/qrcode");
qrcode.toFile(process.env.OUT, process.env.PAYLOAD, {
  width: 900, margin: 3, errorCorrectionLevel: "M",
}, (err) => {
  if (err) { console.error(err); process.exit(1); }
});
'

echo
echo "  host:        $HOSTPORT"
echo "  code:        $CODE   (single-use, expires in 5 minutes)"
echo "  image:       $OUT"
echo
command -v open >/dev/null && open "$OUT" && echo "opened. point the phone at it."
