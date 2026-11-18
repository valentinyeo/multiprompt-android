#!/usr/bin/env bash
set -euo pipefail

tap_desc 'Session list actions'
tap_text Settings
tap_text 'Sign in'
wait_for_text 'Continue as fixture account'
shot screencast-account-browser
tap_text 'Continue as fixture account'
wait_for_text 'Switch / renew login'
shot screencast-signed-in
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" snapshot "" > "$EVIDENCE/screencast-signed-in.json"
adb shell input keyevent KEYCODE_BACK
wait_for_text Inbox
tap_text All
tap_text verify-session
wait_for_text 'Message verify-session'

python3 - "$FIXTURE/screencast-proof.png" <<'PYIMAGE'
from pathlib import Path
import struct
import sys
import zlib

def chunk(name, data):
    return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data))

Path(sys.argv[1]).write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 64, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\0' + b'\x40\x80\xc0' * 64) * 64)) + chunk(b'IEND', b''))
PYIMAGE
adb push "$FIXTURE/screencast-proof.png" /sdcard/Pictures/screencast-proof.png
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/screencast-proof.png

tap_desc Attach
tap_text 'Add image'
wait_for_text Photos
shot screencast-account-picker
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" snapshot "" > "$EVIDENCE/screencast-picker.json"
photo=$(python3 - "$EVIDENCE/current.xml" <<'PYPHOTO'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert not any(n.get('text') == 'Screencast2 uploads' for n in root.iter('node'))
photos = [n.get('content-desc') for n in root.iter('node') if n.get('content-desc', '').startswith('Photo taken')]
assert len(photos) == 1, photos
print(photos[0])
PYPHOTO
)
tap_desc "$photo"
shot screencast-image-selected
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" snapshot "" > "$EVIDENCE/screencast-selected.json"
python3 - "$EVIDENCE/current.xml" <<'PYSELECTED'
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))
assert any(n.get('text') in ('Add (1)', 'Add 1', '1 selected') or (n.get('content-desc', '').startswith('Photo taken') and (n.get('checked') == 'true' or n.get('selected') == 'true')) for n in nodes)
PYSELECTED
sudo cat /home/verifyssh/device-approval-receipts.jsonl > "$EVIDENCE/screencast-account-receipts.txt"
python3 - "$EVIDENCE/screencast-account-receipts.txt" <<'PYRECEIPTS'
import json
import sys
rows = [json.loads(line) for line in open(sys.argv[1])]
assert dict(action='browser_pkce_exchange', verified=True) in rows
assert dict(action='screencast_key_fetch', authenticated=True, status=200) in rows
PYRECEIPTS
printf 'SIGNED_IN_IMAGE_SELECTED_WITHOUT_TYPING_A_KEY\n'
