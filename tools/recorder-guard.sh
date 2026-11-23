#!/usr/bin/env bash

recorder_descendants() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null); do
        recorder_descendants "$child"
        printf '%s\n' "$child"
    done
}

recorder_signal_tree() {
    local signal=$1 pid
    for pid in $(recorder_descendants "$2") "$2"; do
        [[ "$pid" =~ ^[0-9]+$ ]] && kill "-$signal" "$pid" 2>/dev/null
    done
    return 0
}

recorder_client_live() {
    [[ "${1:-}" =~ ^[0-9]+$ ]] && kill -0 "$1" 2>/dev/null
}

recorder_wait_client() {
    local client=${1:-} seconds=${2:-60} label=${3:-recorder} ticks=0 status=0
    [[ "$client" =~ ^[0-9]+$ ]] || return 0
    if recorder_client_live "$client"; then
        while recorder_client_live "$client" && (( ticks < seconds * 10 )); do
            sleep 0.1
            ticks=$((ticks + 1))
        done
        if recorder_client_live "$client"; then
            printf '%s client still running after %ss; killing it\n' "$label" "$seconds" >&2
            recorder_signal_tree KILL "$client"
            wait "$client" 2>/dev/null
            return 1
        fi
    fi
    wait "$client" 2>/dev/null
    status=$?
    (( status == 127 )) && status=0
    return "$status"
}
