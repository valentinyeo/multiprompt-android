#!/usr/bin/env bash
menu_surface() {
    control_state "reader-menu-$1" surface "$2"
}
menu_reader() {
    wait_for_text VERIFY_RENAMED_2653
    menu_surface "$1" '{"text":["VERIFY_RENAMED_2653"],"desc":["Session actions"],"absent":["Pin a page","Deepgram dictation","Screencast2 uploads","Estimate accuracy","Page actions","Computer actions"]}'
}
open_more() {
    open_control_menu More
    wait_for_text 'Release notes'
}
open_pin() {
    open_control_menu "$1"
    wait_for_text 'Pin a page'
}
open_computer_menu() {
    tap_desc 'Computer actions'
    wait_for_text 'Viewer address'
}
open_viewer_address() {
    open_computer_menu
    tap_text 'Viewer address'
    wait_for_text 'Address and port'
}
open_more
menu_surface more '{"text":["Release notes","Estimate accuracy","Dictation API key","Screencast2 upload key"]}'
tap_text 'Estimate accuracy'
wait_for_text Close
menu_surface accuracy-open '{"text":["Estimate accuracy","Close"]}'
tap_text Close
wait_for_absence 'Estimate accuracy'
menu_reader accuracy-close
open_more
tap_text 'Estimate accuracy'
wait_for_text Close
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Estimate accuracy'
menu_reader accuracy-dismiss
open_more
tap_text 'Dictation API key'
wait_for_text 'Deepgram dictation'
fill 'API key' not-a-key
menu_surface key-draft '{"text":["Deepgram dictation","Cancel","Save and dictate"]}'
tap_text 'Save and dictate'
wait_for_text 'This is not a Deepgram API key.'
menu_surface key-invalid '{"text":["Deepgram dictation","This is not a Deepgram API key. Copy the 40 character key."]}'
tap_text Cancel
wait_for_absence 'Deepgram dictation'
menu_reader key-cancel
open_more
tap_text 'Dictation API key'
wait_for_text 'Deepgram dictation'
menu_surface key-reopened '{"text":["Deepgram dictation","API key"],"field":"","disabled":["Save and dictate"]}'
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Deepgram dictation'
menu_reader key-dismiss
open_more
tap_text 'Screencast2 upload key'
wait_for_text 'Screencast2 uploads'
menu_surface upload-open '{"text":["Screencast2 uploads","Save and choose image","Cancel"]}'
tap_text Cancel
wait_for_absence 'Screencast2 uploads'
menu_reader upload-cancel
open_more
tap_text 'Screencast2 upload key'
wait_for_text 'Screencast2 uploads'
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Screencast2 uploads'
menu_reader upload-dismiss
open_pin 'Pin a page…'
fill 'Page address' https://example.com/igsh-2669
menu_surface pin-draft '{"text":["Pin a page","Pin page"],"field":"https://example.com/igsh-2669"}'
tap_text Cancel
wait_for_absence 'Pin a page'
menu_reader pin-cancel
open_pin 'Pin a page…'
menu_surface pin-cancelled-value '{"text":["Pin a page"],"field":""}'
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Pin a page'
menu_reader pin-dismiss
open_pin 'Pin a page…'
fill 'Page address' https://example.com/igsh-2669
tap_text 'Pin page'
wait_for_absence 'Pin a page'
menu_surface pinned '{"text":["example.com/igsh-2669","Open","Remove"],"desc":["Session actions"]}'
relaunch_app
tap_text verify-session
wait_for_text 'Message VERIFY_RENAMED_2653'
menu_surface pin-retained '{"text":["example.com/igsh-2669","Open","Remove"],"desc":["Session actions"]}'
tap_text Open
wait_for_text Page
menu_surface page-open '{"text":["Page"],"desc":["Session actions"]}'
adb shell input keyevent KEYCODE_BACK
wait_for_absence Page
menu_reader page-back
tap_text Remove
wait_for_absence example.com
menu_surface pin-removed '{"desc":["Session actions"],"absent":["example.com/igsh-2669","Open","Remove"]}'
tap_desc Attach
tap_text Snippets
wait_for_text 'Save current text'
menu_surface snippets-open '{"text":["Snippets","Save current text"],"absent":["Add image"]}'
tap_text Snippets
wait_for_text 'Add image'
menu_surface snippets-back '{"text":["Add image","Add file","Snippets"]}'
tap_text 'Add file'
wait_for_text 'Recent'
shot reader-menu-file-picker
python3 tools/control-journeys/ui.py "$EVIDENCE/$(printf '%02d' "$step")-reader-menu-file-picker.xml" "$EVIDENCE/$(printf '%02d' "$step")-reader-menu-file-picker.png" surface '{"text":["Recent"],"absent":["Session actions"]}' > "$EVIDENCE/reader-menu-file-picker.json"
adb shell input keyevent KEYCODE_BACK
menu_reader file-cancel
open_control_menu Computer
wait_for_text 'View only'
menu_surface computer-open '{"text":["Computer","View only"],"desc":["Back to chat","Computer actions"]}'
open_computer_menu
menu_surface computer-menu '{"text":["Viewer address","Open full screen","Hide computer"],"disabled":["127.0.0.1:6080"]}'
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Viewer address'
menu_surface computer-dismiss '{"text":["Computer","View only"],"desc":["Computer actions"],"absent":["Viewer address"]}'
open_viewer_address
fill 'Address and port' 127.0.0.1:6081
menu_surface address-draft '{"text":["Viewer address","Save address"],"field":"127.0.0.1:6081"}'
tap_text Cancel
wait_for_absence 'Address and port'
open_viewer_address
menu_surface address-cancel '{"text":["Viewer address"],"field":"127.0.0.1:6080"}'
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'Address and port'
menu_surface address-dismiss '{"text":["Computer","View only"],"desc":["Computer actions"],"absent":["Address and port"]}'
open_viewer_address
fill 'Address and port' 127.0.0.1:6081
tap_text 'Save address'
wait_for_absence 'Address and port'
menu_surface address-saved '{"text":["Computer","View only"],"desc":["Computer actions"],"absent":["Address and port"]}'
tap_desc 'Back to chat'
menu_reader computer-back
open_control_menu Computer
wait_for_text 'View only'
open_computer_menu
menu_surface address-retained '{"text":["Viewer address"],"disabled":["127.0.0.1:6081"]}'
tap_text 'Open full screen'
wait_for_text 'Exit full screen'
menu_surface computer-menu-full '{"text":["Exit full screen"],"absent":["Computer actions","Back to chat"]}'
tap_text 'Exit full screen'
wait_for_text 'View only'
menu_surface computer-exit-full '{"text":["Computer","View only"],"desc":["Computer actions","Back to chat"]}'
open_computer_menu
tap_text 'Hide computer'
menu_reader computer-hide
open_control_menu 'Voice Mode'
wait_for_absence 'Search in chat'
menu_reader voice-enabled
tap_desc 'Session actions'
tap_node text 'Voice Mode' reveal
menu_surface voice-on '{"text":["Voice Mode"],"marked":{"Voice Mode":true}}'
tap_text 'Voice Mode'
wait_for_absence 'Search in chat'
tap_desc 'Session actions'
tap_node text 'Voice Mode' reveal
menu_surface voice-off '{"text":["Voice Mode"],"marked":{"Voice Mode":false}}'
adb shell input keyevent KEYCODE_BACK
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reader-menu-before-end.txt"
open_control_menu 'End tmux session'
wait_for_text 'End VERIFY_RENAMED_2653 completely?'
menu_surface end-open '{"text":["End VERIFY_RENAMED_2653 completely?","Cancel","End session"]}'
tap_text Cancel
wait_for_absence 'End session'
menu_reader end-cancel
open_control_menu 'End tmux session'
wait_for_text 'End VERIFY_RENAMED_2653 completely?'
adb shell input keyevent KEYCODE_BACK
wait_for_absence 'End session'
menu_reader end-dismiss
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reader-menu-after-end.txt"
cmp "$EVIDENCE/reader-menu-before-end.txt" "$EVIDENCE/reader-menu-after-end.txt"
sudo -u verifyssh tmux display-message -p -t '=verify-session:' '#{session_name}|#{window_name}' > "$EVIDENCE/reader-menu-end-host.txt"
grep -Fx 'verify-session|VERIFY_RENAMED_2653' "$EVIDENCE/reader-menu-end-host.txt"
tap_desc Back
wait_for_text Inbox
tap_desc 'Session list actions'
wait_for_text Settings
menu_surface inbox-menu '{"text":["Refresh","Settings"]}'
adb shell input keyevent KEYCODE_BACK
wait_for_absence Settings
menu_surface inbox-dismiss '{"text":["Inbox"],"desc":["Session list actions"],"absent":["Refresh","Settings"]}'
tap_text verify-session
wait_for_text 'Message VERIFY_RENAMED_2653'
open_more
tap_text 'Release notes'
wait_for_text 'App updates'
menu_surface release-notes '{"text":["App updates"],"absent":["Session actions","Dictation API key"]}'
printf 'READER_MENU_40_CONTROLS_OK\n' | tee "$EVIDENCE/reader-menu-proof.txt"
