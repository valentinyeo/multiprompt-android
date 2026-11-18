#!/usr/bin/env bash
control_state() {
    local name=$1 phase=$2 value=${3:-}
    shot "$name"
    python3 tools/control-journeys/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.png" \
        "$phase" "$value" > "$EVIDENCE/$name.json"
    cat "$EVIDENCE/$name.json"
}
open_control_menu() {
    tap_desc 'Session actions'
    tap_text "$1"
}
tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text VERIFY_READY_528
if [[ "$feature" == bot-look.md ]]; then
    adb shell wm size 1080x1280
    open_control_menu 'Bot look'
    wait_for_text Face
    control_state bot-look-top scroll-top
    for face in {1..20}; do
        tap_desc "Face $face"
        control_state "bot-look-face-$face" face "$face"
    done
    for colour in {1..8}; do
        tap_desc "Colour $colour"
        control_state "bot-look-colour-$colour" colour "$colour"
    done
    tap_text Auto
    control_state bot-look-auto auto
    control_state bot-look-bottom scroll-bottom
    adb shell input keyevent KEYCODE_BACK
    wait_for_absence 'Bot look'
    control_state bot-look-dismissed reader verify-session
    adb shell wm size reset
    relaunch_app
    tap_text verify-session
    wait_for_text 'Message verify-session'
    open_control_menu 'Bot look'
    control_state bot-look-retained-face face 20
    control_state bot-look-retained-auto auto
    adb shell input keyevent KEYCODE_BACK
    wait_for_absence 'Bot look'
    adb shell wm size reset
    printf 'BOT_LOOK_CHOICES_SCROLL_DISMISS_OK\n' | tee "$EVIDENCE/bot-look-proof.txt"
    exit 0
fi
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reader-controls-before.txt"
! grep -F 'VERIFY_RECEIVED:/rename' "$EVIDENCE/reader-controls-before.txt"
open_control_menu Rename
wait_for_text 'Rename session'
fill Name VERIFY_CANCELLED_2653
control_state reader-controls-rename-draft rename-draft VERIFY_CANCELLED_2653
tap_text Cancel
wait_for_absence 'Rename session'
control_state reader-controls-rename-cancel reader verify-session
open_control_menu Rename
wait_for_text 'Rename session'
control_state reader-controls-rename-unchanged rename-draft verify-session
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Rename session'
control_state reader-controls-rename-dismiss reader verify-session
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reader-controls-cancelled.txt"
cmp "$EVIDENCE/reader-controls-before.txt" "$EVIDENCE/reader-controls-cancelled.txt"
open_control_menu Rename
wait_for_text 'Rename session'
fill Name VERIFY_RENAMED_2653
control_state reader-controls-rename-save-draft rename-draft VERIFY_RENAMED_2653
tap_text Rename
wait_for_absence 'Rename session'
wait_for_text VERIFY_RENAMED_2653
wait_receipt 'VERIFY_RECEIVED:/rename VERIFY_RENAMED_2653'
control_state reader-controls-renamed reader VERIFY_RENAMED_2653
sudo -u verifyssh tmux display-message -p -t '=verify-session:' '#{session_name}|#{window_name}' > "$EVIDENCE/reader-controls-window.txt"
grep -Fx 'verify-session|VERIFY_RENAMED_2653' "$EVIDENCE/reader-controls-window.txt"
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reader-controls-host.txt"
[[ $(grep -Fxc 'VERIFY_RECEIVED:/rename VERIFY_RENAMED_2653' "$EVIDENCE/reader-controls-host.txt") == 1 ]]
open_control_menu Display
wait_for_text 'Show terminal details'
control_state reader-controls-display display 'Show terminal details'
if select_node contains 'Sunlight mode off' >/dev/null 2>&1; then
    tap_text 'Sunlight mode off'
    wait_for_absence 'Use default font size'
    open_control_menu Display
    wait_for_text 'Sunlight mode on'
fi
tap_text 'Show terminal details'
wait_for_absence 'Use default font size'
control_state reader-controls-details reader VERIFY_RENAMED_2653
open_control_menu Display
wait_for_text 'Hide terminal details'
control_state reader-controls-details-on display 'Hide terminal details'
tap_text 'Hide terminal details'
wait_for_absence 'Use default font size'
open_control_menu Display
wait_for_text 'Show terminal details'
control_state reader-controls-details-off display 'Show terminal details'
tap_text 'Sunlight mode on'
wait_for_absence 'Use default font size'
control_state reader-controls-sunlight reader VERIFY_RENAMED_2653
open_control_menu Display
wait_for_text 'Sunlight mode off'
control_state reader-controls-sunlight-on display 'Sunlight mode off'
tap_text 'Sunlight mode off'
wait_for_absence 'Use default font size'
open_control_menu Display
wait_for_text 'Sunlight mode on'
control_state reader-controls-sunlight-off display 'Sunlight mode on'
adb shell input keyevent KEYCODE_BACK
printf 'READER_RENAME_DISPLAY_CONTROLS_OK\n' | tee "$EVIDENCE/reader-controls-proof.txt"
source "$scripts/reader-menu-drive.sh"
