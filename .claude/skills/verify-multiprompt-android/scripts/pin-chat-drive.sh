#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"
other_target=pin-00
if [[ "$deep_target" == pin-00 ]]; then
    other_target=pin-01
fi
sleep 2
"$mpa" tap text "$other_target"
"$mpa" read --wait-text "Message $other_target"
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox
"$mpa" read --wait-text "$deep_target"
"$mpa" read --wait-text "$other_target"
"$mpa" screenshot pin-before
"$mpa" proof --name pin-before --order "$deep_target" --order "$other_target"
"$mpa" hold desc "$deep_description"
"$mpa" read --wait-text 'Pin to top'
"$mpa" read --select absent --value Unpin
"$mpa" screenshot pin-menu
"$mpa" tap text 'Pin to top'
"$mpa" read --wait-absent 'Pin to top'
"$mpa" screenshot pin-pinned
"$mpa" proof --name pin-pinned --order "$other_target" --order "$deep_target"
sleep 2
"$mpa" tap text "$other_target"
"$mpa" read --wait-text "Message $other_target"
"$mpa" fill "Message $other_target" PIN_OTHER_REPLY_140
"$mpa" tap desc 'Send prompt'
"$mpa" read --wait-text 'VERIFY_RECEIVED:PIN_OTHER_REPLY_140'
"$mpa" tap desc Back
"$mpa" read --wait-text Inbox
"$mpa" read --wait-text "$deep_target"
"$mpa" read --wait-text "$other_target"
"$mpa" screenshot pin-after-reply
"$mpa" proof --name pin-after-reply --order "$other_target" --order "$deep_target"
relaunch_app Inbox
"$mpa" read --wait-text "$deep_target"
"$mpa" read --wait-text "$other_target"
"$mpa" screenshot pin-after-restart
"$mpa" proof --name pin-after-restart --order "$other_target" --order "$deep_target"
"$mpa" hold desc "$deep_description"
"$mpa" read --wait-text Unpin
"$mpa" read --select absent --value 'Pin to top'
"$mpa" screenshot pin-unpin-menu
"$mpa" tap text Unpin
"$mpa" read --wait-absent Unpin
"$mpa" screenshot pin-unpinned
"$mpa" proof --name pin-unpinned --order "$deep_target" --order "$other_target"
python3 - "$EVIDENCE" "$deep_target" "$other_target" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
pinned_chat, other_chat = sys.argv[2], sys.argv[3]
names = ["pin-before", "pin-pinned", "pin-after-reply", "pin-after-restart", "pin-unpinned"]
phases = {name: json.loads((root / f"{name}.json").read_text()) for name in names}
summary = {
    "pinnedChat": pinned_chat,
    "otherChat": other_chat,
    "allPassed": all(phase["passed"] for phase in phases.values()),
    "phases": {name: {"passed": phase["passed"], "checks": phase["checks"]} for name, phase in phases.items()},
}
assert summary["allPassed"], summary
(root / "pin-chat-state.json").write_text(json.dumps(summary, separators=(",", ":")) + "\n")
PY
step=$((step + 7))
