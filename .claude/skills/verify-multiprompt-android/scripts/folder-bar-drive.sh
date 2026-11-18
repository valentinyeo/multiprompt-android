#!/usr/bin/env bash
folder_bar_state() {
    local name=$1 expected=$2
    shot "$name"
    python3 tools/inbox-controls/ui.py "$EVIDENCE/$(printf '%02d' "$step")-$name.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.png" "$expected" > "$EVIDENCE/$name.json"
    cat "$EVIDENCE/$name.json"
}
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
folder_bar_state folder-bar-selected '{"selected":["Folder 09"]}'
reopen_folder_bar
folder_bar_state folder-bar-reopened '{"folder":"Folder 09"}'
tap_folder All
folder_bar_state folder-bar-all '{"folder":"All"}'
tap_folder 'Folder 06'
folder_bar_state folder-bar-middle '{"folder":"Folder 06"}'
reopen_folder_bar
folder_bar_state folder-bar-middle-reopened '{"folder":"Folder 06"}'
tap_folder All
printf 'FOLDER_BAR_SELECTED_FULLY_VISIBLE_OK\n' | tee "$EVIDENCE/folder-bar-proof.txt"
tap_desc 'Session list actions'
tap_text Settings
wait_for_text 'Session list settings'
tap_text 'Sign out'
wait_for_text 'Use without account (debug)'
tap_text 'Use without account (debug)'
wait_for_text Inbox
tap_text All
if [[ "${BREAK_IT_MODE:-}" == true ]]; then
    tap_text verify-session
    wait_for_text 'Message verify-session'
    source "$scripts/ai-switch-drive.sh"
fi
