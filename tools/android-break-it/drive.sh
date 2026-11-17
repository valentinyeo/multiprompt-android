#!/usr/bin/env bash
break_it_started=false
break_it_network=false
break_it_checkpoint() {
    local stress=$1 text=$2
    shot "break-it-$stress"
    cp "$EVIDENCE/current.xml" "$EVIDENCE/$stress.xml"
    cp "$EVIDENCE/$(printf '%02d' "$step")-break-it-$stress.png" "$EVIDENCE/$stress.png"
    printf '%s\n' "$text" > "$EVIDENCE/$stress.txt"
    printf '{"stress":"%s","passed":true}\n' "$stress" > "$EVIDENCE/$stress.json"
    printf 'BREAK_IT %s PASSED\n' "$stress"
}
break_it_restore() {
    if [[ "$break_it_network" == true ]]; then
        adb emu network speed full
        adb emu network delay none
    fi
    if [[ "$break_it_started" == true ]]; then adb shell wm user-rotation lock 0; fi
}
break_it_draft() {
    [[ "${BREAK_IT_MODE:-}" == true && "$break_it_started" == false && "$1" == Message\ * && -n "$2" ]] || return 0
    local label=$1 draft=${2//%s/ } chat=${1#Message } attempt
    break_it_started=true
    printf 'BREAK_IT rotate START\n'
    adb shell wm user-rotation lock 1
    for attempt in {1..15}; do
        adb shell dumpsys window displays > "$EVIDENCE/rotate-display.txt"
        if grep -q 'mRotation=ROTATION_90' "$EVIDENCE/rotate-display.txt"; then break; fi
        sleep 1
    done
    grep -q 'mRotation=ROTATION_90' "$EVIDENCE/rotate-display.txt"
    wait_for_text "$label"
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value "$label" "$draft"
    break_it_checkpoint rotate "Landscape rotation retained exact unsent draft: $draft"
    adb shell wm user-rotation lock 0
    wait_for_text "$label"
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value "$label" "$draft"
    printf 'BREAK_IT background START\n'
    adb shell input keyevent KEYCODE_HOME
    shot break-it-background-away
    adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
    wait_for_text "$label"
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value "$label" "$draft"
    break_it_checkpoint background "Home and return retained exact unsent draft: $draft"
    printf 'BREAK_IT chat-switch START\n'
    tap_desc Back
    wait_for_text Inbox
    tap_text All
    tap_text verify-breakit-other
    wait_for_text 'Message verify-breakit-other'
    shot break-it-other-chat
    tap_desc Back
    wait_for_text Inbox
    tap_text All
    tap_text "$chat"
    wait_for_text "$label"
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value "$label" "$draft"
    break_it_checkpoint chat-switch "Opened verify-breakit-other and returned to $chat; retained exact draft: $draft"
    printf 'BREAK_IT network START\n'
    break_it_network=true
    adb emu network speed edge
    adb emu network delay 150:300
    adb emu network status | tee "$EVIDENCE/network-status.txt"
    grep -q '150' "$EVIDENCE/network-status.txt"
    grep -q '118400' "$EVIDENCE/network-status.txt"
    dump_ui
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value "$label" "$draft"
    break_it_checkpoint network "EDGE 118400 bits/s upload and 150:300ms delay applied before Send; the rest of the original journey must pass under this throttle. Draft: $draft"
}
break_it_stream_send() {
    [[ "${BREAK_IT_MODE:-}" == true ]] || return 0
    jq -e '.followup_submitted_while_streaming == true and .submitted < .completed' "$1"
    break_it_checkpoint stream-send "$(jq -c '{followup_submitted_while_streaming, submitted, completed}' "$1")"
}
