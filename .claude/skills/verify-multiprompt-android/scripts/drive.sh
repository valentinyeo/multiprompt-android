#!/usr/bin/env bash
set -euo pipefail
feature=${1:?Usage: drive.sh feature-file.md}
case "$feature" in
    sign-in.md|hosts.md|sessions.md|session-reader.md|terminal.md) ;;
    *) printf 'Unknown feature: %s\n' "$feature" >&2; exit 2 ;;
esac
: "${EVIDENCE:?Set evidence directory}"
: "${FIXTURE:?Set fixture directory}"
: "${EXPECTED_VERSION:?Set expected APK versionName}"
scripts=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$EVIDENCE"
exec > >(tee -a "$EVIDENCE/driver-transcript.txt") 2>&1
set -x
package=dev.multiprompt.companion.debug
step=0
dump_ui() {
    timeout 20s adb shell uiautomator dump /sdcard/verify-ui.xml >/dev/null
    adb pull /sdcard/verify-ui.xml "$EVIDENCE/current.xml" >/dev/null
}
shot() {
    dump_ui
    step=$((step + 1))
    cp "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-$1.xml"
    adb exec-out screencap -p > "$EVIDENCE/$(printf '%02d' "$step")-$1.png"
}
finish() {
    code=$?
    trap - EXIT
    set +e
    shot "exit-$code"
    timeout 15s adb logcat -d > "$EVIDENCE/logcat.txt"
    printf 'DRIVER EXIT: %s\n' "$code"
    exit "$code"
}
trap finish EXIT
select_node() {
    dump_ui
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" "$1" "$2"
}
tap_node() {
    local point x y
    point=$(select_node "$1" "$2")
    read -r x y <<< "$point"
    adb shell input tap "$x" "$y"
}
tap_text() { tap_node text "$1"; }
tap_desc() { tap_node desc "$1"; }
wait_for_text() {
    local attempt
    for attempt in {1..30}; do
        if select_node contains "$1" >/dev/null 2>&1; then return; fi
        sleep 1
    done
    printf 'Timed out waiting for %s\n' "$1" >&2
    return 1
}
fill() {
    tap_node field "$1"
    adb shell input keycombination 113 29
    adb shell input text "$2"
    adb shell input keyevent KEYCODE_BACK
}
wait_receipt() {
    local attempt
    for attempt in {1..30}; do
        if sudo grep -Fx "$1" /home/verifyssh/receipts.txt; then return; fi
        sleep 1
    done
    return 1
}
adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
wait_for_text 'Sign in to see your servers'
"$scripts/doctor.sh"
shot sign-in
if [[ "$feature" == sign-in.md ]]; then
    tap_text 'Sign in'
    for attempt in {1..30}; do
        adb shell dumpsys activity activities > "$EVIDENCE/browser-activity.txt"
        if grep -E '(mResumedActivity|topResumedActivity).*com.android.chrome/' "$EVIDENCE/browser-activity.txt"; then break; fi
        sleep 1
    done
    grep -E '(mResumedActivity|topResumedActivity).*com.android.chrome/' "$EVIDENCE/browser-activity.txt"
    shot access-browser
    printf 'SIGN-IN HANDOFF VERIFIED; OTP completion is NOT verified\n'
    exit 0
fi
tap_text 'Use without account (debug)'
wait_for_text Inbox
tap_desc 'Session list actions'
tap_text Hosts
tap_desc 'Add host'
wait_for_text 'Add VPS'
fill Label Verify
fill 'Hostname or IP' 10.0.2.2
fill Username verifyssh
fill Port 2222
adb push "$FIXTURE/user-key" /sdcard/Download/verify-key.pem
tap_text 'Import private key'
wait_for_text 'Recent'
shot key-picker
tap_desc 'Show roots'
tap_text Downloads
wait_for_text verify-key.pem
tap_text verify-key.pem
wait_for_text 'Key:'
shot key-imported
tap_text 'Save and connect'
wait_for_text 'Trust Verify?'
shot compare-host-key
fingerprint=$(cat "$EVIDENCE/host-fingerprint.txt")
grep -F "ssh-ed25519 $fingerprint" "$EVIDENCE/current.xml"
if sudo grep -q 'Accepted publickey' "$FIXTURE/sshd.log"; then
    printf 'FAIL: authentication occurred before explicit trust\n' >&2
    exit 1
fi
tap_text 'Trust and connect'
wait_for_text verify-session
shot sessions
sudo grep 'Accepted publickey for verifyssh' "$FIXTURE/sshd.log" > "$EVIDENCE/ssh-auth.txt"
if [[ "$feature" == hosts.md ]]; then
    tap_desc 'Session list actions'
    tap_text Hosts
    wait_for_text "Pinned $fingerprint"
    shot pinned-host
    exit 0
fi
if [[ "$feature" == sessions.md ]]; then
    tap_desc 'Search sessions'
    fill 'Search all sessions' verify-session
    wait_for_text verify-session
    shot search-match
    tap_desc 'Close session search'
    wait_for_text verify-session
    shot search-cleared
    exit 0
fi
tap_text verify-session
wait_for_text VERIFY_READY_528
shot reader-ready
if [[ "$feature" == terminal.md ]]; then
    tap_desc 'Session actions'
    tap_text 'Open live terminal'
    wait_for_text 'SSH · tmux attached'
    sudo -u verifyssh tmux list-clients -F '#{client_name} #{client_session}' > "$EVIDENCE/tmux-clients.txt"
    grep -F verify-session "$EVIDENCE/tmux-clients.txt"
    shot terminal-attached
    exit 0
fi
fill 'Message this session' VERIFY_SENT_528
shot send-draft
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:VERIFY_SENT_528'
wait_for_text 'VERIFY_RECEIVED:VERIFY_SENT_528'
shot send-result
tap_desc 'Session actions'
shot enter-action
tap_text 'Enter / approve'
wait_receipt VERIFY_ENTER_528
wait_for_text VERIFY_ENTER_528
shot enter-result
tap_desc 'Session actions'
shot interrupt-action
tap_text 'Interrupt session'
wait_receipt VERIFY_INTERRUPTED_528
wait_for_text VERIFY_INTERRUPTED_528
shot interrupt-result
sudo cp /home/verifyssh/receipts.txt "$EVIDENCE/receipts.txt"
sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/tmux-final.txt"
grep -Fx VERIFY_INTERRUPTED_528 "$EVIDENCE/receipts.txt"
printf 'FEATURE VERIFIED: %s\n' "$feature"
