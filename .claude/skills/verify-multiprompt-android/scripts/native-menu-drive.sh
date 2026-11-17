#!/usr/bin/env bash
menu_state() {
    local label=$1 mode=$2 value=$3
    shot "native-menu-$label"
    python3 tools/menu-controls/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-native-menu-$label.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-native-menu-$label.png" \
        "$mode" "$value" "${4:-}" | tee -a "$EVIDENCE/native-menu-state.txt"
}
open_model_menu() {
    if [[ "$1" == header ]]; then
        tap_text verify-session
    else
        tap_desc 'Session actions'
        tap_text 'Change ›'
    fi
    wait_for_text 'Open model picker (/model)'
}
menu_command() {
    local entry=$1 label=$2 command=$3 model=$4 effort=$5 phase=$6 before after attempt
    open_model_menu "$entry"
    before=$(sudo grep -Fxc "VERIFY_RECEIVED:$command" /home/verifyssh/receipts.txt || true)
    tap_text "$label"
    for attempt in {1..30}; do
        after=$(sudo grep -Fxc "VERIFY_RECEIVED:$command" /home/verifyssh/receipts.txt || true)
        if (( after > before )); then break; fi
        sleep 1
    done
    (( after == before + 1 ))
    printf 'VERIFY_RECEIVED:%s\n' "$command" >> "$EVIDENCE/native-menu-commands.txt"
    if [[ "$command" == /model ]]; then wait_for_text 'Received /model.'; fi
    wait_for_text "$model 5.5 · $effort"
    menu_state "$phase" visible "$model 5.5 · $effort"
    menu_state "$phase-draft" draft 'keep this draft'
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/native-menu-$phase-host.txt"
    grep -Fx "$model 5.5 $effort" "$EVIDENCE/native-menu-$phase-host.txt"
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/native-menu-receipts.txt"
}
menu_scroll() {
    local name=$1 target=$2 before attempt x1 y1 x2 y2
    shot "native-menu-$name-before-scroll"
    before="$EVIDENCE/$(printf '%02d' "$step")-native-menu-$name-before-scroll.xml"
    for attempt in {1..8}; do
        dump_ui
        read -r x1 y1 x2 y2 < <(python3 tools/menu-controls/ui.py "$EVIDENCE/current.xml" swipe)
        adb shell input swipe "$x1" "$y1" "$x2" "$y2" 500
        shot "native-menu-$name-scroll-$attempt"
        if python3 tools/menu-controls/ui.py \
            "$EVIDENCE/$(printf '%02d' "$step")-native-menu-$name-scroll-$attempt.xml" \
            "$EVIDENCE/$(printf '%02d' "$step")-native-menu-$name-scroll-$attempt.png" \
            scroll "$target" "$before" >> "$EVIDENCE/native-menu-state.txt"; then return; fi
    done
    return 1
}
: > "$EVIDENCE/native-menu-commands.txt"
menu_state attach-back visible 'Add image'
for model in Opus Haiku Fable; do
    tap_text Model
    tap_text "$model"
    wait_receipt "VERIFY_RECEIVED:/model ${model,,}"
    printf 'VERIFY_RECEIVED:/model %s\n' "${model,,}" >> "$EVIDENCE/native-menu-commands.txt"
    wait_for_text "$model 5.5 · high"
    menu_state "attach-${model,,}" visible "$model 5.5 · high"
    menu_state "attach-${model,,}-draft" draft 'keep this draft'
    tap_desc Attach
    wait_for_text "$model ›"
    menu_state "attach-${model,,}-selected" visible "$model ›"
done
for effort in Low Medium; do
    tap_text Effort
    tap_text "$effort"
    wait_receipt "VERIFY_RECEIVED:/effort ${effort,,}"
    printf 'VERIFY_RECEIVED:/effort %s\n' "${effort,,}" >> "$EVIDENCE/native-menu-commands.txt"
    wait_for_text "Fable 5.5 · ${effort,,}"
    menu_state "attach-${effort,,}" visible "Fable 5.5 · ${effort,,}"
    tap_desc Attach
    wait_for_text "$effort ›"
    menu_state "attach-${effort,,}-selected" visible "$effort ›"
done
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Add image'
menu_state attach-dismissed draft 'keep this draft'
adb shell wm size 1080x1400
open_model_menu header
menu_state header-current current Fable
menu_scroll header 'Set effort: max'
adb shell input keyevent KEYCODE_BACK
menu_state header-dismissed draft 'keep this draft'
tap_desc 'Session actions'
wait_for_text 'Search in chat'
menu_scroll reader Delete
adb shell input keyevent KEYCODE_BACK
menu_state reader-dismissed draft 'keep this draft'
adb shell wm size reset
open_model_menu reader
menu_state reader-current current Fable
tap_text '‹ Back'
wait_for_text 'Search in chat'
menu_state reader-back visible 'Search in chat'
adb shell input keyevent KEYCODE_BACK
for entry in header reader; do
    menu_command "$entry" 'Open model picker (/model)' /model Fable medium "$entry-picker"
    for model in Opus Sonnet Fable; do
        menu_command "$entry" "Use Claude $model" "/model ${model,,}" "$model" medium "$entry-${model,,}"
    done
    for effort in low medium high xhigh max; do
        menu_command "$entry" "Set effort: $effort" "/effort $effort" Fable "$effort" "$entry-$effort"
    done
    menu_command "$entry" 'Set effort: medium' '/effort medium' Fable medium "$entry-restore-medium"
done
menu_command reader 'Use Claude Sonnet' '/model sonnet' Sonnet medium restore-sonnet
menu_command reader 'Set effort: high' '/effort high' Sonnet high restore-high
