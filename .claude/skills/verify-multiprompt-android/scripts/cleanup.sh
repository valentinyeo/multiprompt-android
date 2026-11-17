#!/usr/bin/env bash
set -euo pipefail
: "${FIXTURE:?Set fixture directory}"
: "${EVIDENCE:?Set evidence directory}"
mkdir -p "$EVIDENCE"
exec > >(tee -a "$EVIDENCE/cleanup.txt") 2>&1
status=0
if command -v adb >/dev/null && timeout 10s adb get-state >/dev/null 2>&1; then
    timeout 10s adb shell rm -f /sdcard/Download/verify-key.pem /sdcard/verify-ui.xml /sdcard/verify-doctor.xml || true
fi
if [[ -d "$FIXTURE" ]]; then
    sudo cat "$FIXTURE/sshd.log" > "$EVIDENCE/sshd.log" 2>/dev/null || true
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/receipts.txt" 2>/dev/null || true
    if [[ "${VERIFY_FEATURE:-}" == ai-switch.md ]]; then
        sudo -u verifyssh python3 - > "$EVIDENCE/ai-switch-host-debug.json" <<'PYHOST'
import json
from pathlib import Path
home = Path('/home/verifyssh')
root = home / '.local/state/multiprompt/agent-host'
files = [path for path in root.glob('*') if path.is_file() and path.suffix in ('.json', '.jsonl', '.log')]
files += list(home.glob('ai-switch-*.txt')) + list(home.glob('ai-switch-*.jsonl'))
print(json.dumps({str(path.relative_to(home)): path.read_text()[-64000:] for path in files}, indent=2))
PYHOST
    fi
    for name in tmux sshd; do
        if [[ -f "$FIXTURE/$name.pid" ]]; then
            pid=$(cat "$FIXTURE/$name.pid")
            [[ "$pid" =~ ^[0-9]+$ ]]
            if sudo kill "$pid"; then
                printf 'STOPPED %s pid=%s\n' "$name" "$pid"
            else
                status=1
            fi
        fi
    done
    if [[ -f /home/verifyssh/device-approval.pid ]]; then
        pid=$(sudo cat /home/verifyssh/device-approval.pid)
        [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]]
        sudo kill "$pid"
        sudo cat /home/verifyssh/device-approval-receipts.jsonl > "$EVIDENCE/device-approval-receipts.txt"
    fi
    rm -f "$FIXTURE/pending-device" "$FIXTURE/pending-device.pub" "$FIXTURE/denied-device" "$FIXTURE/denied-device.pub" "$FIXTURE/device-known-hosts"
    rm -f "$FIXTURE/user-key" "$FIXTURE/user-key.pub" "$FIXTURE/host-key" "$FIXTURE/host-key.pub" "$FIXTURE/rotated-host-key" "$FIXTURE/rotated-host-key.pub"
fi
printf 'CLEANUP COMPLETE: evidence retained; status=%s\n' "$status"
exit "$status"
