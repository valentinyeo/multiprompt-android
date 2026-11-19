#!/usr/bin/env bash
: "${FIXTURE:?}"
: "${EVIDENCE:?}"
RECORDER_STOP_SECONDS="${RECORDER_STOP_SECONDS:-60}"
RECORDER_LIMIT_SECONDS="${RECORDER_LIMIT_SECONDS:-780}"
RECORDER_REMOTE=/sdcard/release-journey.mp4

recorder_start() {
    local attempt pid
    timeout "$RECORDER_LIMIT_SECONDS" adb shell "screenrecord --time-limit 0 --bit-rate 2000000 $RECORDER_REMOTE & echo \$!; wait" > "$FIXTURE/recorder.pid" 2> "$EVIDENCE/recording.log" &
    RECORDER_CLIENT=$!
    RECORDER_DEVICE=
    for attempt in {1..30}; do
        pid=$(head -1 "$FIXTURE/recorder.pid" | tr -d '\r')
        if [[ "$pid" =~ ^[0-9]+$ ]]; then
            RECORDER_DEVICE=$pid
            break
        fi
        sleep 0.2
    done
    [[ "$RECORDER_DEVICE" =~ ^[0-9]+$ ]]
    timeout 30 adb shell kill -0 "$RECORDER_DEVICE"
}

recorder_stop() {
    local code=0 watchdog
    [[ "${RECORDER_DEVICE:-}" =~ ^[0-9]+$ ]] || return 1
    timeout 30 adb shell kill -2 "$RECORDER_DEVICE" || code=1
    if kill -0 "$RECORDER_CLIENT" 2>/dev/null; then
        (
            sleep "$RECORDER_STOP_SECONDS"
            printf 'release recorder client still running after %ss; killing it\n' "$RECORDER_STOP_SECONDS" >&2
            kill -9 "$RECORDER_CLIENT" 2>/dev/null
        ) &
        watchdog=$!
        wait "$RECORDER_CLIENT" || code=1
        kill "$watchdog" 2>/dev/null || true
        wait "$watchdog" 2>/dev/null || true
    else
        wait "$RECORDER_CLIENT" || code=1
    fi
    return "$code"
}

recorder_pull() {
    timeout 120 adb pull "$RECORDER_REMOTE" "$EVIDENCE/release-journey.mp4"
}
