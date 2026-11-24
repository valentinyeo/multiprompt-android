#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../recorder-guard.sh"
RECORDER_STOP_SECONDS="${RECORDER_STOP_SECONDS:-60}"
bash tools/release-choices/seed.sh
if [[ "${VERIFY_FEATURE:-}" == session-gestures.md ]]; then
    trap 'kill "$(cat "$FIXTURE/offline-proxy.pid")"' EXIT
    .claude/skills/verify-multiprompt-android/scripts/drive.sh "$VERIFY_FEATURE"
    exit 0
fi
finish() {
    code=$?
    trap - EXIT
    set +e
    local device
    device=$(adb shell cat /sdcard/choices-recording.pid | tr -d '\r')
    if [[ "${VERIFY_FEATURE:-}" == session-favorites.md || "${VERIFY_FEATURE:-}" == chat-queued.md || "${VERIFY_FEATURE:-}" == session-routing.md ]]; then
        adb shell kill -0 "$device" || code=1
    fi
    if [[ "$device" =~ ^[0-9]+$ ]]; then
        adb shell kill -2 "$device"
    fi
    if ! recorder_wait_client "$recording" "$RECORDER_STOP_SECONDS" choices; then
        code=1
        if [[ "$device" =~ ^[0-9]+$ ]]; then
            adb shell kill -9 "$device"
        fi
    fi
    if [[ "${VERIFY_FEATURE:-}" == session-favorites.md || "${VERIFY_FEATURE:-}" == chat-queued.md || "${VERIFY_FEATURE:-}" == session-routing.md ]]; then
        adb pull /sdcard/release-choices.mp4 "$EVIDENCE/release-choices.mp4" || code=1
        test -s "$EVIDENCE/release-choices.mp4" || code=1
    else
        adb pull /sdcard/release-choices.mp4 "$EVIDENCE/release-choices.mp4"
    fi
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
if [[ "${VERIFY_FEATURE:-}" == session-favorites.md || "${VERIFY_FEATURE:-}" == chat-queued.md || "${VERIFY_FEATURE:-}" == session-routing.md ]]; then
    adb shell 'screenrecord --time-limit 0 /sdcard/release-choices.mp4 & echo $! > /sdcard/choices-recording.pid; wait' > "$FIXTURE/recording.log" 2>&1 &
else
    adb shell 'screenrecord --time-limit 180 /sdcard/release-choices.mp4 & echo $! > /sdcard/choices-recording.pid; wait' > "$FIXTURE/recording.log" 2>&1 &
fi
recording=$!
trap finish EXIT
.claude/skills/verify-multiprompt-android/scripts/drive.sh "${VERIFY_FEATURE:?}"
