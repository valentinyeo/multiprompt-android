#!/usr/bin/env bash
set -euo pipefail
if [[ "${RECORD_VIDEO:-}" != true ]]; then
    exec "$@"
fi
: "${EVIDENCE:?}"
: "${FIXTURE:?}"
mkdir -p "$EVIDENCE" "$FIXTURE"
source "$(dirname "${BASH_SOURCE[0]}")/recorder-guard.sh"
RECORDER_STOP_SECONDS="${RECORDER_STOP_SECONDS:-60}"
RECORDER_LIMIT_SECONDS="${RECORDER_LIMIT_SECONDS:-240}"
remote=/sdcard/journey-video.mp4
output="$EVIDENCE/journey-video.mp4"
feature="${VERIFY_FEATURE:-}"
own_recorder=false
if [[ "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion || "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion.m3 ]]; then
    if [[ "$feature" != launcher-icon.md ]]; then
        own_recorder=true
    fi
fi
case "$feature" in
    session-routing.md|chat-cancel.md|chat-queued.md|terminal.md|session-gestures.md)
        own_recorder=true
        ;;
esac
copy_existing_video() {
    local best="" best_size=0 candidate size
    shopt -s nullglob
    for candidate in "$EVIDENCE"/*.mp4; do
        [[ "$candidate" == "$output" ]] && continue
        size=$(wc -c < "$candidate" 2>/dev/null || echo 0)
        if (( size > best_size )); then
            best=$candidate
            best_size=$size
        fi
    done
    shopt -u nullglob
    if [[ -n "$best" ]]; then
        cp -f "$best" "$output"
    fi
}
if [[ "$own_recorder" == true ]]; then
    set +e
    "$@"
    code=$?
    set -e
    copy_existing_video || true
    exit "$code"
fi
recorder_client=
recorder_pid=
stop_recorder() {
    if [[ "$recorder_pid" =~ ^[0-9]+$ ]]; then
        timeout 30 adb shell kill -2 "$recorder_pid" >/dev/null 2>&1 || true
    fi
    if [[ "$recorder_client" =~ ^[0-9]+$ ]]; then
        if ! recorder_wait_client "$recorder_client" "$RECORDER_STOP_SECONDS" journey-video; then
            if [[ "$recorder_pid" =~ ^[0-9]+$ ]]; then
                timeout 30 adb shell kill -9 "$recorder_pid" >/dev/null 2>&1 || true
            fi
        fi
    fi
    recorder_client=
    recorder_pid=
    timeout 120 adb pull "$remote" "$output" >/dev/null 2>&1 || true
    timeout 15 adb shell rm -f "$remote" >/dev/null 2>&1 || true
    if [[ -f "$output" && ! -s "$output" ]]; then
        rm -f "$output"
    fi
}
: > "$FIXTURE/journey-video.pid"
timeout "$RECORDER_LIMIT_SECONDS" adb shell "screenrecord --time-limit 180 --bit-rate 1000000 $remote & echo \$!; wait" > "$FIXTURE/journey-video.pid" 2> "$EVIDENCE/journey-video.log" &
recorder_client=$!
for attempt in {1..30}; do
    pid=$(head -1 "$FIXTURE/journey-video.pid" | tr -d '\r')
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
        recorder_pid=$pid
        break
    fi
    sleep 0.2
done
if [[ ! "$recorder_pid" =~ ^[0-9]+$ ]]; then
    printf 'journey-video: screenrecord did not report a device pid; continuing without video\n' >&2
    recorder_wait_client "$recorder_client" 5 journey-video >/dev/null 2>&1 || true
    recorder_client=
fi
set +e
"$@"
code=$?
set -e
stop_recorder
exit "$code"
