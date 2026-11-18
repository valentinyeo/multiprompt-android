#!/usr/bin/env bash
account_control_state() {
    local name=$1 expected=$2
    shot "$name"
    python3 tools/account-controls/proof.py \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-$name.png" "$expected" > "$EVIDENCE/$name.json"
    cat "$EVIDENCE/$name.json"
}
account_layout_receipt() {
    local name=$1 expected=$2 attempt
    for attempt in {1..30}; do
        sudo cat /home/verifyssh/account-controls-layout.json > "$EVIDENCE/$name-layout.json"
        if python3 tools/account-controls/proof.py layout "$EVIDENCE/$name-layout.json" "$expected" > "$EVIDENCE/$name-host.json"; then
            cat "$EVIDENCE/$name-host.json"
            return
        fi
        sleep 1
    done
    return 1
}
account_inbox_menu() {
    tap_desc 'Session list actions'
    tap_text "$1"
}
account_hold_session() {
    local name=${1:-verify-session} point x y
    tap_node desc "Robot $name" reveal
    point=$(select_node desc "Robot $name") || return
    read -r x y <<< "$point"
    adb shell input swipe "$x" "$y" "$x" "$y" 800
}
account_open_move() {
    account_hold_session
    tap_text 'Move to workspace'
    wait_for_text 'Move to'
}
account_open_selection() {
    account_hold_session
    tap_text 'Select bots'
    wait_for_text '1 selected'
}
account_open_group() {
    account_hold_session
    tap_text 'Move to group'
    wait_for_text 'No group'
}
account_new_group() {
    account_open_group
    tap_text 'New group'
    wait_for_text 'New group'
}
account_rename_group() {
    hold_text "${1^^}"
    wait_for_text 'Rename group'
}
account_inbox_menu Settings
tap_text 'Sign in'
wait_for_text 'Continue as fixture account'
shot account-controls-browser
tap_text 'Continue as fixture account'
wait_for_text 'Switch / renew login'
account_control_state account-signed-in '{"text":["Session list settings","Switch / renew login","Host mapping"]}'
tap_text 'Host mapping'
wait_for_text 'Desktop tmux hosts'
tap_text '1 · Contabo UK: Not mapped'
tap_text 'Verify · verifyssh@10.0.2.2'
tap_text Done
tap_desc Back
wait_for_text Inbox
tap_text Workshop
tap_node desc 'Robot verify-session' reveal
account_control_state account-workshop '{"text":["Inbox","ORIGINAL","OTHER"],"desc":["Robot verify-session","Robot pin-00"],"selected":["Workshop"]}'
account_layout_receipt account-initial '{"tabs":{"verify-session":{"workspace":"Workshop","group":"Original"},"pin-00":{"workspace":"Workshop","group":"Other"}}}'
if [[ "$feature" == workspace-controls.md ]]; then
    account_inbox_menu 'New workspace'
    account_control_state workspace-new-open '{"text":["New workspace","Name","Create","Cancel"]}'
    fill Name Discarded
    account_control_state workspace-new-draft '{"text":["New workspace","Discarded"]}'
    tap_text Cancel
    account_control_state workspace-new-cancel '{"text":["Inbox"],"absent":["New workspace","Discarded"]}'
    account_inbox_menu 'New workspace'
    adb shell input keyevent KEYCODE_BACK
    account_control_state workspace-new-dismiss '{"text":["Inbox"],"absent":["New workspace"]}'
    account_layout_receipt workspace-cancelled '{"absent":["Discarded"]}'
    account_inbox_menu 'New workspace'
    fill Name Review
    tap_text Create
    wait_for_absence 'New workspace'
    tap_node text Review reveal
    account_control_state workspace-created '{"text":["Inbox","Review"],"absent":["New workspace"]}'
    account_layout_receipt workspace-created '{"contains":["Review"],"absent":["Discarded"]}'
    adb shell wm size 1080x1280
    account_inbox_menu 'Arrange splits'
    account_control_state workspace-arrange-top '{"text":["Arrange splits","Review"],"absent":["Workshop"]}'
    tap_node text Archive reveal Workshop
    account_control_state workspace-arrange-scrolled '{"text":["Arrange splits","Workshop"],"absent":["Review"]}'
    tap_text Archive Workshop
    tap_node text Restore reveal Workshop
    wait_for_text 'ARCHIVED WORKSPACES'
    account_control_state workspace-archived '{"text":["ARCHIVED WORKSPACES","Workshop","Restore"]}'
    tap_text Restore Workshop
    wait_for_absence 'ARCHIVED WORKSPACES'
    tap_node text Archive reveal Workshop
    account_control_state workspace-restored '{"text":["Arrange splits","Workshop"],"absent":["Restore","ARCHIVED WORKSPACES"]}'
    adb shell input keyevent KEYCODE_BACK
    account_control_state workspace-arrange-dismiss '{"text":["Inbox"],"absent":["Arrange splits"]}'
    adb shell wm size reset
    account_inbox_menu 'Arrange splits'
    tap_node text '↑' reveal Studio
    tap_text '↑' Studio
    account_control_state workspace-moved-up '{"text":["Arrange splits","Studio"]}'
    account_layout_receipt workspace-moved-up '{"order":["Workshop","North","Studio","South","East","West","Archiveable","Review"]}'
    tap_text '↓' Studio
    account_control_state workspace-moved-down '{"text":["Arrange splits","Studio"]}'
    account_layout_receipt workspace-moved-down '{"order":["Workshop","Studio","North","South","East","West","Archiveable","Review"]}'
    tap_text 'Automatic order'
    account_control_state workspace-automatic '{"text":["Arrange splits","Studio"]}'
    account_layout_receipt workspace-automatic '{"order":["Workshop","Studio","North","South","East","West","Archiveable","Review"]}'
    tap_text Done
    account_control_state workspace-arrange-done '{"text":["Inbox"],"absent":["Arrange splits"]}'
    tap_text Workshop
    account_open_move
    account_control_state workspace-move-open '{"text":["Move to","Workshop","Studio","Cancel"]}'
    tap_text Cancel
    account_control_state workspace-move-cancel '{"text":["Inbox"],"desc":["Robot verify-session"],"absent":["Move to"]}'
    account_open_move
    adb shell input keyevent KEYCODE_BACK
    account_control_state workspace-move-dismiss '{"text":["Inbox"],"desc":["Robot verify-session"],"absent":["Move to"]}'
    account_layout_receipt workspace-move-unchanged '{"tabs":{"verify-session":{"workspace":"Workshop"}}}'
    account_open_move
    tap_text Studio
    account_layout_receipt workspace-single-moved '{"tabs":{"verify-session":{"workspace":"Studio"},"pin-00":{"workspace":"Workshop"}}}'
    account_control_state workspace-single-left '{"text":["Inbox"],"absent_desc":["Robot verify-session"],"desc":["Robot pin-00"]}'
    tap_text Studio
    tap_node desc 'Robot verify-session' reveal
    account_control_state workspace-single-arrived '{"text":["Inbox"],"selected":["Studio"],"desc":["Robot verify-session"],"absent_desc":["Robot pin-00"]}'
    account_open_move
    tap_text Workshop
    account_layout_receipt workspace-single-returned '{"tabs":{"verify-session":{"workspace":"Workshop"}}}'
    tap_text Workshop
    account_open_selection
    account_control_state workspace-selection-open '{"text":["1 selected","Move to…","Cancel"]}'
    tap_desc 'Robot pin-00'
    account_control_state workspace-selected-two '{"text":["2 selected","Move to…"]}'
    tap_desc 'Robot pin-00'
    account_control_state workspace-deselected-one '{"text":["1 selected","Move to…"]}'
    tap_text Cancel
    account_control_state workspace-selection-cancel '{"text":["Inbox"],"absent":["1 selected","Move to…"]}'
    account_open_selection
    tap_desc 'Robot pin-00'
    tap_text 'Move to…'
    account_control_state workspace-bulk-open '{"text":["Move to","Studio","Cancel"]}'
    tap_text Cancel
    account_control_state workspace-bulk-cancel '{"text":["2 selected","Move to…"],"absent":["Move to"]}'
    tap_text 'Move to…'
    adb shell input keyevent KEYCODE_BACK
    account_control_state workspace-bulk-dismiss '{"text":["2 selected","Move to…"],"absent":["Move to"]}'
    account_layout_receipt workspace-bulk-unchanged '{"tabs":{"verify-session":{"workspace":"Workshop"},"pin-00":{"workspace":"Workshop"}}}'
    tap_text 'Move to…'
    tap_text Studio
    account_layout_receipt workspace-bulk-moved '{"tabs":{"verify-session":{"workspace":"Studio"},"pin-00":{"workspace":"Studio"}}}'
    account_control_state workspace-bulk-left '{"text":["This split is clear"],"absent":["2 selected","Move to…"],"absent_desc":["Robot verify-session","Robot pin-00"]}'
    tap_text Studio
    tap_node desc 'Robot verify-session' reveal
    account_control_state workspace-bulk-arrived '{"text":["Inbox"],"selected":["Studio"],"desc":["Robot verify-session","Robot pin-00"]}'
    relaunch_app
    tap_text Studio
    tap_node desc 'Robot verify-session' reveal
    account_control_state workspace-retained '{"text":["Inbox"],"selected":["Studio"],"desc":["Robot verify-session","Robot pin-00"]}'
    printf 'WORKSPACE_CONTROLS_OK\n' | tee "$EVIDENCE/workspace-controls-proof.txt"
    exit 0
fi
account_open_group
account_control_state group-move-open '{"text":["Move to group","Original","Other","New group","No group","Cancel"]}'
tap_text Cancel
account_control_state group-move-cancel '{"text":["ORIGINAL","OTHER"],"absent":["No group","Cancel"]}'
account_open_group
adb shell input keyevent KEYCODE_BACK
account_control_state group-move-dismiss '{"text":["ORIGINAL","OTHER"],"absent":["No group"]}'
account_new_group
fill Name Discarded
account_control_state group-new-draft '{"text":["New group","Discarded","Create"]}'
tap_text Cancel
account_control_state group-new-cancel '{"text":["ORIGINAL","OTHER"],"absent":["New group","Discarded"]}'
account_new_group
adb shell input keyevent KEYCODE_BACK
account_control_state group-new-dismiss '{"text":["ORIGINAL","OTHER"],"absent":["New group"]}'
account_layout_receipt group-unchanged '{"tabs":{"verify-session":{"group":"Original"},"pin-00":{"group":"Other"}},"no_group":["Discarded"]}'
account_new_group
fill Name Focus
tap_text Create
wait_for_absence 'New group'
wait_for_text Focus
account_control_state group-created '{"text":["FOCUS","OTHER"],"absent":["ORIGINAL","New group"]}'
account_layout_receipt group-created '{"tabs":{"verify-session":{"group":"Focus"},"pin-00":{"group":"Other"}}}'
account_rename_group Focus
account_control_state group-rename-open '{"text":["Rename group","Focus","Ungroup","Rename","Cancel"]}'
fill Name Cancelled
account_control_state group-rename-draft '{"text":["Rename group","Cancelled"]}'
tap_text Cancel
account_control_state group-rename-cancel '{"text":["FOCUS","OTHER"],"absent":["Rename group","Cancelled"]}'
account_rename_group Focus
adb shell input keyevent KEYCODE_BACK
account_control_state group-rename-dismiss '{"text":["FOCUS","OTHER"],"absent":["Rename group"]}'
account_layout_receipt group-rename-unchanged '{"tabs":{"verify-session":{"group":"Focus"}},"no_group":["Cancelled"]}'
account_rename_group Focus
fill Name Renamed
tap_text Rename
wait_for_absence 'Rename group'
wait_for_text Renamed
account_control_state group-renamed '{"text":["RENAMED","OTHER"],"absent":["FOCUS","Rename group"]}'
account_layout_receipt group-renamed '{"tabs":{"verify-session":{"group":"Renamed"}}}'
account_rename_group Renamed
fill Name Keyboard
tap_node field Name
adb shell input keyevent KEYCODE_ENTER
wait_for_absence 'Rename group'
wait_for_text Keyboard
account_control_state group-keyboard-renamed '{"text":["KEYBOARD","OTHER"],"absent":["RENAMED","Rename group"]}'
account_layout_receipt group-keyboard-renamed '{"tabs":{"verify-session":{"group":"Keyboard"}}}'
account_open_group
tap_text Other
wait_for_absence 'No group'
account_control_state group-existing-moved '{"text":["OTHER"],"absent":["KEYBOARD","Move to group"]}'
account_layout_receipt group-existing-moved '{"tabs":{"verify-session":{"group":"Other"},"pin-00":{"group":"Other"}}}'
account_open_group
tap_text 'No group'
wait_for_absence 'No group'
account_control_state group-removed '{"text":["OTHER"],"desc":["Robot verify-session","Robot pin-00"],"absent":["Move to group"]}'
account_layout_receipt group-removed '{"tabs":{"verify-session":{"group":""},"pin-00":{"group":"Other"}}}'
account_rename_group Other
tap_text Ungroup
wait_for_absence 'Rename group'
account_control_state group-ungrouped '{"text":["Inbox"],"desc":["Robot verify-session","Robot pin-00"],"absent":["OTHER","Rename group"]}'
account_layout_receipt group-ungrouped '{"tabs":{"verify-session":{"group":""},"pin-00":{"group":""}},"no_group":["Original","Focus","Renamed","Keyboard","Other"]}'
relaunch_app
tap_text Workshop
tap_node desc 'Robot verify-session' reveal
account_control_state group-retained '{"text":["Inbox"],"desc":["Robot verify-session","Robot pin-00"],"absent":["ORIGINAL","FOCUS","RENAMED","KEYBOARD","OTHER"]}'
printf 'GROUP_CONTROLS_OK\n' | tee "$EVIDENCE/group-controls-proof.txt"
