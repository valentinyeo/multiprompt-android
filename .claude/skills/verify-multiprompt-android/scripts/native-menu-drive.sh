#!/usr/bin/env bash
menu_state() {
    local label=$1 mode=$2 value=$3 xml before=${4:-}
    shot "native-menu-$label"
    xml="$EVIDENCE/$(printf '%02d' "$step")-native-menu-$label.xml"
    case "$mode" in
        visible)
            python3 "$scripts/mpa" read --xml "$xml" --select visible --value "$value" --extra enabled
            ;;
        reply)
            python3 "$scripts/mpa" read --xml "$xml" --select visible --value "$value"
            ;;
        draft)
            python3 "$scripts/mpa" read --xml "$xml" --select draft --value "$value"
            ;;
        current)
            python3 "$scripts/mpa" read --xml "$xml" --select current --value "$value" --extra disabled
            ;;
        scroll)
            python3 "$scripts/mpa" read --xml "$xml" --select visible --value "$value" --extra enabled --before "$before"
            ;;
    esac | tee -a "$EVIDENCE/native-menu-state.txt"
}
open_model_menu() {
    tap_desc 'Session actions'
    tap_text 'Change ›'
    wait_for_text 'Open model picker (/model)'
}
tap_menu_receipt() {
    local label=$1 command=$2 before after attempt retry
    before=$(sudo grep -Fxc "VERIFY_RECEIVED:$command" /home/verifyssh/receipts.txt || true)
    for retry in 1 2; do
        tap_text "$label"
        for attempt in {1..30}; do
            after=$(sudo grep -Fxc "VERIFY_RECEIVED:$command" /home/verifyssh/receipts.txt || true)
            if (( after > before )); then
                (( after == before + 1 ))
                return
            fi
            sleep 1
        done
        if [[ "$retry" == 1 ]] && select_node text "$label" >/dev/null 2>&1; then
            printf 'Native menu tap did not send %s; retrying the visible row once\n' "$command" >&2
        else
            break
        fi
    done
    printf 'NATIVE_MENU_RECEIPT_MISSING: VERIFY_RECEIVED:%s\n' "$command" >&2
    return 1
}
menu_command() {
    local label=$1 command=$2 model=$3 effort=$4 phase=$5
    open_model_menu
    tap_menu_receipt "$label" "$command"
    printf 'VERIFY_RECEIVED:%s\n' "$command" >> "$EVIDENCE/native-menu-commands.txt"
    scroll_to_newest
    wait_for_text "Received $command. Claude $model 5.5 $effort"
    menu_state "$phase" reply "Received $command. Claude $model 5.5 $effort"
    menu_state "$phase-draft" draft 'keep this draft'
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/native-menu-$phase-host.txt"
    grep -Fx "$model 5.5 $effort" "$EVIDENCE/native-menu-$phase-host.txt"
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/native-menu-receipts.txt"
    open_model_menu
    menu_state "$phase-current" current "$model 5.5 · $effort"
    adb shell input keyevent KEYCODE_BACK
}
menu_scroll() {
    local name=$1 target=$2 before attempt
    shot "native-menu-$name-before-scroll"
    before="$EVIDENCE/$(printf '%02d' "$step")-native-menu-$name-before-scroll.xml"
    for attempt in {1..8}; do
        dump_ui
        python3 "$scripts/mpa" swipe --xml "$EVIDENCE/current.xml"
        shot "native-menu-$name-scroll-$attempt"
        if python3 "$scripts/mpa" read \
            --xml "$EVIDENCE/$(printf '%02d' "$step")-native-menu-$name-scroll-$attempt.xml" \
            --select visible --value "$target" --extra enabled --before "$before" >> "$EVIDENCE/native-menu-state.txt"; then return; fi
    done
    return 1
}
: > "$EVIDENCE/native-menu-commands.txt"
menu_state attach-back visible 'Add image'
for model in Opus Haiku Fable; do
    tap_text Model Opus
    tap_menu_receipt "$model" "/model ${model,,}"
    printf 'VERIFY_RECEIVED:/model %s\n' "${model,,}" >> "$EVIDENCE/native-menu-commands.txt"
    scroll_to_newest
    wait_for_text "Received /model ${model,,}. Claude $model 5.5 high"
    menu_state "attach-${model,,}" reply "Received /model ${model,,}. Claude $model 5.5 high"
    menu_state "attach-${model,,}-draft" draft 'keep this draft'
    tap_desc Attach
    wait_for_text "$model ›"
    menu_state "attach-${model,,}-selected" visible "$model ›"
done
for effort in Low Medium; do
    tap_text Effort Low
    tap_menu_receipt "$effort" "/effort ${effort,,}"
    printf 'VERIFY_RECEIVED:/effort %s\n' "${effort,,}" >> "$EVIDENCE/native-menu-commands.txt"
    scroll_to_newest
    wait_for_text "Received /effort ${effort,,}. Claude Fable 5.5 ${effort,,}"
    menu_state "attach-${effort,,}" reply "Received /effort ${effort,,}. Claude Fable 5.5 ${effort,,}"
    tap_desc Attach
    wait_for_text "$effort ›"
    menu_state "attach-${effort,,}-selected" visible "$effort ›"
done
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Add image'
menu_state attach-dismissed draft 'keep this draft'
adb shell wm size 1080x1400
tap_desc 'Session actions'
wait_for_text 'Search in chat'
menu_scroll reader Delete
adb shell input keyevent KEYCODE_BACK
menu_state reader-dismissed draft 'keep this draft'
adb shell wm size reset
open_model_menu
menu_state reader-current current Fable
tap_text '‹ Back'
wait_for_text 'Search in chat'
menu_state reader-back visible 'Search in chat'
adb shell input keyevent KEYCODE_BACK
menu_command 'Open model picker (/model)' /model Fable medium reader-picker
for model in Opus Sonnet Fable; do
    menu_command "Use Claude $model" "/model ${model,,}" "$model" medium "reader-${model,,}"
done
for effort in low medium high xhigh max; do
    menu_command "Set effort: $effort" "/effort $effort" Fable "$effort" "reader-$effort"
done
menu_command 'Use Claude Sonnet' '/model sonnet' Sonnet max restore-sonnet
menu_command 'Set effort: high' '/effort high' Sonnet high restore-high
