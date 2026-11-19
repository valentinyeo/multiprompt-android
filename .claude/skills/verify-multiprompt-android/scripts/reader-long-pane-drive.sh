#!/usr/bin/env bash
set -euo pipefail
mpa="$scripts/mpa"

long_pane_capture() {
    local stage=$1 lines
    sudo -u verifyssh tmux capture-pane -p -J -S -5000 -t =live-long-pane-2840 > "$EVIDENCE/long-pane-capture-$stage.txt"
    lines=$(wc -l < "$EVIDENCE/long-pane-capture-$stage.txt")
    if [[ "$lines" -lt 2950 ]] || ! grep -qx LONG_PANE_TAIL_2840 "$EVIDENCE/long-pane-capture-$stage.txt"; then
        printf 'LONG_PANE_FIXTURE_EMPTY: the host pane holds %s lines without its tail marker\n' "$lines" >&2
        return 1
    fi
    printf 'LONG_PANE_HOST_LINES: %s\n' "$lines"
}

long_pane_no_crash() {
    local stage=$1
    timeout 30s adb logcat -d > "$EVIDENCE/logcat-$stage.txt"
    if grep -qE 'FATAL EXCEPTION|Can.t represent a (size|width)' "$EVIDENCE/logcat-$stage.txt"; then
        grep -A 25 -E 'FATAL EXCEPTION|Can.t represent a (size|width)' "$EVIDENCE/logcat-$stage.txt" >&2
        printf 'LONG_PANE_READER_CRASHED: the app died with the reader open at %s\n' "$stage" >&2
        return 1
    fi
}

product_checks=1
long_pane_capture seeded
"$mpa" read --wait-text Inbox
"$mpa" tap text live-long-pane-2840
"$mpa" read --wait-text 'Message live-long-pane-2840' --timeout 180
"$mpa" read --wait-text LONG_PANE_TAIL_2840 --timeout 180
"$mpa" screenshot long-pane-reader
"$mpa" proof --name long-pane-reader --require-text LONG_PANE_TAIL_2840 \
    --note LONG_PANE_READER_SHOWS_NEWEST_OUTPUT_2840 --proof-file "$EVIDENCE/long-pane-reader-proof.txt"
long_pane_no_crash reader
"$mpa" tap text 'Show earlier output'
"$mpa" read --wait-text 'check the release watch at tag v0.1.128' --timeout 120
"$mpa" screenshot long-pane-earlier
"$mpa" proof --name long-pane-earlier --require-text 'check the release watch at tag v0.1.128' \
    --note LONG_PANE_READER_REVEALS_EARLIER_OUTPUT_2840 --proof-file "$EVIDENCE/long-pane-earlier-proof.txt"
long_pane_no_crash earlier
"$mpa" key back
"$mpa" read --wait-text Inbox
"$mpa" launch --force-stop --wait-text Inbox
long_pane_no_crash relaunch
"$mpa" tap text live-long-pane-2840
"$mpa" read --wait-text LONG_PANE_TAIL_2840 --timeout 180
"$mpa" screenshot long-pane-reopened
"$mpa" proof --name long-pane-reopened --require-text LONG_PANE_TAIL_2840 \
    --note LONG_PANE_READER_REOPENS_AFTER_RELAUNCH_2840 --proof-file "$EVIDENCE/long-pane-reopened-proof.txt"
long_pane_no_crash reopened
printf 'LONG_PANE_READER_JOURNEY_OK\n' | tee "$EVIDENCE/long-pane-proof.txt"
