#!/usr/bin/env bash
break_it_started=false
break_it_network=false
break_it_active=
break_it_checkpoint() {
    local stress=$1 text=$2 passed=${3:-true}
    shot "break-it-$stress"
    cp "$EVIDENCE/current.xml" "$EVIDENCE/$stress.xml"
    cp "$EVIDENCE/$(printf '%02d' "$step")-break-it-$stress.png" "$EVIDENCE/$stress.png"
    printf '%s\n' "$text" > "$EVIDENCE/$stress.txt"
    printf '{"stress":"%s","passed":%s}\n' "$stress" "$passed" > "$EVIDENCE/$stress.json"
    if [[ "$passed" == true ]]; then printf 'BREAK_IT %s PASSED\n' "$stress"; else printf 'BREAK_IT %s FAILED\n' "$stress"; fi
    printf '::endgroup::\n'
    break_it_active=
}
break_it_failure() {
    if [[ -n "$break_it_active" && "$1" != 0 ]]; then
        break_it_checkpoint "$break_it_active" "Checkpoint failed with driver exit $1. The screenshot/XML retain the failed state; driver-transcript.txt retains the assertion." false
    fi
}
break_it_restore() {
    if [[ "$break_it_network" == true ]]; then
        adb emu network speed full
        adb emu network delay none
    fi
    if [[ "$break_it_started" == true ]]; then adb shell wm user-rotation lock 0; fi
}
break_it_require_draft() {
    if [[ "$1" != Message\ * ]]; then wait_for_text "$1"; return; fi
    local attempt
    for attempt in {1..30}; do
        if select_node editor '' >/dev/null 2>&1; then break; fi
        sleep 1
    done
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" editor-value "$1" "$2"
}
break_it_draft() {
    [[ "${BREAK_IT_MODE:-}" == true && "$break_it_started" == false ]] || return 0
    local label=$1 draft=${2//%s/ } chat=${3:-${1#Message }} attempt state
    state="screen: $label"
    if [[ "$label" == Message\ * ]]; then state="exact unsent draft: $draft"; fi
    break_it_started=true
    break_it_active=rotate
    printf '::group::Break-it 1/5 Rotate during draft\n'
    adb shell wm user-rotation lock 1
    for attempt in {1..15}; do
        adb shell dumpsys window displays > "$EVIDENCE/rotate-display.txt"
        if grep -q 'mRotation=ROTATION_90' "$EVIDENCE/rotate-display.txt"; then break; fi
        sleep 1
    done
    grep -q 'mRotation=ROTATION_90' "$EVIDENCE/rotate-display.txt"
    break_it_require_draft "$label" "$draft"
    break_it_checkpoint rotate "Landscape rotation retained $state"
    adb shell wm user-rotation lock 0
    break_it_require_draft "$label" "$draft"
    break_it_active=background
    printf '::group::Break-it 2/5 Background and return\n'
    adb shell input keyevent KEYCODE_HOME
    shot break-it-background-away
    adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
    break_it_require_draft "$label" "$draft"
    break_it_checkpoint background "Home and return retained $state"
    if [[ "$label" == Message\ * ]]; then
        break_it_active=chat-switch
        printf '::group::Break-it 3/5 Switch chats and back\n'
        tap_desc Back
        wait_for_text Inbox
        tap_text All
        break_it_open_chat verify-breakit-other 'Message verify-breakit-other'
        shot break-it-other-chat
        tap_desc Back
        wait_for_text Inbox
        tap_text All
        break_it_open_chat "$chat" "$draft"
        break_it_require_draft "$label" "$draft"
        break_it_checkpoint chat-switch "Opened verify-breakit-other and returned to $chat; retained exact draft: $draft"
    fi
    break_it_active=network
    printf '::group::Break-it 4/5 Throttle network\n'
    break_it_network=true
    adb emu network speed 128:128
    adb emu network delay 150:300
    adb emu network status | tee "$EVIDENCE/network-status.txt"
    grep -Eq 'download speed: +128000 bits/s' "$EVIDENCE/network-status.txt"
    grep -Eq 'upload speed: +128000 bits/s' "$EVIDENCE/network-status.txt"
    grep -Eq 'minimum latency: +150 ms' "$EVIDENCE/network-status.txt"
    grep -Eq 'maximum latency: +300 ms' "$EVIDENCE/network-status.txt"
    break_it_require_draft "$label" "$draft"
    break_it_checkpoint network "128000 bits/s upload/download and 150:300ms delay applied; the rest of the original journey must pass under this throttle. Screen: $label. Draft: $draft"
}
break_it_screen() {
    [[ "${BREAK_IT_MODE:-}" == true && "$break_it_started" == false ]] || return 0
    if node -e 'const p = require("./.github/scripts/pstack-verifier/break-it.cjs"); process.exit(p.requiredStresses(process.argv[1]).includes("chat-switch") ? 1 : 0)' "$feature"; then
        break_it_draft "$1" ''
    fi
}
break_it_open_chat() {
    if select_node desc 'Close session search' >/dev/null 2>&1; then tap_desc 'Close session search'; fi
    tap_desc 'Search sessions'
    fill 'Search all sessions' "$1"
    wait_for_text '1 result across all splits'
    tap_desc "Robot $1"
    wait_for_text "$2"
}
break_it_ready() {
    [[ "${BREAK_IT_MODE:-}" == true && "$break_it_started" == false ]] || return 0
    setup_failure=1
    break_it_open_chat live-pi_verify-breakit-probe BREAK_IT_READY
    setup_failure=0
    fill 'Message verify-breakit-probe' BREAK_IT_DRAFT_2679
    break_it_draft 'Message verify-breakit-probe' BREAK_IT_DRAFT_2679 live-pi_verify-breakit-probe
    break_it_active=stream-send
    printf '::group::Break-it 5/5 Send while a reply streams\n'
    fill 'Message verify-breakit-probe' BREAK_IT_STREAM
    tap_desc 'Send prompt'
    wait_for_text BREAK_IT_STREAM_REPLY
    shot break-it-streaming
    fill 'Message verify-breakit-probe' BREAK_IT_FOLLOWUP
    tap_desc 'Send prompt'
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/breakit-fixture.py release
    wait_for_text BREAK_IT_FOLLOWUP_REPLY
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/breakit-fixture.py proof > "$EVIDENCE/stream-send-host.json"
    break_it_stream_send "$EVIDENCE/stream-send-host.json"
    tap_desc Back
    wait_for_text Inbox
    tap_text All
    if select_node desc 'Close session search' >/dev/null 2>&1; then tap_desc 'Close session search'; fi
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/breakit-fixture.py stop
}
break_it_stream_send() {
    [[ "${BREAK_IT_MODE:-}" == true ]] || return 0
    break_it_active=stream-send
    jq -e '.followup_submitted_while_streaming == true and .submitted < .completed' "$1"
    break_it_checkpoint stream-send "$(jq -c '{followup_submitted_while_streaming, submitted, completed}' "$1")"
}
