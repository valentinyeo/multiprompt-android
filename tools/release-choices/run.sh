#!/usr/bin/env bash
set -euo pipefail
bash tools/release-choices/seed.sh
if [[ "${VERIFY_FEATURE:-chat-choices.md}" == chat-queued.md || "${VERIFY_FEATURE:-}" == session-gestures.md ]]; then
    trap 'kill "$(cat "$FIXTURE/offline-proxy.pid")"' EXIT
    .claude/skills/verify-multiprompt-android/scripts/drive.sh "$VERIFY_FEATURE"
    exit 0
fi
finish() {
    code=$?
    trap - EXIT
    set +e
    if [[ "${VERIFY_FEATURE:-}" == session-favorites.md ]]; then
        adb shell kill -0 "$(adb shell cat /sdcard/choices-recording.pid | tr -d '\r')" || code=1
    fi
    adb shell kill -2 "$(adb shell cat /sdcard/choices-recording.pid | tr -d '\r')"
    wait "$recording"
    if [[ "${VERIFY_FEATURE:-}" == session-favorites.md ]]; then
        adb pull /sdcard/release-choices.mp4 "$EVIDENCE/release-choices.mp4" || code=1
        test -s "$EVIDENCE/release-choices.mp4" || code=1
    else
        adb pull /sdcard/release-choices.mp4 "$EVIDENCE/release-choices.mp4"
    fi
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
if [[ "${VERIFY_FEATURE:-}" == session-favorites.md ]]; then
    adb shell 'screenrecord --time-limit 0 /sdcard/release-choices.mp4 & echo $! > /sdcard/choices-recording.pid; wait' > "$FIXTURE/recording.log" 2>&1 &
else
    adb shell 'screenrecord --time-limit 180 /sdcard/release-choices.mp4 & echo $! > /sdcard/choices-recording.pid; wait' > "$FIXTURE/recording.log" 2>&1 &
fi
recording=$!
trap finish EXIT
.claude/skills/verify-multiprompt-android/scripts/drive.sh "${VERIFY_FEATURE:?}"
