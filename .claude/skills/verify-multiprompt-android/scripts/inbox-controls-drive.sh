#!/usr/bin/env bash
inbox_control_state() {
    local name=$1 expected=$2
    shot "$name"
    python3 tools/inbox-controls/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.png" "$expected" > "$EVIDENCE/$name.json"
    cat "$EVIDENCE/$name.json"
}
open_inbox_settings() {
    tap_desc 'Session list actions'
    tap_text Settings
    wait_for_text 'Session list settings'
}
if [[ "$feature" == settings-controls.md ]]; then
    adb shell wm size 1080x1280
    open_inbox_settings
    inbox_control_state settings-top '{"text":["Session list settings","Sign in","Host mapping"],"absent":["Large"]}'
    tap_node text Large reveal
    inbox_control_state settings-scrolled '{"text":["Theme","Default Reader font size","Large"],"absent":["Sign in"]}'
    for theme in Sunlight Dark System; do
        tap_text "$theme"
        inbox_control_state "settings-theme-$theme" "{\"text\":[\"Theme\"],\"selected\":[\"$theme\"]}"
    done
    for font in Small Large Normal; do
        tap_text "$font"
        inbox_control_state "settings-font-$font" "{\"text\":[\"Default Reader font size\"],\"selected\":[\"$font\"]}"
    done
    tap_desc Back
    wait_for_text Inbox
    inbox_control_state settings-back '{"text":["Inbox"],"desc":["Session list actions"],"absent":["Session list settings"]}'
    relaunch_app
    open_inbox_settings
    tap_node text Large reveal
    inbox_control_state settings-retained '{"text":["Theme","Default Reader font size"],"selected":["System","Normal"]}'
    tap_desc Back
    open_inbox_settings
    tap_text 'Dictation key'
    wait_for_text 'Deepgram dictation'
    inbox_control_state settings-dictation '{"text":["Deepgram dictation","Save key","Cancel"]}'
    tap_text Cancel
    wait_for_absence 'Deepgram dictation'
    inbox_control_state settings-dictation-closed '{"text":["Session list settings","Dictation key"],"absent":["Save key"]}'
    tap_text 'Host mapping'
    wait_for_text 'Desktop tmux hosts'
    inbox_control_state mapping-open '{"text":["Desktop tmux hosts","0 · Hetzner: Not mapped","1 · Contabo UK: Not mapped"]}'
    tap_text '1 · Contabo UK: Not mapped'
    inbox_control_state mapping-slot '{"text":["Automatic","Back"],"absent":["1 · Contabo UK: Not mapped"]}'
    tap_text Back
    inbox_control_state mapping-back '{"text":["Desktop tmux hosts","1 · Contabo UK: Not mapped","Done"],"absent":["Automatic"]}'
    adb shell input keyevent KEYCODE_BACK
    wait_for_absence 'Desktop tmux hosts'
    inbox_control_state mapping-system-back '{"text":["Session list settings","Host mapping"],"absent":["Desktop tmux hosts"]}'
    adb shell wm size 1080x720
    open_inbox_settings
    tap_text 'Host mapping'
    wait_for_text 'Desktop tmux hosts'
    inbox_control_state mapping-scroll-top '{"text":["Desktop tmux hosts","0 · Hetzner: Not mapped"],"absent":["Done"]}'
    tap_node text Done reveal
    inbox_control_state mapping-scroll-bottom '{"text":["Done"],"absent":["0 · Hetzner: Not mapped"]}'
    tap_text Done
    wait_for_absence 'Desktop tmux hosts'
    inbox_control_state mapping-dismissed '{"text":["Session list settings","Host mapping"],"absent":["Desktop tmux hosts"]}'
    adb shell wm size reset
    open_inbox_settings
    tap_text Update
    wait_for_text 'App updates'
    inbox_control_state settings-update '{"text":["App updates","Check now"],"desc":["Open navigation"],"absent":["Session list settings"]}'
    sudo cat /home/verifyssh/commands.log > "$EVIDENCE/settings-refresh-before.txt"
    refresh_deadline=$((SECONDS + 15))
    refresh_tapped=0
    while (( SECONDS < refresh_deadline )); do
        dump_ui || true
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" desc Refresh >/dev/null 2>&1 && tap_desc Refresh; then
            refresh_tapped=1
            break
        fi
        if (( SECONDS >= refresh_deadline )); then break; fi
        sleep 1
    done
    if [[ "$refresh_tapped" != 1 ]]; then
        printf 'Timed out waiting for Refresh\n' >&2
        exit 1
    fi
    wait_for_text 'App updates'
    inbox_control_state settings-refreshed '{"text":["App updates"],"desc":["Open navigation"]}'
    sudo cat /home/verifyssh/commands.log > "$EVIDENCE/settings-refresh-after.txt"
    python3 - "$EVIDENCE/settings-refresh-before.txt" "$EVIDENCE/settings-refresh-after.txt" > "$EVIDENCE/settings-refresh-host.txt" <<'PY'
from pathlib import Path
import sys
before, after = [Path(p).read_text() for p in sys.argv[1:]]
assert after.startswith(before)
new = after[len(before):]
assert 'list-sessions' in new, 'Update navigation Refresh did not reach the SSH host'
for command in new.splitlines():
    if 'list-sessions' in command:
        start = max(0, command.index('list-sessions') - 5)
        print(command[start:start + 1000])
PY
    tap_desc 'Open navigation'
    inbox_control_state update-menu '{"text":["Sessions","✓ Update"],"absent":["Session list settings"]}'
    adb shell input keyevent KEYCODE_BACK
    inbox_control_state update-menu-dismissed '{"text":["App updates"],"absent":["Sessions","✓ Update"]}'
    tap_desc 'Open navigation'
    tap_text '✓ Update'
    inbox_control_state update-selected '{"text":["App updates"],"absent":["✓ Update"]}'
    tap_desc 'Open navigation'
    tap_text Sessions
    wait_for_text Inbox
    inbox_control_state update-sessions '{"text":["Inbox"],"desc":["Session list actions"],"absent":["App updates"]}'
    open_inbox_settings
    tap_desc "Version $EXPECTED_VERSION, open release notes"
    wait_for_text 'App updates'
    inbox_control_state settings-version '{"text":["App updates"],"absent":["Session list settings"]}'
    printf 'SETTINGS_MAPPING_NAVIGATION_CONTROLS_OK\n' | tee "$EVIDENCE/settings-controls-proof.txt"
    if [[ "${BREAK_IT_MODE:-}" == true ]]; then
        adb shell input keyevent KEYCODE_BACK
        wait_for_text Inbox
        tap_text All
        tap_text verify-session
        wait_for_text 'Message verify-session'
        source "$scripts/ai-switch-drive.sh"
    fi
    exit 0
fi
reopen_folder_bar() {
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
    wait_for_text Inbox
    doctor
}
tap_folder() {
    local attempt direction plan action x1 y1 x2 y2
    for attempt in {1..24}; do
        dump_ui
        direction=forward
        if (( attempt > 12 )); then direction=backward; fi
        plan=$(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" reach-text "$1" "$direction" horizontal) || return
        read -r action x1 y1 x2 y2 <<< "$plan"
        if [[ "$action" == tap ]]; then tap_text "$1"; return; fi
        adb shell input swipe "$x1" "$y1" "$x2" "$y2" 500
    done
    printf 'Folder not reachable: %s\n' "$1" >&2
    return 1
}
if [[ "$feature" == inbox-reminders.md ]]; then
    setup_failure=1
    adb shell settings put global http_proxy 127.0.0.1:9
    [[ "$(adb shell settings get global http_proxy | tr -d '\r')" == 127.0.0.1:9 ]]
    adb shell am force-stop "$package"
    adb shell am instrument -w -e class dev.multiprompt.companion.account.FolderBarSeedTest#seedsOverflowingFolders \
        -e mode folder-bar-seed "$package.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' > "$EVIDENCE/folder-bar-seed.txt"
    grep -F 'OK (1 test)' "$EVIDENCE/folder-bar-seed.txt"
    ! grep -F 'FAILURES' "$EVIDENCE/folder-bar-seed.txt"
    setup_failure=0
    relaunch_app
    tap_folder 'Folder 09'
    inbox_control_state folder-bar-selected '{"selected":["Folder 09"]}'
    reopen_folder_bar
    inbox_control_state folder-bar-reopened '{"folder":"Folder 09"}'
    tap_folder All
    inbox_control_state folder-bar-all '{"folder":"All"}'
    tap_folder 'Folder 06'
    inbox_control_state folder-bar-middle '{"folder":"Folder 06"}'
    reopen_folder_bar
    inbox_control_state folder-bar-middle-reopened '{"folder":"Folder 06"}'
    tap_folder All
    printf 'FOLDER_BAR_SELECTED_FULLY_VISIBLE_OK\n' | tee "$EVIDENCE/folder-bar-proof.txt"
    open_inbox_settings
    tap_text 'Sign out'
    wait_for_text 'Use without account (debug)'
    tap_text 'Use without account (debug)'
    wait_for_text Inbox
    tap_text All
fi
switch_inbox_bucket() {
    tap_desc 'Switch session bucket'
    tap_text "$1"
    wait_for_text "$1"
}
hold_inbox_session() {
    local point x y
    tap_node desc 'Robot verify-session' reveal
    point=$(select_node desc 'Robot verify-session') || return
    read -r x y <<< "$point"
    adb shell input swipe "$x" "$y" "$x" "$y" 800
}
find_inbox_session() {
    if ! select_node desc 'Close session search' >/dev/null 2>&1; then
        tap_desc 'Search sessions'
        select_node focused-field '' >/dev/null
        clear_field ''
        adb shell input text verify-session
        dump_ui
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value '' verify-session
        adb shell input keyevent KEYCODE_BACK
    fi
    wait_for_text '1 result across all splits'
    tap_node desc 'Robot verify-session' reveal
}
open_inbox_reminder() {
    find_inbox_session
    hold_inbox_session
    tap_text 'Remind Me…'
    wait_for_text 'Remind me'
}
restore_reminded_session() {
    switch_inbox_bucket Waiting
    tap_node desc 'Robot verify-session' reveal
    inbox_control_state "reminder-$1-waiting" '{"text":["Waiting","verify-session"],"absent":["Remind me"]}'
    hold_inbox_session
    tap_text 'Return to open'
    switch_inbox_bucket Inbox
    tap_node desc 'Robot verify-session' reveal
    inbox_control_state "reminder-$1-restored" '{"text":["Inbox","verify-session"]}'
}
reminder_receipt() {
    local name=$1 preset=$2 after
    after=$(adb shell date +%s | tr -d '\r')
    adb exec-out run-as "$package" cat shared_prefs/session_read_state.xml > "$EVIDENCE/reminder-$name-preferences.xml"
    python3 tools/inbox-controls/ui.py reminder "$EVIDENCE/reminder-$name-preferences.xml" \
        "$reminder_before" "$after" "$reminder_timezone" "$preset" > "$EVIDENCE/reminder-$name-time.json"
    cat "$EVIDENCE/reminder-$name-time.json"
}
reminder_timezone=$(adb shell getprop persist.sys.timezone | tr -d '\r')
reminder_timezone=${reminder_timezone:-Etc/UTC}
sudo cat /home/verifyssh/commands.log > "$EVIDENCE/inbox-refresh-before.txt"
tap_desc 'Session list actions'
tap_text Refresh
find_inbox_session
inbox_control_state inbox-refreshed '{"text":["Inbox","verify-session"],"absent":["Settings","Refresh"]}'
sudo cat /home/verifyssh/commands.log > "$EVIDENCE/inbox-refresh-after.txt"
python3 - "$EVIDENCE/inbox-refresh-before.txt" "$EVIDENCE/inbox-refresh-after.txt" > "$EVIDENCE/inbox-refresh-host.txt" <<'PY'
from pathlib import Path
import sys
before, after = [Path(p).read_text() for p in sys.argv[1:]]
assert after.startswith(before)
new = after[len(before):]
assert 'list-sessions' in new, 'Refresh did not reach the SSH host'
for command in new.splitlines():
    if 'list-sessions' in command:
        start = max(0, command.index('list-sessions') - 5)
        print(command[start:start + 1000])
PY
tap_desc 'Switch session bucket'
inbox_control_state bucket-open '{"text":["Inbox","Waiting","Archive","Unread only"]}'
adb shell input keyevent KEYCODE_BACK
inbox_control_state bucket-dismissed '{"text":["Inbox"],"absent":["Unread only","Archive"]}'
switch_inbox_bucket Waiting
inbox_control_state bucket-waiting '{"text":["Waiting"],"absent_desc":["Robot verify-session"]}'
switch_inbox_bucket Archive
inbox_control_state bucket-archive '{"text":["Archive"],"absent_desc":["Robot verify-session"]}'
switch_inbox_bucket Inbox
inbox_control_state bucket-inbox '{"text":["Inbox"],"desc":["Session list actions"]}'
tap_desc 'Switch session bucket'
tap_text 'Unread only'
inbox_control_state bucket-unread-on '{"text":["Inbox · Unread"],"absent":["Unread only"]}'
tap_desc 'Switch session bucket'
tap_text 'Unread only'
inbox_control_state bucket-unread-off '{"text":["Inbox"],"absent":["Inbox · Unread"]}'
open_inbox_reminder
inbox_control_state reminder-open '{"text":["Remind me","verify-session","In 30 minutes","Cancel","Set reminder"]}'
tap_text Cancel
inbox_control_state reminder-cancelled '{"text":["Inbox"],"desc":["Robot verify-session"],"absent":["Remind me"]}'
open_inbox_reminder
adb shell input keyevent KEYCODE_BACK
inbox_control_state reminder-dismissed '{"text":["Inbox"],"desc":["Robot verify-session"],"absent":["Remind me"]}'
adb shell wm size 1080x1280
open_inbox_reminder
inbox_control_state reminder-scroll-top '{"text":["In 30 minutes"],"absent":["Custom time"]}'
tap_node field 'Custom time' reveal
inbox_control_state reminder-scroll-bottom '{"text":["Custom time"],"absent":["In 30 minutes"]}'
tap_text Cancel
adb shell wm size reset
for preset in later 1800 3600 7200 18000 day1 day2; do
    reminder_before=$(adb shell date +%s | tr -d '\r')
    open_inbox_reminder
    case "$preset" in
        later)
            dump_ui
            label=$(python3 - "$EVIDENCE/current.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
labels = [n.get('text') for n in ET.parse(sys.argv[1]).getroot().iter('node') if n.get('text', '').startswith('Later today · ')]
assert len(labels) == 1
print(labels[0])
PY
)
            ;;
        1800) label='In 30 minutes' ;;
        3600) label='In 1 hour' ;;
        7200) label='In 2 hours' ;;
        18000) label='In 5 hours' ;;
        day1) label='Tomorrow · 9:00 AM' ;;
        day2) label='In 2 days · 9:00 AM' ;;
    esac
    tap_text "$label"
    wait_for_absence 'Remind me'
    reminder_receipt "$preset" "$preset"
    restore_reminded_session "$preset"
done
open_inbox_reminder
fill 'Custom time' 'in 3 hours'
inbox_control_state reminder-custom-draft '{"text":["Remind me","in 3 hours","Set reminder"]}'
reminder_before=$(adb shell date +%s | tr -d '\r')
tap_text 'Set reminder'
wait_for_absence 'Remind me'
reminder_receipt custom 10800
restore_reminded_session custom
open_inbox_reminder
fill 'Custom time' 'in 4 hours'
tap_node field 'Custom time'
reminder_before=$(adb shell date +%s | tr -d '\r')
adb shell input keyevent KEYCODE_ENTER
wait_for_absence 'Remind me'
reminder_receipt keyboard 14400
restore_reminded_session keyboard
printf 'INBOX_BUCKET_REMINDER_CONTROLS_OK\n' | tee "$EVIDENCE/inbox-reminders-proof.txt"
if [[ "${BREAK_IT_MODE:-}" == true ]]; then
    tap_desc 'Close session search'
    tap_text All
    tap_text verify-session
    wait_for_text 'Message verify-session'
    source "$scripts/ai-switch-drive.sh"
fi
