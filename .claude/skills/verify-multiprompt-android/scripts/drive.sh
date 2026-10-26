#!/usr/bin/env bash
set -euo pipefail
feature=${1:?Usage: drive.sh feature-file.md}
case "$feature" in
    sign-in.md|hosts.md|hosts-retired.md|host-changed-key.md|sessions.md|session-reader.md|session-routing.md|chat-choices.md|terminal.md|chat-reactions.md|chat-queued.md|native-skills.md|chat-resync.md|launcher-icon.md) ;;
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
launcher_icon() {
    local size w h attempt
    size=$(adb shell wm size | grep -Eo '[0-9]+x[0-9]+' | tail -1)
    w=${size%x*}
    h=${size#*x}
    adb shell input keyevent KEYCODE_HOME
    sleep 3
    shot launcher-home
    adb shell input swipe $((w / 2)) $((h * 9 / 10)) $((w / 2)) $((h / 5)) 300
    sleep 2
    for attempt in {1..8}; do
        dump_ui
        if python3 "$scripts/launcher-icon.py" find "$EVIDENCE/current.xml"; then break; fi
        adb shell input swipe $((w / 2)) $((h * 8 / 10)) $((w / 2)) $((h / 4)) 300
        sleep 2
    done
    shot launcher-drawer
    python3 "$scripts/launcher-icon.py" check "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-launcher-drawer.png" "$EVIDENCE/launcher-icon-crop.png" "$EVIDENCE/launcher-icon-result.json"
    local aapt2
    aapt2=$(find "$ANDROID_HOME/build-tools" -name aapt2 | sort | tail -1)
    "$aapt2" dump xmltree --file res/mipmap-anydpi-v26/ic_launcher.xml "$VERIFY_APK" | tee "$EVIDENCE/adaptive-icon-tree.txt"
    grep -q 'monochrome' "$EVIDENCE/adaptive-icon-tree.txt"
    printf 'ANDROID_LAUNCHER_ROBOT_ICON_PROVEN\n'
}
adb shell settings put global hide_error_dialogs 1
adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
wait_for_text 'Sign in to see your servers, workspaces and sessions.'
doctor
dump_ui
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" version "$EXPECTED_VERSION" > "$EVIDENCE/version-label.txt"
shot sign-in
if [[ "$feature" == launcher-icon.md ]]; then
    launcher_icon
    exit 0
fi
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
tap_text All
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
if [[ "$feature" == sessions.md || "$feature" == session-reader.md ]]; then
    adb shell cmd uimode night yes
    sleep 2
fi
if [[ "$feature" == session-routing.md ]]; then
    wait_for_text verify-other
    tap_text verify-session
    wait_for_text 'Messaging verify-session'
    fill 'Message verify-session' UI_ONLY_A
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:UI_ONLY_A'
    shot session-a-sent
    tap_desc Back
    wait_for_text Inbox
    tap_text verify-other
    wait_for_text 'Messaging verify-other'
    fill 'Message verify-other' UI_ONLY_B
    tap_desc 'Send prompt'
    for attempt in {1..30}; do
        if sudo grep -Fx 'VERIFY_RECEIVED:UI_ONLY_B' /home/verifyssh/other-receipts.txt; then break; fi
        sleep 1
    done
    sudo grep -Fx 'VERIFY_RECEIVED:UI_ONLY_B' /home/verifyssh/other-receipts.txt
    shot session-b-sent
    adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell am instrument -w -r -e class dev.multiprompt.companion.ui.ReaderSessionRoutingTest \
        dev.multiprompt.companion.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/routing-instrumentation.txt"
    grep -F 'OK (1 test)' "$EVIDENCE/routing-instrumentation.txt"
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/session-a-receipts.txt"
    sudo cat /home/verifyssh/other-receipts.txt > "$EVIDENCE/session-b-receipts.txt"
    python3 - "$EVIDENCE" <<'PYRECEIPTS'
from pathlib import Path
import sys
root = Path(sys.argv[1])
a = (root / 'session-a-receipts.txt').read_text().splitlines()
b = (root / 'session-b-receipts.txt').read_text().splitlines()
assert a == ['VERIFY_RECEIVED:' + text for text in ('UI_ONLY_A', 'TYPED_A', 'VOICE_A')], a
assert b == ['VERIFY_RECEIVED:' + text for text in ('UI_ONLY_B', 'TYPED_B', 'VOICE_B', 'AFTER_SWITCH_B')], b
print('EMULATOR_ROUTING_OK')
PYRECEIPTS
    adb pull /sdcard/Android/data/dev.multiprompt.companion.debug/files/routing-b.png "$EVIDENCE/routing-b.png"
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
    dump_ui
    if grep -F 'content-desc="Session actions"' "$EVIDENCE/current.xml"; then
        printf 'Inbox row still has a per-row menu button\n' >&2
        exit 1
    fi
    read -r row_x row_y <<< "$(select_node text verify-session)"
    adb shell input swipe "$row_x" "$row_y" "$row_x" "$row_y" 800
    wait_for_text 'Mark unread'
    wait_for_text 'End session'
    shot row-long-press-actions
    adb shell input keyevent KEYCODE_BACK
    wait_for_absence 'End session'
    exit 0
fi
tap_text verify-session
if [[ "$feature" == chat-choices.md ]]; then
    wait_for_text 'Use your own account. Keep the current setup.'
    shot choices-before
    tap_node text-containing 'Use your own account. Keep the current setup.'
    wait_receipt 'VERIFY_RECEIVED:B: Use your own account. Keep the current setup.'
    wait_for_text 'B: Use your own account. Keep the current setup.'
    shot choices-after
    wait_for_text '2: Use the current account'
    shot question-before
    tap_text '2: Use the current account'
    wait_receipt 'VERIFY_QUESTION_KEY:2'
    wait_for_text 'Question answered: Use the current account'
    shot question-after
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/receipts.txt"
    python3 - "$EVIDENCE/receipts.txt" <<'CHOICES'
import sys
from pathlib import Path
rows = Path(sys.argv[1]).read_text().splitlines()
assert rows == ["VERIFY_RECEIVED:B: Use your own account. Keep the current setup.", "VERIFY_QUESTION_KEY:2"], rows
print("CHOICE SEND AND QUESTION KEY VERIFIED")
CHOICES
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/tmux-final.txt"
    printf 'FEATURE VERIFIED: %s\n' "$feature"
    exit 0
fi
if [[ "$feature" == chat-queued.md ]]; then
    wait_for_text VERIFY_READY_1008
else
    wait_for_text VERIFY_READY_528
fi
shot reader-ready
if [[ "$feature" == chat-queued.md ]]; then
    wait_for_text 'Ready for queued messages.'
    shot queued-ready
    fill 'Message verify-session' WAITING_ONE
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:WAITING_ONE'
    wait_for_text WAITING_ONE
    fill 'Message verify-session' WAITING_TWO
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:WAITING_TWO'
    wait_for_text WAITING_TWO
    shot queued-waiting
    python3 "$scripts/queued.py" "$EVIDENCE/current.xml" 2 WAITING
    sudo -u verifyssh python3 /home/verifyssh/queued-console.py take-in
    sleep 8
    shot queued-taken-in
    python3 "$scripts/queued.py" "$EVIDENCE/current.xml" 2 TAKEN_IN_NO_REPLY
    sudo -u verifyssh python3 /home/verifyssh/queued-console.py reply
    wait_for_text 'Both messages received.'
    for attempt in {1..30}; do
        dump_ui
        if python3 "$scripts/queued.py" "$EVIDENCE/current.xml" 0 REPLIED >/dev/null 2>&1; then break; fi
        sleep 1
    done
    shot queued-replied
    if ! python3 "$scripts/queued.py" "$EVIDENCE/current.xml" 0 REPLIED; then
        printf 'IGSH1008_QUEUED_STUCK\n' >&2
        exit 1
    fi
    wait_for_text WAITING_ONE
    wait_for_text WAITING_TWO
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/queued-receipts.txt"
    sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-queued-work/a1b2c3d4-1008-4000-8000-000000001008.jsonl > "$EVIDENCE/queued-transcript.jsonl"
    printf 'ANDROID_QUEUED_CLEARS_AFTER_REPLY\n'
    exit 0
fi
if [[ "$feature" == native-skills.md ]]; then
    set_composer() {
        tap_node editor ''
        adb shell input keycombination 113 29
        adb shell input text "$1"
    }
    set_composer /
    if ! wait_for_text '/review-native'; then
        printf 'NATIVE_SLASH_PICKER_MISSING\n' >&2
        exit 1
    fi
    wait_for_text '/personal-note'
    wait_for_text '/qa:verify-native'
    wait_for_absence '/late-native'
    if select_node contains '/hidden-native' >/dev/null 2>&1; then exit 1; fi
    shot native-slash-list
    adb shell input text rev
    wait_for_text '/review-native'
    wait_for_absence '/personal-note'
    shot native-filtered
    tap_text '/review-native'
    shot native-inserted
    adb shell input text investigation
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:/review-native investigation'
    set_composer 'draft%sstays'
    tap_desc Attach
    wait_for_text Skill
    wait_for_text Model
    wait_for_text Effort
    wait_for_text 'Add image'
    shot native-plus-menu
    tap_text Skill
    wait_for_text '/review-native'
    wait_for_text '/qa:verify-native'
    wait_for_text '/late-native'
    shot native-plus-same-list
    tap_text '/qa:verify-native'
    shot native-plugin-inserted
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:/qa:verify-native draft stays'
    set_composer 'keep%sthis%sdraft'
    tap_desc Attach
    tap_text Model
    wait_for_text 'Current: Opus 5.5'
    shot native-model-menu
    tap_text 'Use Claude Sonnet'
    wait_receipt 'VERIFY_RECEIVED:/model sonnet'
    tap_desc Attach
    tap_text Model
    wait_for_text 'Current: Sonnet 5.5'
    shot native-model-observed
    tap_text '‹ Back'
    tap_text Effort
    wait_for_text 'Current: medium'
    shot native-effort-menu
    tap_text 'Set effort: high'
    wait_receipt 'VERIFY_RECEIVED:/effort high'
    tap_desc Attach
    tap_text Effort
    wait_for_text 'Current: high'
    shot native-effort-observed
    adb shell input keyevent KEYCODE_BACK
    tap_desc Attach
    tap_text 'Add image'
    wait_for_text 'Screencast2 uploads'
    fill 'Screencast2 upload key' fixture-only-no-upload
    tap_text 'Save and choose image'
    wait_for_text Photos
    shot native-image-picker
    adb shell input keyevent KEYCODE_BACK
    wait_for_text 'keep this draft'
    shot native-draft-preserved
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/native-receipts.txt"
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/native-tmux-final.txt"
    sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-native-skills-work/a1b2c3d4-1009-4000-8000-000000001009.jsonl > "$EVIDENCE/native-transcript.jsonl"
    python3 - "$EVIDENCE" <<'NATIVE'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
rows = (root / 'native-receipts.txt').read_text().splitlines()
assert rows == ['VERIFY_RECEIVED:/review-native investigation', 'VERIFY_RECEIVED:/qa:verify-native draft stays', 'VERIFY_RECEIVED:/model sonnet', 'VERIFY_RECEIVED:/effort high'], rows
assert 'Sonnet 5.5 high' in (root / 'native-tmux-final.txt').read_text()
assert 'BODY_MUST_NOT_BE_INJECTED' not in (root / 'native-transcript.jsonl').read_text()
for label, required in [('native-inserted', '/review-native '), ('native-plugin-inserted', '/qa:verify-native draft stays'), ('native-draft-preserved', 'keep this draft')]:
    xml = ET.parse(next(root.glob('*-' + label + '.xml'))).getroot()
    fields = [n for n in xml.iter('node') if n.get('class') == 'android.widget.EditText' and n.get('package') == 'dev.multiprompt.companion.debug']
    assert len(fields) == 1 and fields[0].get('text') == required, (label, fields)
(root / 'native-result.json').write_text(json.dumps(dict(status='passed', slash=True, filtered=True, plus_same_list=True, fresh_native_skills=True, native_only=True, model='Sonnet 5.5', effort='high', draft_preserved=True, image_picker=True, upload_exercised=False), indent=2))
print('NATIVE_SKILLS_MODEL_EFFORT_PROVEN')
NATIVE
    exit 0
fi
if [[ "$feature" == chat-resync.md ]]; then
    wait_for_text 'FIRST REPLY'
    for message in SYNC_ONE SYNC_TWO; do
        fill 'Message verify-session' "$message"
        tap_desc 'Send prompt'
        wait_receipt "VERIFY_RECEIVED:$message"
        wait_for_text "Reply to $message"
    done
    wait_for_text Queued
    shot out-of-sync
    queued_before=$(grep -o 'text="Queued"' "$EVIDENCE/current.xml" | wc -l)
    printf '%s\n' "$queued_before" > "$EVIDENCE/queued-before.txt"
    [[ "$queued_before" == 2 ]]
    fill 'Message verify-session' 'draft%sstays%shere'
    shot draft-before
    tap_desc 'Session actions'
    if ! wait_for_text 'Resync chat'; then
        printf 'IGSH1011_RESYNC_MISSING\n' >&2
        exit 1
    fi
    shot resync-menu
    tap_text 'Resync chat'
    toast_shots=()
    for attempt in {1..8}; do
        adb exec-out screencap -p > "$EVIDENCE/toast-$attempt.png"
        toast_shots+=("$EVIDENCE/toast-$attempt.png")
    done
    sleep 5
    adb exec-out screencap -p > "$EVIDENCE/toast-settled.png"
    python3 "$scripts/toast.py" "$EVIDENCE/toast-settled.png" "${toast_shots[@]}" | tee "$EVIDENCE/toast-result.txt"
    wait_for_absence Queued
    shot synced
    wait_for_text 'Reply to SYNC_ONE'
    wait_for_text 'Reply to SYNC_TWO'
    wait_for_text '[phone] SYNC_ONE'
    wait_for_text '[phone] SYNC_TWO'
    dump_ui
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field 'draft stays here'
    shot resynced
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/receipts.txt"
    python3 - "$EVIDENCE/receipts.txt" <<'RESYNC'
import sys
from pathlib import Path
rows = Path(sys.argv[1]).read_text().splitlines()
assert rows == ["VERIFY_RECEIVED:SYNC_ONE", "VERIFY_RECEIVED:SYNC_TWO"], rows
print("RESYNC CLEARED STALE QUEUED; NOTHING RESENT")
RESYNC
    sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-resync-work/a1b2c3d4-1011-4000-8000-000000001011.jsonl > "$EVIDENCE/resync-transcript.jsonl"
    printf 'ANDROID_CHAT_RESYNC_VERIFIED\n'
    exit 0
fi
if [[ "$feature" == chat-reactions.md ]]; then
    wait_for_text 'FIRST REPLY'
    wait_for_text 'SECOND REPLY'
    fill 'Message verify-session' 'draft%sstays%shere'
    shot reactions-before
    read -r reaction_x reaction_y <<< "$(python3 "$scripts/reactions.py" point "$EVIDENCE/current.xml")"
    adb shell input swipe "$reaction_x" "$reaction_y" "$reaction_x" "$reaction_y" 900
    shot reactions-menu
    if ! wait_for_text '👍'; then
        printf 'IGSH975_REACTIONS_MISSING\n' >&2
        exit 1
    fi
    wait_for_text '👎'
    wait_for_text '❤️'
    tap_text '👍'
    wait_for_absence 'Report'
    for attempt in {1..30}; do
        dump_ui
        if grep -F 'content-desc="Reactions: 👍"' "$EVIDENCE/current.xml" >/dev/null; then break; fi
        sleep 1
    done
    shot reactions-badge
    python3 "$scripts/reactions.py" check "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-reactions-badge.png" badge "$EVIDENCE/reactions-result.json"
    for attempt in {1..30}; do
        if sudo test -s /home/verifyssh/receipts.txt; then break; fi
        sleep 1
    done
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reaction-receipts.txt"
    python3 "$scripts/reactions.py" transport "$EVIDENCE/reaction-receipts.txt" "$EVIDENCE/reaction-transport.json"
    relaunch_app
    tap_text verify-session
    wait_for_text 'FIRST REPLY'
    wait_for_text 'SECOND REPLY'
    for attempt in {1..30}; do
        dump_ui
        if grep -F 'content-desc="Reactions: 👍"' "$EVIDENCE/current.xml" >/dev/null; then break; fi
        sleep 1
    done
    shot reactions-restart
    python3 "$scripts/reactions.py" check "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-reactions-restart.png" restart "$EVIDENCE/reactions-result.json"
    sudo -u verifyssh python3 /home/verifyssh/reaction-console.py append-unmatched
    wait_for_text 'Unavailable reply'
    shot reactions-fallback
    python3 "$scripts/reactions.py" check "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-reactions-fallback.png" fallback "$EVIDENCE/reactions-result.json"
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/reaction-receipts.txt"
    python3 "$scripts/reactions.py" transport "$EVIDENCE/reaction-receipts.txt" "$EVIDENCE/reaction-transport.json"
    sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-reaction-work/a1b2c3d4-0975-4000-8000-000000000975.jsonl > "$EVIDENCE/reaction-transcript.jsonl"
    printf 'ANDROID_REACTIONS_REAL_SSH_RESTART_PROVEN\n'
    exit 0
fi
if [[ "$feature" == session-reader.md ]]; then
    tap_desc 'Attach'
    wait_for_text 'Add image'
    shot attach-menu
    adb shell input keyevent KEYCODE_BACK
    wait_for_absence 'Add image'
    shot attach-closed
fi
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
fill 'Message verify-session' VERIFY_SENT_528
shot send-draft
tap_desc 'Send prompt'
shot eta-sending
python3 "$scripts/no-chat-bar.py" "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-eta-sending.png" sending
timeout 15s adb logcat -d -s SendTiming:I > "$EVIDENCE/send-timing.txt"
python3 "$scripts/send-timing.py" "$EVIDENCE/current.xml" "$EVIDENCE/send-timing.txt" VERIFY_SENT_528
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
