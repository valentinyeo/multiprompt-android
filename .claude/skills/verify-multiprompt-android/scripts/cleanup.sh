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
    sudo cp "$FIXTURE/sshd.log" "$EVIDENCE/sshd.log" 2>/dev/null || true
    sudo cp /home/verifyssh/receipts.txt "$EVIDENCE/receipts.txt" 2>/dev/null || true
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
    rm -f "$FIXTURE/user-key" "$FIXTURE/user-key.pub" "$FIXTURE/host-key" "$FIXTURE/host-key.pub"
fi
printf 'CLEANUP COMPLETE: evidence retained; status=%s\n' "$status"
exit "$status"
