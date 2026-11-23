#!/usr/bin/env bash
: "${FIXTURE:?}"
: "${EVIDENCE:?}"
source "$(dirname "${BASH_SOURCE[0]}")/recorder-guard.sh"
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
    if ! [[ "$RECORDER_DEVICE" =~ ^[0-9]+$ ]] || ! timeout 30 adb shell kill -0 "$RECORDER_DEVICE"; then
        recorder_reap || true
        return 1
    fi
}

recorder_stop() {
    local code=0
    if ! [[ "${RECORDER_DEVICE:-}" =~ ^[0-9]+$ ]]; then
        recorder_reap || true
        return 1
    fi
    timeout 30 adb shell kill -2 "$RECORDER_DEVICE" || code=1
    recorder_wait_client "${RECORDER_CLIENT:-}" "$RECORDER_STOP_SECONDS" release || code=1
    if (( code != 0 )); then
        timeout 30 adb shell kill -9 "$RECORDER_DEVICE" >/dev/null 2>&1 || true
    fi
    RECORDER_DEVICE=
    RECORDER_CLIENT=
    return "$code"
}

recorder_reap() {
    local code=0
    if [[ "${RECORDER_DEVICE:-}" =~ ^[0-9]+$ ]]; then
        timeout 30 adb shell kill -9 "$RECORDER_DEVICE" >/dev/null 2>&1 || code=1
    fi
    RECORDER_DEVICE=
    recorder_wait_client "${RECORDER_CLIENT:-}" "$RECORDER_STOP_SECONDS" release || code=1
    RECORDER_CLIENT=
    return "$code"
}

recorder_pull() {
    timeout 120 adb pull "$RECORDER_REMOTE" "$EVIDENCE/release-journey.mp4"
}
