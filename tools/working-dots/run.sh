#!/usr/bin/env bash
set -euo pipefail
bash tools/release-choices/seed.sh
adb shell settings put secure lockscreen.disabled 1
finish() {
    code=$?
    trap - EXIT
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
trap finish EXIT
.claude/skills/verify-multiprompt-android/scripts/drive.sh chat-working.md
