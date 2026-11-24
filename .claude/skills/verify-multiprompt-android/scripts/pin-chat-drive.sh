#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
other_target=pin-00
if [[ "$deep_target" == pin-00 ]]; then
    other_target=pin-01
fi

top_rows() {
    python3 - "$EVIDENCE/current.xml" "$EVIDENCE/seed-counts.json" <<'PY'
import json
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
names = set(json.loads(open(sys.argv[2]).read())["session_names"])
rows = {}
for node in root.iter("node"):
    text = (node.get("text") or "").strip()
    bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds") or "")
    if text in names and bounds:
        rows[text] = min(rows.get(text, 1 << 30), int(bounds.group(2)))
print(json.dumps(sorted(rows, key=rows.get)))
PY
}

prove_pinned_on_top() {
    local phase=$1 rows second
    "$mpa" tap text "$deep_target" --reveal > /dev/null
    rows=$(top_rows)
    printf '%s\n' "$rows" > "$EVIDENCE/pin-top-rows-$phase.json"
    python3 - "$rows" "$phase" "$deep_target" <<'PY'
import json
import sys

rows, phase, pinned = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3]
assert rows and rows[0] == pinned, f"{phase}: pinned chat {pinned} is not the top row on screen, found {rows[:3]}"
PY
    second=$(printf '%s' "$rows" | jq -er '.[1]')
    "$mpa" proof --name "$phase" --order "$deep_target" --order "$second"
    "$mpa" screenshot "$phase"
    cp "$EVIDENCE/current.xml" "$EVIDENCE/$phase.xml"
}

sleep 2
"$mpa" tap text "$other_target"
"$mpa" read --wait-text "Message $other_target"
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox
"$mpa" read --wait-text "$deep_target"
"$mpa" read --wait-text "$other_target"
"$mpa" screenshot pin-before
cp "$EVIDENCE/current.xml" "$EVIDENCE/pin-before.xml"
"$mpa" proof --name pin-before --order "$deep_target" --order "$other_target"
"$mpa" hold desc "$deep_description"
"$mpa" read --wait-text 'Pin to top'
"$mpa" read --select absent --value Unpin
"$mpa" screenshot pin-menu
"$mpa" tap text 'Pin to top'
"$mpa" read --wait-absent 'Pin to top'
sleep 2
prove_pinned_on_top pin-pinned
"$mpa" tap text "$other_target"
"$mpa" read --wait-text "Message $other_target"
"$mpa" fill "Message $other_target" PIN_OTHER_REPLY_140
"$mpa" tap desc 'Send prompt'
"$mpa" read --wait-text 'VERIFY_RECEIVED:PIN_OTHER_REPLY_140'
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox
"$mpa" read --wait-text "$other_target"
"$mpa" proof --name pin-after-reply-newest --require-text "$other_target" --forbid-desc "$deep_description"
cp "$EVIDENCE/current.xml" "$EVIDENCE/pin-after-reply-newest.xml"
prove_pinned_on_top pin-after-reply
relaunch_app Inbox
"$mpa" read --wait-text Inbox
prove_pinned_on_top pin-after-restart
"$mpa" hold desc "$deep_description"
"$mpa" read --wait-text Unpin
"$mpa" read --select absent --value 'Pin to top'
"$mpa" screenshot pin-unpin-menu
"$mpa" tap text Unpin
"$mpa" read --wait-absent Unpin
sleep 2
"$mpa" screenshot pin-unpinned
cp "$EVIDENCE/current.xml" "$EVIDENCE/pin-unpinned.xml"
relaunch_app Inbox
"$mpa" read --wait-text Inbox
"$mpa" proof --name pin-unpinned --order "$deep_target" --order "$other_target"
"$mpa" hold desc "$deep_description"
"$mpa" read --wait-text 'Pin to top'
"$mpa" tap text 'Pin to top'
"$mpa" read --wait-absent 'Pin to top'
sleep 2
prove_pinned_on_top pin-archive-top
row_below=$(python3 - "$EVIDENCE/pin-top-rows-pin-archive-top.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1]))[1])
PY
)
"$mpa" tap text "$deep_target"
"$mpa" read --wait-text "Message $deep_target"
"$mpa" tap desc 'Session actions'
"$mpa" screenshot pin-archive-next-menu
"$mpa" tap text Waiting
"$mpa" read --wait-text "Message $row_below"
"$mpa" proof --name pin-archive-next --require-text "Message $row_below" --forbid-text "Message $deep_target"
cp "$EVIDENCE/current.xml" "$EVIDENCE/pin-archive-next.xml"
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox
"$mpa" tap desc 'Switch session bucket'
"$mpa" tap text Waiting
"$mpa" hold desc "$deep_description"
"$mpa" tap text 'Return to open'
"$mpa" tap desc 'Switch session bucket'
"$mpa" tap text Inbox
sleep 2
prove_pinned_on_top pin-after-restore
"$mpa" hold desc "$deep_description"
"$mpa" read --wait-text Unpin
"$mpa" tap text Unpin
"$mpa" read --wait-absent Unpin
python3 - "$EVIDENCE" "$deep_target" "$other_target" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
pinned_chat, other_chat = sys.argv[2], sys.argv[3]
names = ["pin-before", "pin-pinned", "pin-after-reply-newest", "pin-after-reply", "pin-after-restart", "pin-unpinned", "pin-archive-top", "pin-archive-next", "pin-after-restore"]
phases = {}
for name in names:
    phase = json.loads((root / f"{name}.json").read_text())
    phases[name] = {"passed": phase["passed"], "checks": phase["checks"]}
summary = {
    "pinnedChat": pinned_chat,
    "otherChat": other_chat,
    "allPassed": all(phase["passed"] for phase in phases.values()),
    "phases": phases,
}
assert summary["allPassed"], summary
(root / "pin-chat-state.json").write_text(json.dumps(summary, separators=(",", ":")) + "\n")
PY
step=$((step + 7))
