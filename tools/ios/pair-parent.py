#!/usr/bin/env python3
"""Transfer a single-use parent QR payload to an installed DEBUG iOS app.
No tokens, codes, private keys or payloads are printed or retained locally.
Establish this installation's SSH host, checkout and LAN address beforehand.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ssh', required=True)
parser.add_argument('--checkout', required=True)
parser.add_argument('--host', required=True)
parser.add_argument('--port', required=True, type=int)
parser.add_argument('--device', required=True)
parser.add_argument('--no-launch', action='store_true', help='Let a UI test launch the app and approve Local Network access')
args = parser.parse_args()
command = f'cd {shlex.quote(args.checkout)} && docker compose -f deploy/docker-compose.yml exec -T hub node services/hub/dist/cli/pair.js parent'
result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', args.ssh, command], capture_output=True, text=True)
if result.returncode:
    raise SystemExit('Hub pairing command failed; no payload was printed.')
code = re.search(r'^\s*code:\s*(\d{6})\s*$', result.stdout, re.M)
pin = re.search(r'^\s*fingerprint:\s*([a-fA-F0-9:]+)\s*$', result.stdout, re.M)
address = re.search(r'^\s*host:\s*(\S+)\s*$', result.stdout, re.M)
if not code or not pin or not address or address.group(1) != f'{args.host}:{args.port}':
    raise SystemExit('Hub pairing output does not match the established LAN address.')
payload = dict(v=1, host=args.host, port=args.port, fingerprint=pin.group(1), code=code.group(1), role='parent')
with tempfile.TemporaryDirectory(prefix='kids-parent-pair-') as directory:
    path = Path(directory) / 'parent-pairing.json'
    path.write_text(json.dumps(payload))
    os.chmod(path, 0o600)
    copied = subprocess.run(['xcrun', 'devicectl', 'device', 'copy', 'to', '--device', args.device, '--source', str(path), '--destination', 'Documents/parent-pairing.json', '--domain-type', 'appDataContainer', '--domain-identifier', 'com.kidsmonitor.parent.ios', '--quiet'], capture_output=True)
    if copied.returncode:
        raise SystemExit('Could not transfer pairing file; temporary local payload removed.')
    if not args.no_launch:
        launched = subprocess.run(['xcrun', 'devicectl', 'device', 'process', 'launch', '--device', args.device, '--terminate-existing', 'com.kidsmonitor.parent.ios', '--debug-pair', '--quiet'], capture_output=True)
        if launched.returncode:
            raise SystemExit('App launch failed. Open the installed debug app with --debug-pair before the code expires.')
print('Parent pairing started. Confirm its name and advancing last-seen on the hub.')
