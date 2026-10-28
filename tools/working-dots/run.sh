#!/usr/bin/env bash
set -euo pipefail
bash tools/release-choices/seed.sh
adb shell settings put secure lockscreen.disabled 1
adb shell am instrument -w -r dev.multiprompt.verification/dev.multiprompt.verification.Observe > "$EVIDENCE/visibility.txt" 2>&1 &
observer=$!
finish() {
    code=$?
    trap - EXIT
    kill "$observer" 2>/dev/null || true
    wait "$observer" 2>/dev/null || true
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
trap finish EXIT
.claude/skills/verify-multiprompt-android/scripts/drive.sh chat-working.md
