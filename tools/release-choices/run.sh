#!/usr/bin/env bash
set -euo pipefail
bash tools/release-choices/seed.sh
finish() {
    code=$?
    trap - EXIT
    set +e
    adb shell kill -2 "$(adb shell cat /sdcard/choices-recording.pid | tr -d '\r')"
    wait "$recording"
    adb pull /sdcard/release-choices.mp4 "$EVIDENCE/release-choices.mp4"
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
adb shell 'screenrecord --time-limit 180 /sdcard/release-choices.mp4 & echo $! > /sdcard/choices-recording.pid; wait' > "$FIXTURE/recording.log" 2>&1 &
recording=$!
trap finish EXIT
.claude/skills/verify-multiprompt-android/scripts/drive.sh chat-choices.md
