#!/usr/bin/env bash
set -euo pipefail
feature=${1:?Usage: drive.sh feature-file.md}
case "$feature" in
    sign-in.md|hosts.md|hosts-retired.md|host-changed-key.md|sessions.md|session-reader.md|terminal.md) ;;
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
    timeout 20s adb shell uiautomator dump /sdcard/verify-ui.xml >/dev/null || return
    timeout 20s adb pull /sdcard/verify-ui.xml "$EVIDENCE/current.xml" >/dev/null
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
    dump_ui || return
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
dismiss_system_anr() {
    if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "isn't responding" >/dev/null 2>&1; then
        printf 'System ANR dialog dismissed with Wait\n' | tee -a "$EVIDENCE/system-anr.txt"
        point=$(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text Wait)
        read -r x y <<< "$point"
        adb shell input tap "$x" "$y"
    fi
}
doctor() {
    local attempt
    for attempt in 1 2 3; do
        dump_ui
        dismiss_system_anr
        if "$scripts/doctor.sh"; then break; fi
        printf 'Doctor attempt %s failed\n' "$attempt" | tee -a "$EVIDENCE/doctor-retries.txt"
        [[ "$attempt" -lt 3 ]]
        sleep 3
    done
    for attempt in {1..30}; do
        if adb shell ping -c 1 -W 2 10.0.2.2 >/dev/null 2>&1; then return; fi
        printf 'Emulator network not up yet, attempt %s/30\n' "$attempt" | tee -a "$EVIDENCE/network-wait.txt"
        sleep 2
    done
    printf 'BLOCKED: emulator cannot reach 10.0.2.2\n' >&2
    return 1
}
wait_for_text() {
    local attempt
    for attempt in {1..30}; do
        if select_node contains "$1" >/dev/null 2>&1; then return; fi
        dismiss_system_anr
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
wait_for_absence() {
    local attempt
    for attempt in {1..30}; do
        if select_node absent "$1" >/dev/null 2>&1; then return; fi
        sleep 1
    done
    printf 'Timed out waiting for absence of %s\n' "$1" >&2
    return 1
}
relaunch_app() {
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
    for attempt in {1..30}; do
        printf 'Relaunch navigation attempt %s/30\n' "$attempt"
        dump_ui
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains Inbox >/dev/null 2>&1; then break; fi
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text 'Use without account (debug)' >/dev/null 2>&1; then
            tap_text 'Use without account (debug)'
            break
        fi
        sleep 1
    done
    wait_for_text Inbox
    doctor
}
refresh_inbox() {
    tap_desc 'Session list actions'
    tap_text Refresh
}
require_dismissed() {
    local attempt connections_before=$1 connections_after
    for attempt in {1..12}; do
        dump_ui
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains Inbox
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent 'SSH host key changed'
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent 'Not now'
        sleep 1
    done
    connections_after=$(sudo awk '/Connection from / {n++} END {print n+0}' "$FIXTURE/sshd.log")
    (( connections_after > connections_before ))
}
wait_receipt() {
    local attempt
    for attempt in {1..30}; do
        if sudo grep -Fx "$1" /home/verifyssh/receipts.txt; then return; fi
        sleep 1
    done
    return 1
}
adb shell settings put global hide_error_dialogs 1
adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
wait_for_text 'Sign in to see your servers, workspaces and sessions.'
doctor
dump_ui
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" version "$EXPECTED_VERSION" > "$EVIDENCE/version-label.txt"
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
if [[ "$feature" == hosts-retired.md ]]; then
    retired=45.151.123.11
    sudo iptables -I OUTPUT -d "$retired" -p tcp -j REJECT --reject-with tcp-reset
    sudo iptables -S OUTPUT | tee "$EVIDENCE/retired-address-blocked.txt"
    grep -F -- "-d $retired/32" "$EVIDENCE/retired-address-blocked.txt"
    fill Label 'Inne%sCRO'
    fill 'Hostname or IP' "$retired"
    fill Username valentin
    fill Port 22
    adb push "$FIXTURE/user-key" /sdcard/Download/verify-key.pem
    tap_text 'Import private key'
    wait_for_text 'Recent'
    tap_desc 'Show roots'
    tap_text Downloads
    wait_for_text verify-key.pem
    tap_text verify-key.pem
    wait_for_text 'Key:'
    shot retired-form
    tap_text 'Save and connect'
    wait_for_text Inbox
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent 'Trust Inne CRO?'
    tap_desc 'Session list actions'
    tap_text Hosts
    wait_for_text 'Inne CRO'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "valentin@$retired:22"
    shot retired-host-saved
    relaunch_app
    for attempt in {1..10}; do
        dump_ui
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains Inbox
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks unreachable
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks 'Inne CRO'
        sleep 1
    done
    shot relaunched-inbox
    tap_desc 'Session list actions'
    tap_text Hosts
    for attempt in {1..30}; do
        if select_node desc 'Add host' >/dev/null 2>&1; then break; fi
        sleep 1
    done
    select_node desc 'Add host'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks 'Inne CRO'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks "$retired"
    shot relaunched-hosts
    sudo iptables -L OUTPUT -v -n | tee "$EVIDENCE/retired-address-attempts.txt"
    printf 'RETIRED HOST REMOVED ON RELAUNCH VERIFIED\n'
    exit 0
fi
fill Label Verify
fill 'Hostname or IP' 10.0.2.2
fill Username verifyssh
fill Port 2222
adb push "$FIXTURE/user-key" /sdcard/Download/verify-key.pem
tap_text 'Import private key'
wait_for_text 'Recent'
shot key-picker
tap_desc 'Show roots'
shot picker-roots
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
if ! wait_for_text verify-session; then
    printf 'Trust tap 1 did not pin the host; the dialog was still shown\n' | tee -a "$EVIDENCE/trust-retries.txt"
    shot trust-retry-1
    exit 1
fi
shot sessions
sudo grep 'Accepted publickey for verifyssh' "$FIXTURE/sshd.log" > "$EVIDENCE/ssh-auth.txt"
if [[ "$feature" == hosts.md ]]; then
    tap_desc 'Session list actions'
    tap_text Hosts
    wait_for_text "Pinned $fingerprint"
    shot pinned-host
    exit 0
fi
if [[ "$feature" == host-changed-key.md ]]; then
    ssh-keygen -q -t ed25519 -N '' -C verify-rotated -f "$FIXTURE/rotated-host-key"
    ssh-keygen -lf "$FIXTURE/rotated-host-key.pub" -E sha256 | awk '{print $2}' > "$EVIDENCE/rotated-fingerprint.txt"
    rotated=$(cat "$EVIDENCE/rotated-fingerprint.txt")
    [[ "$rotated" == SHA256:* && "$rotated" != "$fingerprint" ]]
    accepted_before=$(sudo awk '/Accepted publickey/ {n++} END {print n+0}' "$FIXTURE/sshd.log")
    (( accepted_before > 0 ))
    printf '%s\n' "$accepted_before" > "$EVIDENCE/accepted-before-rotation.txt"
    old_pid=$(sudo cat "$FIXTURE/sshd.pid")
    [[ "$old_pid" =~ ^[0-9]+$ && "$old_pid" -gt 1 ]]
    sudo kill "$old_pid"
    for attempt in {1..30}; do
        if ! sudo kill -0 "$old_pid" 2>/dev/null; then break; fi
        sleep 1
    done
    if sudo kill -0 "$old_pid" 2>/dev/null; then exit 1; fi
    python3 - "$FIXTURE" <<'PYROTATE'
import sys
from pathlib import Path
fixture = Path(sys.argv[1])
config = fixture / "sshd_config"
old = f"HostKey {fixture}/host-key\n"
new = f"HostKey {fixture}/rotated-host-key\n"
text = config.read_text()
assert text.count(old) == 1
config.write_text(text.replace(old, new))
PYROTATE
    sudo /usr/sbin/sshd -t -f "$FIXTURE/sshd_config"
    sudo /usr/sbin/sshd -f "$FIXTURE/sshd_config" -E "$FIXTURE/sshd.log"
    new_pid=$(sudo cat "$FIXTURE/sshd.pid")
    [[ "$new_pid" =~ ^[0-9]+$ && "$new_pid" -gt 1 && "$new_pid" != "$old_pid" ]]
    sudo kill -0 "$new_pid"
    printf '%s\n' "$new_pid" > "$EVIDENCE/rotated-sshd-pid.txt"
    wait_for_text 'SSH host key changed' || { refresh_inbox; wait_for_text 'SSH host key changed'; }
    shot changed-host-key
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "Now: ssh-ed25519 $rotated"
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "Saved: ssh-ed25519 $fingerprint"
    tap_text 'Not now'
    connections_before=$(sudo awk '/Connection from / {n++} END {print n+0}' "$FIXTURE/sshd.log")
    refresh_inbox
    require_dismissed "$connections_before"
    shot changed-key-dismissed
    relaunch_app
    connections_before=$(sudo awk '/Connection from / {n++} END {print n+0}' "$FIXTURE/sshd.log")
    refresh_inbox
    require_dismissed "$connections_before"
    shot changed-key-dismissed-relaunched
    tap_desc 'Session list actions'
    tap_text Hosts
    wait_for_text "Pinned $fingerprint"
    shot original-key-still-pinned
    accepted_after=$(sudo awk '/Accepted publickey/ {n++} END {print n+0}' "$FIXTURE/sshd.log")
    printf '%s\n' "$accepted_after" > "$EVIDENCE/accepted-after-rotation.txt"
    [[ "$accepted_after" == "$accepted_before" ]]
    printf 'CHANGED KEY DISMISSAL VERIFIED; no authentication after rotation\n'
    exit 0
fi
if [[ "$feature" == sessions.md ]]; then
    tap_desc 'Search sessions'
    fill 'Search all sessions' verify-session
    wait_for_text '1 result across all splits'
    wait_for_text verify-session
    shot search-match
    tap_desc 'Close session search'
    wait_for_absence 'Search all sessions'
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
sudo -u verifyssh mkdir -p /home/verifyssh/.local/state/multiprompt
sudo -u verifyssh python3 -c 'import json,time; json.dump({"verify-session":{"started_at":int(time.time())-120,"est_seconds":240}},open("/home/verifyssh/.local/state/multiprompt/eta-open.json","w"))'
tap_desc 'Session actions'
wait_for_text 'ETA'
adb shell input keyevent KEYCODE_BACK
shot eta-idle
python3 "$scripts/no-chat-bar.py" "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-eta-idle.png" idle
sudo -u verifyssh tee /home/verifyssh/send-delay.sh >/dev/null <<'DELAY'
#!/usr/bin/env bash
case "$SSH_ORIGINAL_COMMAND" in *mp_buffer=*) sleep 15 ;; esac
exec bash -c "$SSH_ORIGINAL_COMMAND"
DELAY
sudo chmod 755 /home/verifyssh/send-delay.sh
sudo sed -i 's|^|command="/home/verifyssh/send-delay.sh" |' /home/verifyssh/.ssh/authorized_keys
fill 'Message this session' VERIFY_SENT_528
shot send-draft
tap_desc 'Send prompt'
shot eta-sending
python3 "$scripts/no-chat-bar.py" "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-eta-sending.png" sending
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
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/receipts.txt"
sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/tmux-final.txt"
grep -Fx VERIFY_INTERRUPTED_528 "$EVIDENCE/receipts.txt"
printf 'FEATURE VERIFIED: %s\n' "$feature"
