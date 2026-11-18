#!/usr/bin/env bash
wait_for_text 'Message verify-session'
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" version "$EXPECTED_VERSION" > "$EVIDENCE/reader-version-label.txt"
shot reader-version
python3 - "$EVIDENCE" "$EXPECTED_VERSION" <<'PY'
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
version = sys.argv[2]
description = f"Version {version}, open release notes"
xml = sorted(root.glob("[0-9][0-9]-reader-version.xml"))[-1]
nodes = list(ET.parse(xml).getroot().iter("node"))
assert any(node.get("text") == version and node.get("content-desc") == description for node in nodes), description
(root / "reader-version.json").write_text(json.dumps({"contentDescription": description, "text": version, "xml": xml.name}) + "\n")
PY
printf 'reader version label tap\n'
tap_desc "Version ${EXPECTED_VERSION}, open release notes"
wait_for_text 'App updates'
shot reader-updates
python3 - "$EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
xml = sorted(root.glob("[0-9][0-9]-reader-updates.xml"))[-1]
texts = [node.get("text") or "" for node in ET.parse(xml).getroot().iter("node")]
assert "App updates" in texts
(root / "reader-updates.json").write_text(json.dumps({"screen": "App updates", "xml": xml.name}) + "\n")
PY
adb shell input keyevent KEYCODE_BACK
wait_for_text Inbox
tap_text All
tap_text verify-session
wait_for_text 'Message verify-session'
source "$scripts/ai-switch-drive.sh"
