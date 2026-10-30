#!/usr/bin/env bash
set -euo pipefail
feature=${1:?Usage: drive.sh feature-file.md}
case "$feature" in
    settings-navigation.md|chat-codex.md|session-gestures.md|session-favorites.md|chat-working.md|session-summaries.md|sign-in.md|hosts.md|hosts-retired.md|host-changed-key.md|sessions.md|session-reader.md|session-routing.md|chat-choices.md|terminal.md|chat-reactions.md|chat-queued.md|native-skills.md|chat-resync.md|launcher-icon.md|chat-cancel.md|robot-faces.md) ;;
    *) printf 'Unknown feature: %s\n' "$feature" >&2; exit 2 ;;
esac
: "${EVIDENCE:?Set evidence directory}"
: "${FIXTURE:?Set fixture directory}"
: "${EXPECTED_VERSION:?Set expected APK versionName}"
scripts=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$EVIDENCE"
exec > >(tee -a "$EVIDENCE/driver-transcript.txt") 2>&1
set -x
package=${VERIFY_PACKAGE:-dev.multiprompt.companion.debug}
step=0
adb logcat -c
adb logcat -v threadtime > "$EVIDENCE/logcat-stream.txt" 2>&1 &
logcat_stream_pid=$!
recorder_pid=
dump_ui() {
    timeout 20s adb shell uiautomator dump /sdcard/verify-ui.xml >/dev/null || return
    timeout 20s adb pull /sdcard/verify-ui.xml "$EVIDENCE/current.xml" >/dev/null
}
shot() {
    dump_ui
    step=$((step + 1))
    cp "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-$1.xml"
    adb exec-out screencap -p > "$EVIDENCE/$(printf '%02d' "$step")-$1.png"
    if [[ "$feature" == session-favorites.md ]]; then
        python3 - "$EVIDENCE/$(printf '%02d' "$step")-$1.png" "$EVIDENCE/$(printf '%02d' "$step")-$1.xml" "$package" <<'PYCAPTURE'
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
image = Path(sys.argv[1]).read_bytes()
assert len(image) > 24 and image[:8] == b'\x89PNG\r\n\x1a\n'
assert all(size >= 100 for size in struct.unpack('>II', image[16:24]))
root = ET.parse(sys.argv[2]).getroot()
if '-favorites-' in sys.argv[2]:
    assert any(node.get('package') == sys.argv[3] for node in root.iter('node'))
PYCAPTURE
    fi
}
finish() {
    code=$?
    trap - EXIT
    set +e
    {
        date +%T.%N
        timeout 10s adb shell top -b -n 1 -m 15
        timeout 10s adb shell dumpsys meminfo
        timeout 10s adb shell dumpsys activity processes | grep -i "anr\|crash\|not responding"
        uptime
        free -m
        ps -eo pid,pcpu,pmem,args --sort=-pcpu | head -8
        pgrep -a qemu
        adb devices
        sudo dmesg | grep -iE "segfault|qemu|oom|killed process" | tail -20
        ls -la /tmp/android-runner /var/crash /var/lib/systemd/coredump
        sudo coredumpctl list --no-pager
        sudo journalctl --no-pager -n 40 -g "qemu|coredump|apport"
    } > "$EVIDENCE/load-at-exit.txt" 2>&1
    sudo cat /home/verifyssh/commands.log > "$EVIDENCE/commands.txt" 2>/dev/null
    if [[ "$feature" == session-routing.md ]]; then
        sudo cat /home/verifyssh/canary-raw.bin > "$EVIDENCE/canary-raw-final.bin" 2>/dev/null
        sudo cat /home/verifyssh/canary-receipts.txt > "$EVIDENCE/canary-receipts-final.txt" 2>/dev/null
        sudo cat /home/verifyssh/other-receipts.txt > "$EVIDENCE/session-b-receipts-final.txt" 2>/dev/null
    fi
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/tmux-final.txt" 2>/dev/null
    if [[ "$recorder_pid" =~ ^[0-9]+$ ]]; then
        if [[ "$feature" == session-routing.md ]] && ! adb shell kill -0 "$recorder_pid"; then code=1; fi
        adb shell kill -INT "$recorder_pid"
        wait "$recorder_client" || true
        if ! adb pull /sdcard/chat-catchup.mp4 "$EVIDENCE/chat-catchup.mp4" || [[ ! -s "$EVIDENCE/chat-catchup.mp4" ]]; then code=1; fi
    fi
    shot "exit-$code"
    timeout 15s adb logcat -d > "$EVIDENCE/logcat.txt"
    kill "$logcat_stream_pid" 2>/dev/null
    printf 'DRIVER EXIT: %s\n' "$code"
    exit "$code"
}
trap finish EXIT
if [[ "$feature" == session-routing.md && "$package" == dev.multiprompt.companion.debug ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true && "$(adb get-serialno)" == emulator-* ]]
    : > "$FIXTURE/recorder.pid"
    adb shell 'screenrecord --time-limit 0 --bit-rate 2000000 /sdcard/chat-catchup.mp4 & echo $!; wait' > "$FIXTURE/recorder.pid" &
    recorder_client=$!
    for attempt in {1..30}; do
        recorder_pid=$(head -1 "$FIXTURE/recorder.pid" | tr -d '\r')
        [[ "$recorder_pid" =~ ^[0-9]+$ ]] && break
        sleep 1
    done
    [[ "$recorder_pid" =~ ^[0-9]+$ ]]
fi
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
browser_handoff() {
    local action=$1 evidence=$2 resolved attempt
    resolved=$(adb shell cmd package resolve-activity --brief --user 0 \
        -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
        -d https://account.multiprompt.dev/login | tr -d '\r' | tail -1)
    [[ "$resolved" == */* && "$resolved" != android/* ]]
    tap_text "$action"
    for attempt in {1..30}; do
        if adb shell dumpsys activity activities | python3 "$scripts/browser-handoff.py" "$resolved" "$package" > "$EVIDENCE/$evidence.txt"; then
            shot "$evidence"
            return
        fi
        sleep 1
    done
    printf 'Timed out waiting for the resolved authorization browser\n' >&2
    return 1
}
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
        if "$scripts/ssh-ready.sh"; then return; fi
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
    local attempt
    local -a clear_keys
    for attempt in 1 2 3; do
        tap_node field "$1"
        select_node focused-field "$1" >/dev/null || continue
        read -r -a clear_keys < <(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" clear-field "$1")
        adb shell input keyevent "${clear_keys[@]}"
        adb shell input text "$2"
        dump_ui
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value "$1" "${2//%s/ }"; then
            adb shell input keyevent KEYCODE_BACK
            return
        fi
    done
    printf 'Field input did not match: %s\n' "$1" >&2
    return 1
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
    local expected=${1:-Inbox}
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
    for attempt in {1..30}; do
        printf 'Relaunch navigation attempt %s/30\n' "$attempt"
        dump_ui
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "$expected" >/dev/null 2>&1; then break; fi
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text 'Use without account (debug)' >/dev/null 2>&1; then
            tap_text 'Use without account (debug)'
            break
        fi
        sleep 1
    done
    wait_for_text "$expected"
    doctor
    if [[ "$expected" == Inbox ]]; then tap_text All; fi
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
    adb shell dumpsys package "$package" > "$EVIDENCE/package.txt"
    sed -n 's/^[[:space:]]*versionName=//p' "$EVIDENCE/package.txt" | grep -Fx "$EXPECTED_VERSION"
    adb shell input keyevent KEYCODE_HOME
    sleep 3
    dump_ui
    if ! python3 "$scripts/launcher-icon.py" home "$EVIDENCE/current.xml"; then
        local size w h x y attempt
        size=$(adb shell wm size | grep -Eo '[0-9]+x[0-9]+' | tail -1)
        w=${size%x*}
        h=${size#*x}
        adb shell input swipe $((w / 2)) $((h * 7 / 10)) $((w / 2)) $((h / 4)) 500
        sleep 2
        for attempt in {1..8}; do
            dump_ui
            if python3 "$scripts/launcher-icon.py" find "$EVIDENCE/current.xml"; then break; fi
            adb shell input swipe $((w / 2)) $((h * 7 / 10)) $((w / 2)) $((h / 4)) 500
            sleep 2
        done
        shot launcher-drawer
        python3 "$scripts/launcher-icon.py" check "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-launcher-drawer.png" "$EVIDENCE/launcher-icon-crop.png" "$EVIDENCE/launcher-icon-result.json"
        read -r x y < <(python3 "$scripts/launcher-icon.py" position "$EVIDENCE/current.xml")
        adb shell input touchscreen draganddrop "$x" "$y" $((w / 2)) $((h / 2)) 1800
        adb shell input keyevent KEYCODE_HOME
        sleep 2
    fi
    shot launcher-home-icon
    python3 "$scripts/launcher-icon.py" home "$EVIDENCE/current.xml"
    python3 "$scripts/launcher-icon.py" check "$EVIDENCE/current.xml" "$EVIDENCE/$(printf '%02d' "$step")-launcher-home-icon.png" "$EVIDENCE/launcher-icon-crop.png" "$EVIDENCE/launcher-icon-result.json"
    local aapt2 icon_file monochrome_file
    aapt2=$(find "$ANDROID_HOME/build-tools" -name aapt2 | sort | tail -1)
    "$aapt2" dump resources "$VERIFY_APK" > "$EVIDENCE/apk-resources.txt"
    icon_file=$(python3 "$scripts/launcher-icon.py" resource-file "$EVIDENCE/apk-resources.txt" mipmap/ic_launcher)
    "$aapt2" dump xmltree --file "$icon_file" "$VERIFY_APK" | tee "$EVIDENCE/adaptive-icon-tree.txt"
    grep -q 'monochrome' "$EVIDENCE/adaptive-icon-tree.txt"
    monochrome_file=$(python3 "$scripts/launcher-icon.py" resource-file "$EVIDENCE/apk-resources.txt" drawable/ic_launcher_monochrome)
    "$aapt2" dump xmltree --file "$monochrome_file" "$VERIFY_APK" > "$EVIDENCE/monochrome-icon-tree.txt"
    grep -q 'E: vector' "$EVIDENCE/monochrome-icon-tree.txt"
    printf 'ANDROID_LAUNCHER_ROBOT_ICON_PROVEN\n'
}

adb shell settings put global hide_error_dialogs 1
adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"
if [[ "$feature" == launcher-icon.md ]]; then
    launcher_icon
    exit 0
fi
if [[ "$package" == dev.multiprompt.companion || "$package" == dev.multiprompt.companion.m3 ]]; then
    if [[ "$package" == dev.multiprompt.companion.m3 ]]; then [[ "$feature" == settings-navigation.md || "$feature" == chat-resync.md || "$feature" == session-favorites.md || "$feature" == chat-queued.md || "$feature" == session-routing.md ]]; fi
    [[ "$feature" == settings-navigation.md || "$feature" == chat-queued.md || "$feature" == chat-choices.md || "$feature" == chat-reactions.md || "$feature" == chat-working.md || "$feature" == terminal.md || "$feature" == robot-faces.md || "$feature" == chat-cancel.md || "$feature" == session-gestures.md || "$feature" == session-summaries.md || "$feature" == chat-resync.md || "$feature" == session-favorites.md || "$feature" == session-routing.md ]]
    wait_for_text Inbox
    doctor
else
    wait_for_text 'Sign in to see your servers, workspaces and sessions.'
    doctor
    dump_ui
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" version "$EXPECTED_VERSION" > "$EVIDENCE/version-label.txt"
    shot sign-in
fi
if [[ "$feature" == sign-in.md ]]; then
    browser_handoff 'Sign in' access-browser
    printf 'SIGN-IN HANDOFF VERIFIED; OTP completion is NOT verified\n'
    exit 0
fi
if [[ "$package" == dev.multiprompt.companion.debug ]]; then tap_text 'Use without account (debug)'; fi
wait_for_text Inbox
if [[ "$feature" == settings-navigation.md ]]; then
    shot settings-home
    tap_desc 'Session list actions'
    dump_ui
    if ! python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent Hosts || ! python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent Workspaces; then
        echo SETTINGS_NAVIGATION_RETIRED_DESTINATION | tee "$EVIDENCE/settings-baseline.txt"
        exit 1
    fi
    tap_text Settings
    dump_ui
    if ! python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains 'Host mapping'; then
        echo SETTINGS_NAVIGATION_MISSING_HOST_MAPPING | tee "$EVIDENCE/settings-baseline.txt"
        exit 1
    fi
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains 'Switch / renew login'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains 'Sign out'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains Update
    shot settings-preferences
    for attempt in {1..5}; do
        dump_ui
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text 'Default Reader font size' >/dev/null 2>&1 &&
            python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text Large >/dev/null 2>&1; then break; fi
        read -r x1 y1 x2 y2 < <(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" scroll-back "")
        adb shell input swipe "$x2" "$y2" "$x1" "$y1" 500
    done
    dump_ui
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text 'Default Reader font size'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text Large
    shot settings-preferences-bottom
    for attempt in {1..5}; do
        if select_node text Update >/dev/null 2>&1; then break; fi
        read -r x1 y1 x2 y2 < <(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" scroll-back "")
        adb shell input swipe "$x1" "$y1" "$x2" "$y2" 500
    done
    tap_text Update
    wait_for_text 'App updates'
    adb shell input keyevent KEYCODE_BACK
    wait_for_text Inbox
fi
tap_text All
seed_host() {
    adb shell am force-stop "$package"
    set +x
    if [[ "$package" == dev.multiprompt.companion.debug ]]; then
        adb shell am instrument -w -e class dev.multiprompt.companion.account.SeedHostDeviceTest#importsCliHostFixture \
            -e host_label "$1" -e ssh_host "$2" -e ssh_port "$3" -e ssh_user "$4" \
            -e ssh_private_key "$(base64 -w0 "$FIXTURE/user-key")" \
            "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$EVIDENCE/host-seed.txt"
        grep -F 'OK (1 test)' "$EVIDENCE/host-seed.txt"
    else
        adb shell am instrument -w -e mode host-seed \
            -e host_label "$1" -e ssh_host "$2" -e ssh_port "$3" -e ssh_user "$4" \
            -e ssh_private_key "$(base64 -w0 "$FIXTURE/user-key")" \
            dev.multiprompt.verification/dev.multiprompt.verification.Seed > "$EVIDENCE/host-seed.txt"
        grep -F 'ISOLATED_HOST_SEEDED' "$EVIDENCE/host-seed.txt"
        grep -F 'INSTRUMENTATION_CODE: -1' "$EVIDENCE/host-seed.txt"
    fi
    set -x
    if [[ "$feature" == hosts-retired.md ]]; then
        grep -F 'OK (1 test)' "$EVIDENCE/host-seed.txt"
        cp "$EVIDENCE/host-seed.txt" "$EVIDENCE/retired-profile-present.txt"
    fi
    relaunch_app "${5:-Trust $1?}"
}
if [[ "$feature" == hosts-retired.md ]]; then
    retired=45.151.123.11
    sudo iptables -I OUTPUT -d "$retired" -p tcp -j REJECT --reject-with tcp-reset
    sudo iptables -S OUTPUT > "$EVIDENCE/retired-address-blocked.txt"
    seed_host 'Inne CRO' "$retired" 22 valentin Inbox
    for attempt in {1..10}; do
        dump_ui
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains Inbox
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks unreachable
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks 'Inne CRO'
        sleep 1
    done
    adb shell am force-stop "$package"
    adb shell am instrument -w -e class dev.multiprompt.companion.account.SeedHostDeviceTest#importsCliHostFixture \
        -e verify_retired_absent true "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$EVIDENCE/retired-profile-absent.txt"
    grep -F 'OK (1 test)' "$EVIDENCE/retired-profile-absent.txt"
    relaunch_app
    tap_desc 'Session list actions'
    tap_text Settings
    tap_text 'Host mapping'
    wait_for_text '2 · Inne CRO: Not mapped'
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" lacks "$retired"
    shot retired-host-removed
    sudo iptables -L OUTPUT -v -n > "$EVIDENCE/retired-address-attempts.txt"
    printf 'RETIRED HOST REMOVED ON RELAUNCH VERIFIED\n'
    exit 0
fi
seed_host Verify 10.0.2.2 2222 verifyssh
wait_for_text 'Trust Verify?'
shot compare-host-key
fingerprint=$(cat "$EVIDENCE/host-fingerprint.txt")
grep -F "ssh-ed25519 $fingerprint" "$EVIDENCE/current.xml"
if sudo grep -q 'Accepted publickey' "$FIXTURE/sshd.log"; then
    printf 'FAIL: authentication occurred before explicit trust\n' >&2
    exit 1
fi
tap_text 'Trust and connect'
wait_for_text Inbox
tap_text All
if ! wait_for_text verify-session; then
    printf 'Trusted host did not expose verify-session in All\n' | tee -a "$EVIDENCE/trust-retries.txt"
    shot trust-retry-1
    exit 1
fi
shot sessions

if [[ "$feature" == settings-navigation.md ]]; then
    tap_desc 'Session list actions'
    tap_text Settings
    tap_text 'Host mapping'
    wait_for_text 'Desktop tmux hosts'
    tap_text '1 · Contabo UK: Not mapped'
    tap_text 'Verify · verifyssh@10.0.2.2'
    wait_for_text '1 · Contabo UK: Verify (verifyssh)'
    shot mapping-explicit
    tap_text Done
    adb shell input keyevent KEYCODE_BACK
    relaunch_app
    tap_desc 'Session list actions'
    tap_text Settings
    tap_text 'Host mapping'
    wait_for_text '1 · Contabo UK: Verify (verifyssh)'
    shot mapping-restarted
    tap_text '1 · Contabo UK: Verify (verifyssh)'
    tap_text Automatic
    wait_for_text '1 · Contabo UK: Not mapped'
    tap_text Done
    adb shell input keyevent KEYCODE_BACK
    relaunch_app
    tap_desc 'Session list actions'
    tap_text Settings
    tap_text 'Host mapping'
    wait_for_text '1 · Contabo UK: Not mapped'
    shot mapping-automatic-restarted
    tap_text Done
    browser_handoff 'Switch / renew login' settings-browser-handoff
    adb shell input keyevent KEYCODE_BACK
    wait_for_text 'Session list settings'
    tap_text 'Sign out'
    wait_for_text 'Sign in'
    shot settings-signed-out
    browser_handoff 'Sign in' settings-sign-in-browser-handoff
    renew_evidence="$EVIDENCE/settings-browser-handoff.txt"
    signin_evidence="$EVIDENCE/settings-sign-in-browser-handoff.txt"
    if [[ ! -s "$renew_evidence" || ! -s "$signin_evidence" ]]; then
        printf 'Missing browser handoff evidence\n' >&2
        exit 1
    fi
    mapfile -t renew_states < <(grep '^state_sha256=' "$renew_evidence")
    mapfile -t signin_states < <(grep '^state_sha256=' "$signin_evidence")
    if [[ ${#renew_states[@]} -ne 1 || ${#signin_states[@]} -ne 1 ]] ||
        [[ ! "${renew_states[0]:-}" =~ ^state_sha256=[0-9a-f]{64}$ ]] ||
        [[ ! "${signin_states[0]:-}" =~ ^state_sha256=[0-9a-f]{64}$ ]] ||
        [[ "${renew_states[0]}" == "${signin_states[0]}" ]]; then
        printf 'Invalid or reused browser authorization state\n' >&2
        exit 1
    fi
    printf 'SETTINGS JOURNEY VERIFIED; OTP completion is NOT verified\n'
    exit 0
fi

if [[ "$feature" == session-favorites.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true && "$(adb get-serialno)" == emulator-* ]]
    [[ "$package" == dev.multiprompt.companion || "$package" == dev.multiprompt.companion.m3 ]]
    [[ "$(adb shell settings get global http_proxy | tr -d '\r')" == 10.0.2.2:8788 ]]
    wait_for_text verify-keep
    sudo grep 'Accepted publickey for verifyssh' "$FIXTURE/sshd.log" > "$EVIDENCE/ssh-auth.txt"
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh bash -c '
        [[ "$GITHUB_ACTIONS" == true && "$HOME" == /home/verifyssh && "$(id -u)" -ne 0 && "$(id -un)" == verifyssh ]]
        tmux has-session -t verify-session && tmux has-session -t verify-keep
    '
    adb shell am force-stop "$package"
    adb shell am instrument -w -e mode favorites-seed dev.multiprompt.verification/dev.multiprompt.verification.Seed | tr -d '\r' > "$FIXTURE/favorites-seed.txt"
    grep -Fx 'INSTRUMENTATION_RESULT: result=ISOLATED_FAVORITES_SEEDED' "$FIXTURE/favorites-seed.txt"
    grep -Fx 'INSTRUMENTATION_CODE: -1' "$FIXTURE/favorites-seed.txt"
    relaunch_app
    tap_text Favorites
    wait_for_text verify-session
    wait_for_text verify-keep
    dump_ui
    python3 tools/account-journey/ui.py "$EVIDENCE/current.xml" selected Favorites
    shot favorites-before
    read -r row_x row_y <<< "$(select_node text verify-session)"
    adb shell input swipe "$row_x" "$row_y" "$row_x" "$row_y" 1000
    wait_for_text 'End session'
    shot favorites-remove-menu
    if ! select_node text Unfavorite >/dev/null 2>&1; then
        if select_node text Unstar >/dev/null 2>&1; then
            printf 'FAVORITE_DESKTOP_WORDS_MISSING: expected Unfavorite, found Unstar\n' | tee "$EVIDENCE/favorite-failure.txt"
        fi
        exit 1
    fi
    tap_text Unfavorite
    removal_started=$SECONDS
    wait_for_absence verify-session
    (( SECONDS - removal_started <= 10 ))
    wait_for_text verify-keep
    dump_ui
    python3 tools/account-journey/ui.py "$EVIDENCE/current.xml" selected Favorites
    python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent verify-session
    shot favorites-removed
    favorites_snapshot() {
        local expected=$1 output=$2
        adb shell am instrument -w -e mode favorites-snapshot dev.multiprompt.verification/dev.multiprompt.verification.Seed | tr -d '\r' > "$FIXTURE/favorites-snapshot.txt"
        python3 - "$FIXTURE/favorites-snapshot.txt" "$EVIDENCE/$output.json" "$expected" <<'PYFAVORITES'
import json
import re
import sys
from pathlib import Path
raw = Path(sys.argv[1]).read_text()
assert 'INSTRUMENTATION_RESULT: result=ISOLATED_FAVORITES_SNAPSHOT' in raw
assert 'INSTRUMENTATION_CODE: -1' in raw
matches = re.findall(r'^INSTRUMENTATION_RESULT: favorites=(.*)$', raw, re.M)
assert len(matches) == 1
states = json.loads(matches[0])
assert states == {'verify-session': sys.argv[3] == 'true', 'verify-keep': True}, states
Path(sys.argv[2]).write_text(json.dumps(states, sort_keys=True) + '\n')
PYFAVORITES
    }
    favorites_snapshot false favorites-removed-state
    relaunch_app
    tap_text All
    wait_for_text verify-session
    read -r row_x row_y <<< "$(select_node text verify-session)"
    adb shell input swipe "$row_x" "$row_y" "$row_x" "$row_y" 1000
    wait_for_text 'End session'
    shot favorites-add-menu
    if ! select_node text Favorite >/dev/null 2>&1; then
        if select_node text Star >/dev/null 2>&1; then
            printf 'FAVORITE_DESKTOP_WORDS_MISSING: expected Favorite, found Star\n' | tee "$EVIDENCE/favorite-failure.txt"
        fi
        exit 1
    fi
    tap_text Favorite
    tap_text Favorites
    wait_for_text verify-session
    wait_for_text verify-keep
    dump_ui
    python3 tools/account-journey/ui.py "$EVIDENCE/current.xml" selected Favorites
    shot favorites-restored
    favorites_snapshot true favorites-restored-state
    relaunch_app
    tap_text Favorites
    wait_for_text verify-session
    wait_for_text verify-keep
    shot favorites-after-restart
    sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh bash -c '
        [[ "$GITHUB_ACTIONS" == true && "$HOME" == /home/verifyssh && "$(id -u)" -ne 0 && "$(id -un)" == verifyssh ]]
        tmux has-session -t verify-session && tmux has-session -t verify-keep
    '
    printf 'SIGNED_OFFLINE_FAVORITES_OK\n' | tee "$EVIDENCE/favorites-result.txt"
    exit 0
fi

if [[ "$feature" == session-summaries.md ]]; then
    wait_for_text 'Lauren Tan profile is done, checked and pushed.'
    shot summary-reply
    sudo -u verifyssh tmux send-keys -t verify-session summary-working Enter
    wait_for_text 'Working: Reading CI logs'
    shot summary-working
    started=$(date +%s%3N)
    sudo -u verifyssh tmux send-keys -t verify-session summary-next Enter
    wait_for_text 'The release is ready.'
    elapsed=$(( $(date +%s%3N) - started ))
    test "$elapsed" -le 5000
    shot summary-updated
    python3 - "$EVIDENCE/current.xml" <<'PYSUMMARY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
texts = [node.get('text', '') for node in root.iter('node')]
assert 'The release is ready.' in texts
assert not any('Opus 5.5' in text or '[CAVEMAN]' in text or 'valentin@vps' in text for text in texts)
PYSUMMARY
    printf 'SUMMARY_REFRESH_OK elapsed_ms=%s\n' "$elapsed" | tee "$EVIDENCE/summary-result.txt"
    exit 0
fi
if [[ "$feature" == robot-faces.md ]]; then
    select_node desc 'Robot verify-session'
    shot robot-inbox
fi
sudo grep 'Accepted publickey for verifyssh' "$FIXTURE/sshd.log" > "$EVIDENCE/ssh-auth.txt"
if [[ "$feature" == hosts.md ]]; then
    tap_desc 'Session list actions'
    tap_text Settings
    tap_text 'Host mapping'
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
    tap_text Settings
    tap_text 'Host mapping'
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
    wait_for_routing_destination() {
        local name=$1 attempt
        for attempt in {1..30}; do
            dump_ui
            if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text "$name" >/dev/null 2>&1 &&
               python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field "Message $name" >/dev/null 2>&1; then
                return
            fi
            sleep 1
        done
        printf 'Timed out waiting for exact routing destination: %s\n' "$name" >&2
        return 1
    }
    wait_for_text verify-other
    tap_text verify-session
    wait_for_routing_destination verify-session
    fill 'Message verify-session' UI_ONLY_A
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:UI_ONLY_A'
    shot session-a-sent
    tap_desc Back
    wait_for_text Inbox
    tap_text verify-other
    wait_for_routing_destination verify-other
    fill 'Message verify-other' UI_ONLY_B
    tap_desc 'Send prompt'
    for attempt in {1..30}; do
        if sudo grep -Fx 'VERIFY_RECEIVED:UI_ONLY_B' /home/verifyssh/other-receipts.txt; then break; fi
        sleep 1
    done
    sudo grep -Fx 'VERIFY_RECEIVED:UI_ONLY_B' /home/verifyssh/other-receipts.txt
    shot session-b-sent
    if [[ "$package" == dev.multiprompt.companion.debug ]]; then
        adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
        adb shell am instrument -w -r -e class dev.multiprompt.companion.ui.ReaderSessionRoutingTest \
            dev.multiprompt.companion.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$EVIDENCE/routing-instrumentation.txt"
        grep -F 'OK (1 test)' "$EVIDENCE/routing-instrumentation.txt"
    fi
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/session-a-receipts.txt"
    sudo cat /home/verifyssh/other-receipts.txt > "$EVIDENCE/session-b-receipts.txt"
    python3 - "$EVIDENCE" "$package" <<'PYRECEIPTS'
from pathlib import Path
import sys
root = Path(sys.argv[1])
a = (root / 'session-a-receipts.txt').read_text().splitlines()
b = (root / 'session-b-receipts.txt').read_text().splitlines()
if sys.argv[2] == 'dev.multiprompt.companion.debug':
    assert a == ['VERIFY_RECEIVED:' + text for text in ('UI_ONLY_A', 'TYPED_A', 'VOICE_A')], a
    assert b == ['VERIFY_RECEIVED:' + text for text in ('UI_ONLY_B', 'TYPED_B', 'VOICE_B', 'AFTER_SWITCH_B')], b
    print('EMULATOR_ROUTING_OK')
else:
    assert a == ['VERIFY_RECEIVED:UI_ONLY_A'], a
    assert b == ['VERIFY_RECEIVED:UI_ONLY_B'], b
    print('SIGNED_ROUTING_UI_ONLY_POSITIVES_OK')
PYRECEIPTS
    if [[ "$package" == dev.multiprompt.companion.debug ]]; then
        adb pull /sdcard/Android/data/dev.multiprompt.companion.debug/files/routing-b.png "$EVIDENCE/routing-b.png"
    fi
    stale_routing_probe() {
        [[ "${GITHUB_ACTIONS:-}" == true && "$FIXTURE" == "$RUNNER_TEMP/verify-ssh" && "$(adb get-serialno)" == emulator-* ]]
        printf '%s\n' 'UNCLASSIFIED' > "$EVIDENCE/routing-classifier.txt"
        printf '%s\n' 'IGSH1230_STALE_A_FORBIDDEN_7291' > "$EVIDENCE/routing-marker.txt"
        [[ "$(sudo -u verifyssh tmux display-message -p '#{pid}')" == "$(cat "$FIXTURE/tmux.pid")" ]]
        [[ "$(ps -o uid= -p "$(cat "$FIXTURE/tmux.pid")" | tr -d ' ')" == "$(id -u verifyssh)" ]]
        relaunch_app
        wait_for_text verify-session
        tap_text verify-session
        wait_for_routing_destination verify-session
        fill 'Message verify-session' IGSH1230_STALE_A_FORBIDDEN_7291
        shot routing-stale-a-before
        python3 - "$EVIDENCE/current.xml" <<'PYDRAFT'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == 'IGSH1230_STALE_A_FORBIDDEN_7291' for n in root.iter('node'))
PYDRAFT
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh bash -c '
            set -euo pipefail
            [[ "$GITHUB_ACTIONS" == true && "$HOME" == /home/verifyssh && "$(id -un)" == verifyssh ]]
            a=$(tmux display-message -p -t "=verify-session:" "#{session_id} #{session_name}")
            b=$(tmux display-message -p -t "=verify-other:" "#{session_id} #{session_name}")
            [[ "$a" =~ ^\$[0-9]+\ verify-session$ && "$b" =~ ^\$[0-9]+\ verify-other$ ]]
            printf "%s\n" "$a" > /home/verifyssh/routing-a-id
            printf "%s\n" "$b" > /home/verifyssh/routing-b-id
            tmux new-session -d -s verify-session-neighbor -x 100 -y 32 "env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/routing-canary.py"
            tmux set-window-option -t "=verify-session-neighbor:" automatic-rename off
            tmux rename-window -t "=verify-session-neighbor:" verify-session-neighbor
            tmux display-message -p -t "=verify-session-neighbor:" "#{session_id} #{session_name}" > /home/verifyssh/routing-c-id
        '
        for attempt in {1..30}; do
            if sudo test -f /home/verifyssh/canary-ready; then break; fi
            sleep 1
        done
        sudo test -f /home/verifyssh/canary-ready
        for attempt in {1..30}; do
            sudo -u verifyssh tmux capture-pane -p -t '=verify-session-neighbor:' > "$EVIDENCE/canary-screen-before.txt"
            if grep -Fx CANARY_SCREEN_IGSH1230 "$EVIDENCE/canary-screen-before.txt"; then break; fi
            sleep 1
        done
        grep -Fx CANARY_SCREEN_IGSH1230 "$EVIDENCE/canary-screen-before.txt"
        sudo -u verifyssh tmux send-keys -t '=verify-session-neighbor:' -l 'CANARY_CONTROL_7291'
        sudo -u verifyssh tmux send-keys -t '=verify-session-neighbor:' Enter
        for attempt in {1..30}; do
            if sudo grep -Fx 'CANARY_RECEIVED:CANARY_CONTROL_7291' /home/verifyssh/canary-receipts.txt; then break; fi
            sleep 1
        done
        sudo grep -Fx 'CANARY_RECEIVED:CANARY_CONTROL_7291' /home/verifyssh/canary-receipts.txt
        sudo cat /home/verifyssh/canary-raw.bin > "$EVIDENCE/canary-raw-before.bin"
        sudo cat /home/verifyssh/canary-receipts.txt > "$EVIDENCE/canary-receipts-before.txt"
        grep -aF CANARY_CONTROL_7291 "$EVIDENCE/canary-raw-before.bin"
        sudo -u verifyssh tmux capture-pane -p -t '=verify-other:' > "$EVIDENCE/session-b-screen-before.txt"
        sudo cp /home/verifyssh/routing-{a,b,c}-id "$EVIDENCE/"
        sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh bash -c '
            set -euo pipefail
            [[ "$GITHUB_ACTIONS" == true && "$HOME" == /home/verifyssh && "$(id -un)" == verifyssh ]]
            read -r id name < /home/verifyssh/routing-a-id
            [[ "$name" == verify-session && "$id" =~ ^\$[0-9]+$ ]]
            [[ "$(tmux display-message -p -t "$id" "#{session_id} #{session_name}")" == "$id $name" ]]
            tmux kill-session -t "$id"
            if tmux has-session -t "=verify-session" 2>/dev/null; then exit 1; fi
            for label in b c; do
                read -r id name < "/home/verifyssh/routing-$label-id"
                [[ "$(tmux display-message -p -t "$id" "#{session_id} #{session_name}")" == "$id $name" ]]
            done
        '
        printf '%s\n' 'A_ABSENT_B_C_ALIVE' > "$EVIDENCE/routing-preconditions.txt"
        dump_ui
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text verify-session
        python3 - "$EVIDENCE/current.xml" <<'PYPREMOUNT'
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))
assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == 'IGSH1230_STALE_A_FORBIDDEN_7291' for n in nodes)
PYPREMOUNT
        shot routing-a-ended-before-send
        sudo cat /home/verifyssh/commands.log > "$EVIDENCE/commands-before-probe.txt"
        tap_desc 'Send prompt'
        for attempt in {1..30}; do
            sudo cat /home/verifyssh/canary-raw.bin > "$EVIDENCE/canary-raw-after.bin"
            sudo cat /home/verifyssh/canary-receipts.txt > "$EVIDENCE/canary-receipts-after.txt"
            dump_ui
            if grep -aFq IGSH1230_STALE_A_FORBIDDEN_7291 "$EVIDENCE/canary-raw-after.bin" && grep -Fxq 'CANARY_RECEIVED:IGSH1230_STALE_A_FORBIDDEN_7291' "$EVIDENCE/canary-receipts-after.txt"; then break; fi
            if python3 - "$EVIDENCE/current.xml" <<'PYRESULT'
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))
assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == 'IGSH1230_STALE_A_FORBIDDEN_7291' for n in nodes)
assert any('Could not send the prompt' in n.get('text', '') for n in nodes)
PYRESULT
            then break; fi
            sleep 1
        done
        sleep 3
        sudo cat /home/verifyssh/canary-raw.bin > "$EVIDENCE/canary-raw-after.bin"
        sudo cat /home/verifyssh/canary-receipts.txt > "$EVIDENCE/canary-receipts-after.txt"
        shot routing-after-send
        sudo cat /home/verifyssh/commands.log > "$EVIDENCE/commands-after-probe.txt"
        tail -c +$(( $(wc -c < "$EVIDENCE/commands-before-probe.txt") + 1 )) "$EVIDENCE/commands-after-probe.txt" > "$EVIDENCE/commands-probe.txt"
        grep -F 'tmux paste-buffer' "$EVIDENCE/commands-probe.txt" | grep -F 'verify-session:'
        sudo -u verifyssh tmux capture-pane -p -t '=verify-session-neighbor:' > "$EVIDENCE/canary-screen-after.txt"
        sudo -u verifyssh tmux capture-pane -p -t '=verify-other:' > "$EVIDENCE/session-b-screen-after.txt"
        sudo cat /home/verifyssh/other-receipts.txt > "$EVIDENCE/session-b-receipts-after.txt"
        cmp "$EVIDENCE/session-b-receipts.txt" "$EVIDENCE/session-b-receipts-after.txt"
        cmp "$EVIDENCE/session-b-screen-before.txt" "$EVIDENCE/session-b-screen-after.txt"
        if grep -aFq IGSH1230_STALE_A_FORBIDDEN_7291 "$EVIDENCE/canary-raw-after.bin" && grep -Fxq 'CANARY_RECEIVED:IGSH1230_STALE_A_FORBIDDEN_7291' "$EVIDENCE/canary-receipts-after.txt"; then
            printf '%s\n' 'SELECTED_SESSION_FELL_BACK_TO_NEIGHBOR' | tee "$EVIDENCE/routing-classifier.txt"
            return 1
        fi
        cmp "$EVIDENCE/canary-raw-before.bin" "$EVIDENCE/canary-raw-after.bin"
        cmp "$EVIDENCE/canary-receipts-before.txt" "$EVIDENCE/canary-receipts-after.txt"
        python3 - "$EVIDENCE/current.xml" <<'PYORACLE'
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))
assert any(n.get('text') == 'verify-session' for n in nodes)
assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == 'IGSH1230_STALE_A_FORBIDDEN_7291' for n in nodes)
assert any('Could not send the prompt' in n.get('text', '') for n in nodes)
assert not any('CANARY_SCREEN_IGSH1230' in n.get('text', '') for n in nodes)
PYORACLE
        if [[ "$package" == dev.multiprompt.companion.debug ]]; then
            printf '%s\n' 'EXACT_TARGET_STALE_SEND_REJECTED' | tee "$EVIDENCE/routing-classifier.txt"
        else
            printf '%s\n' 'EXACT_TARGET_STALE_SEND_REJECTED_UI_ONLY' | tee "$EVIDENCE/routing-classifier.txt"
        fi
    }
    stale_routing_probe
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
if [[ "$feature" == chat-working.md ]]; then
    wait_for_text WORKING_DOTS_READY
    python3 tools/working-dots/drive.py
    exit 0
fi
if [[ "$feature" == session-gestures.md || "$feature" == session-summaries.md ]]; then
    wait_for_text GESTURE_REPLY_TARGET
    python3 tools/session-gestures/drive.py
    exit 0
fi
if [[ "$feature" == chat-choices.md ]]; then
    wait_for_text 'Which route should we use?'
    shot structured-question-before
    tap_node text-containing 'Use the current account'
    tap_text 'Submit answer'
    wait_receipt 'VERIFY_QUESTION_KEY:2'
    wait_for_text 'What should we keep?'
    shot multiple-question-before
    tap_node text-containing 'History'
    tap_node text-containing 'Settings'
    tap_node field Other
    adb shell input text Custom
    adb shell input keyevent KEYCODE_BACK
    tap_text 'Submit answer'
    wait_receipt 'VERIFY_MULTI:{"other": "Custom", "selected": [0, 1]}'
    wait_for_text 'Structured questions answered.'
    wait_for_absence 'Submit answer'
    shot structured-questions-after
    adb shell input swipe 190 300 190 680 500
    wait_for_text 'First route'
    shot ordinary-lists
    python3 - "$EVIDENCE/current.xml" <<'PLAINLISTS'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
parents = {child: parent for parent in root.iter() for child in parent}
for text in ['First route', 'Second route', 'Ordinary bullet']:
    node = next(node for node in root.iter('node') if node.get('text') == text)
    while node is not None:
        assert node.get('clickable') != 'true', (text, node.attrib)
        node = parents.get(node)
assert any(node.get('text') == '1.' for node in root.iter('node'))
print('ORDINARY_LISTS_HAVE_NO_ANSWER_CARDS')
PLAINLISTS
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/receipts.txt"
    python3 - "$EVIDENCE/receipts.txt" <<'CHOICES'
import sys
from pathlib import Path
rows = Path(sys.argv[1]).read_text().splitlines()
assert rows == ["VERIFY_QUESTION_KEY:2", 'VERIFY_MULTI:{"other": "Custom", "selected": [0, 1]}'], rows
print("STRUCTURED_QUESTION_SENDS_VERIFIED")
CHOICES
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/tmux-final.txt"
    printf 'FEATURE VERIFIED: %s\n' "$feature"
    exit 0
fi
if [[ "$feature" == chat-cancel.md ]]; then
    if [[ "$package" != dev.multiprompt.companion ]]; then
        adb shell 'echo $$ > /sdcard/cancel-record.pid; exec screenrecord --time-limit 180 /sdcard/cancel-proof.mp4' &
        recording_client=$!
    fi
    wait_for_text VERIFY_READY_1010
    wait_for_text 'Ready for cancel tests.'
    shot cancel-ready
    for word in CANCEL_KEEP_ONE CANCEL_TARGET_TWO CANCEL_KEEP_THREE; do
        fill 'Message verify-session' "$word"
        tap_desc 'Send prompt'
        wait_for_text "$word"
    done
    shot cancel-waiting
    python3 "$scripts/queued.py" "$EVIDENCE/current.xml" 3 WAITING
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/cancel-draft-before.txt"
    grep -Fx '❯ FIXTURE_OWNED_DRAFT' "$EVIDENCE/cancel-draft-before.txt"
    sudo test ! -s /home/verifyssh/receipts.txt
    read -r cancel_x cancel_y <<< "$(select_node text CANCEL_TARGET_TWO)"
    adb shell input swipe "$cancel_x" "$cancel_y" "$cancel_x" "$cancel_y" 900
    wait_for_text 'Cancel message'
    shot cancel-menu
    tap_text 'Cancel message'
    wait_for_text Cancelled
    shot cancel-cancelled
    python3 "$scripts/queued.py" "$EVIDENCE/current.xml" 2 WAITING
    sudo test ! -s /home/verifyssh/receipts.txt
    sudo -u verifyssh touch /home/verifyssh/cancel-ready
    wait_receipt VERIFY_RECEIVED:CANCEL_KEEP_ONE
    wait_receipt VERIFY_RECEIVED:CANCEL_KEEP_THREE
    wait_for_text Delivered
    wait_for_absence Queued
    shot cancel-delivered
    if sudo grep -F CANCEL_TARGET_TWO /home/verifyssh/receipts.txt; then exit 1; fi
    python3 - <<'PROOF'
from pathlib import Path
import subprocess
rows = subprocess.check_output(['sudo', 'cat', '/home/verifyssh/receipts.txt'], text=True).splitlines()
assert rows == ['VERIFY_RECEIVED:CANCEL_KEEP_ONE', 'VERIFY_RECEIVED:CANCEL_KEEP_THREE'], rows
PROOF
    read -r cancel_x cancel_y <<< "$(select_node text CANCEL_KEEP_THREE)"
    adb shell input swipe "$cancel_x" "$cancel_y" "$cancel_x" "$cancel_y" 900
    wait_for_text Reply
    shot delivered-menu
    if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains 'Cancel message'; then exit 1; fi
    adb shell input keyevent 4
    if [[ "$package" != dev.multiprompt.companion ]]; then
        remote_recording=$(adb shell cat /sdcard/cancel-record.pid | tr -d '\r')
        [[ "$remote_recording" =~ ^[0-9]+$ ]]
        adb shell kill -2 "$remote_recording"
        wait "$recording_client"
        adb pull /sdcard/cancel-proof.mp4 "$EVIDENCE/cancel-proof.mp4"
    fi
    printf 'FEATURE VERIFIED: %s\n' "$feature"
    exit 0
fi

if [[ "$feature" == chat-codex.md ]]; then
    : > "$FIXTURE/recorder.pid"
    adb shell 'screenrecord --time-limit 180 --bit-rate 2000000 /sdcard/chat-catchup.mp4 & echo $!; wait' > "$FIXTURE/recorder.pid" &
    recorder_client=$!
    for attempt in {1..20}; do
        recorder_pid=$(tr -d '\r' < "$FIXTURE/recorder.pid")
        [[ "$recorder_pid" =~ ^[0-9]+$ ]] && break
        sleep 1
    done
    [[ "$recorder_pid" =~ ^[0-9]+$ ]]
    for mode in hax codex; do
        if [[ "$mode" == codex ]]; then
            adb shell input keyevent KEYCODE_BACK
            wait_for_text Inbox
            tap_text codex-native
        fi
        wait_for_text "CODEX_TRANSCRIPT_ONLY_$mode"
        shot "codex-$mode-desktop-post"
        destination=verify-session
        receipt=receipts.txt
        if [[ "$mode" == codex ]]; then destination=codex-native; receipt=codex-receipts.txt; fi
        fill "Message $destination" PHONE_CODEX
        tap_desc 'Send prompt'
        started=$(date +%s%N)
        wait_for_text "CODEX_REPLY_${mode}_PHONE_CODEX"
        finished=$(date +%s%N)
        sudo grep -Fx 'VERIFY_RECEIVED:PHONE_CODEX' "/home/verifyssh/$receipt" > "$EVIDENCE/$mode-receipt.txt"
        published=$(sudo cat "/home/verifyssh/$mode-reply-ns.txt")
        elapsed=$(( (finished - published) / 1000000 ))
        printf '%s\n' "$elapsed" > "$EVIDENCE/$mode-visible-ms.txt"
        [[ "$elapsed" -ge 0 && "$elapsed" -le 5000 ]]
        shot "codex-$mode-phone-reply"
        sudo -u verifyssh tmux capture-pane -pt "$destination" > "$EVIDENCE/$mode-terminal.txt"
        test -s "$EVIDENCE/$mode-terminal.txt"
        if grep -F "CODEX_REPLY_${mode}_PHONE_CODEX" "$EVIDENCE/$mode-terminal.txt"; then
            exit 1
        else
            [[ $? -eq 1 ]]
        fi
    done
    adb shell input keyevent KEYCODE_BACK
    wait_for_text Inbox
    tap_text claude-control
    wait_for_text 'CODEX_TRANSCRIPT_ONLY_claude'
    fill 'Message claude-control' CLAUDE_CONTROL
    tap_desc 'Send prompt'
    wait_for_text 'CODEX_REPLY_claude_CLAUDE_CONTROL'
    wait_for_absence Queued
    tap_desc 'Session actions'
    wait_for_text 'Resync chat'
    tap_text 'Resync chat'
    wait_for_text 'CODEX_REPLY_claude_CLAUDE_CONTROL'
    sudo grep -Fx 'VERIFY_RECEIVED:CLAUDE_CONTROL' /home/verifyssh/claude-receipts.txt > "$EVIDENCE/claude-receipt.txt"
    shot claude-compatible
    printf 'ANDROID_CODEX_READ_SEND_CLAUDE_OK\n' | tee "$EVIDENCE/codex-result.txt"
    exit 0
fi
if [[ "$feature" == chat-queued.md ]]; then
    [[ "${GITHUB_ACTIONS:-}" == true ]]
    wait_for_text VERIFY_READY_1008
elif [[ "$feature" == chat-resync.md ]]; then
    wait_for_text ACCEPTED_FOURTH
else
    wait_for_text VERIFY_READY_528
fi
shot reader-ready
if [[ "$feature" == robot-faces.md ]]; then
    select_node desc 'Robot verify-session'
    select_node text verify-session
    shot robot-chat-title
    printf 'SIGNED_ROBOT_INBOX_AND_CHAT_VERIFIED\n' | tee "$EVIDENCE/robot-result.txt"
    exit 0
fi
if [[ "$feature" == chat-queued.md ]]; then
    wait_for_text 'Ready for queued messages.'
    shot queued-ready
    : > "$FIXTURE/recorder.pid"
    adb shell 'screenrecord --time-limit 0 --bit-rate 2000000 /sdcard/chat-catchup.mp4 & echo $!; wait' > "$FIXTURE/recorder.pid" &
    recorder_client=$!
    for attempt in {1..20}; do
        recorder_pid=$(head -1 "$FIXTURE/recorder.pid")
        [[ "$recorder_pid" =~ ^[0-9]+$ ]] && break
        sleep 0.1
    done
    [[ "$recorder_pid" =~ ^[0-9]+$ ]]
    queue_states() {
        local attempt
        for attempt in {1..60}; do
            if sudo env GITHUB_ACTIONS="$GITHUB_ACTIONS" python3 "$scripts/queue-evidence.py" "$@" > "$EVIDENCE/queue-state.tmp" 2>/dev/null; then
                cat "$EVIDENCE/queue-state.tmp"
                return
            fi
            sleep 1
        done
        sudo env GITHUB_ACTIONS="$GITHUB_ACTIONS" python3 "$scripts/queue-evidence.py" "$@"
        return 1
    }
    queue_ui() {
        local expected=$1 name=$2 attempt
        for attempt in {1..30}; do
            dump_ui
            if python3 "$scripts/queued.py" "$EVIDENCE/current.xml" "$expected" "$name" >/dev/null 2>&1; then return; fi
            sleep 1
        done
        python3 "$scripts/queued.py" "$EVIDENCE/current.xml" "$expected" "$name"
    }
    provider_event() { sudo -u verifyssh env GITHUB_ACTIONS="$GITHUB_ACTIONS" HOME=/home/verifyssh python3 /home/verifyssh/queued-console.py "$1"; }
    fixture_counts() {
        python3 - "$1" "$2" <<'QUEUE_COUNTS'
import json
import subprocess
import sys
expected_inputs = [] if sys.argv[1] == '-' else sys.argv[1].split(',')
expected_receipts = [] if sys.argv[2] == '-' else sys.argv[2].split(',')
inputs = subprocess.check_output(['sudo', 'cat', '/home/verifyssh/receipts.txt'], text=True).splitlines()
assert inputs == ['VERIFY_RECEIVED:' + text for text in expected_inputs], inputs
raw = subprocess.check_output(['sudo', 'cat', '/home/verifyssh/.claude/projects/-home-verifyssh-queued-work/a1b2c3d4-1008-4000-8000-000000001008.jsonl'], text=True)
rows = [json.loads(line) for line in raw.splitlines()]
receipts = [row['attachment']['prompt'] for row in rows if row.get('type') == 'attachment' and row.get('attachment', {}).get('type') == 'queued_command' and not row.get('isSidechain')]
assert receipts == expected_receipts, receipts
QUEUE_COUNTS
    }
    resync_marker() {
        tap_desc 'Session actions'
        wait_for_text 'Resync chat'
        tap_text 'Resync chat'
        provider_event "marker:$1"
        wait_for_text "$1"
    }
    fill 'Message verify-session' WAITING_ONE
    tap_desc 'Send prompt'
    wait_for_text WAITING_ONE
    fill 'Message verify-session' WAITING_TWO
    tap_desc 'Send prompt'
    wait_for_text WAITING_TWO
    queue_states waiting queued queued | tee "$EVIDENCE/queue-pending.json"
    queue_ui 2 QUEUE_WAITING
    shot queued-waiting
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/draft-before.txt"
    grep -F '❯ FIXTURE_OWNED_DRAFT' "$EVIDENCE/draft-before.txt"
    fixture_counts - -
    provider_event clear-draft
    wait_receipt 'VERIFY_RECEIVED:WAITING_ONE'
    queue_states waiting submitted queued | tee "$EVIDENCE/queue-submitted.json"
    queue_ui 1 QUEUE_SUBMITTED
    shot queued-submitted
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/native-queue-blocked.txt"
    grep -F 'edit queued messages' "$EVIDENCE/native-queue-blocked.txt"
    fixture_counts WAITING_ONE -
    provider_event clear-hint
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/native-hint-cleared.txt"
    grep -E '^❯[[:space:]]*$' "$EVIDENCE/native-hint-cleared.txt"
    if grep -F 'edit queued messages' "$EVIDENCE/native-hint-cleared.txt"; then
        exit 1
    else
        [[ $? -eq 1 ]]
    fi
    resync_marker BEFORE_CONSUMPTION_MARKER
    queue_states waiting submitted queued | tee "$EVIDENCE/queue-after-resync.json"
    queue_ui 1 QUEUE_SUBMITTED
    fixture_counts WAITING_ONE -
    shot queued-after-resync
    provider_event consume:WAITING_ONE
    wait_receipt 'VERIFY_RECEIVED:WAITING_TWO'
    queue_states waiting delivered submitted | tee "$EVIDENCE/queue-first-delivered.json"
    queue_ui 0 QUEUE_FIRST_DELIVERED
    fixture_counts WAITING_ONE,WAITING_TWO WAITING_ONE
    shot queued-first-delivered
    provider_event consume:WAITING_TWO
    queue_states waiting delivered delivered | tee "$EVIDENCE/queue-both-delivered.json"
    queue_ui 0 QUEUE_BOTH_DELIVERED
    fixture_counts WAITING_ONE,WAITING_TWO WAITING_ONE,WAITING_TWO
    shot queued-both-delivered
    provider_event reply
    wait_for_text 'Both messages received.'
    fill 'Message verify-session' FOLLOWUP
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:FOLLOWUP'
    queue_states followup submitted | tee "$EVIDENCE/queue-followup-submitted.json"
    queue_ui 0 FOLLOWUP_SUBMITTED
    fixture_counts WAITING_ONE,WAITING_TWO,FOLLOWUP WAITING_ONE,WAITING_TWO
    provider_event consume:FOLLOWUP
    queue_states followup delivered | tee "$EVIDENCE/queue-followup-delivered.json"
    wait_for_text FOLLOWUP
    queue_ui 0 FOLLOWUP_DELIVERED
    fixture_counts WAITING_ONE,WAITING_TWO,FOLLOWUP WAITING_ONE,WAITING_TWO,FOLLOWUP
    adb shell kill -INT "$recorder_pid"
    wait "$recorder_client" || true
    adb pull /sdcard/chat-catchup.mp4 "$EVIDENCE/chat-catchup.mp4"
    recorder_pid=
    python3 "$scripts/catchup.py" &
    catchup_pid=$!
    for attempt in {1..30}; do
        [[ -f "$EVIDENCE/catchup-monitor-ready" ]] && break
        kill -0 "$catchup_pid"
        sleep 1
    done
    [[ -f "$EVIDENCE/catchup-monitor-ready" ]]
    provider_event followup
    wait "$catchup_pid"
    shot catchup-finished
    fill 'Message verify-session' UNCERTAIN_ONE
    tap_desc 'Send prompt'
    wait_for_text UNCERTAIN_ONE
    fill 'Message verify-session' UNCERTAIN_TWO
    tap_desc 'Send prompt'
    wait_receipt 'VERIFY_RECEIVED:UNCERTAIN_ONE'
    queue_states uncertain submitted queued | tee "$EVIDENCE/queue-uncertain.json"
    queue_ui 1 UNCERTAIN_SUBMITTED
    fixture_counts WAITING_ONE,WAITING_TWO,FOLLOWUP,UNCERTAIN_ONE WAITING_ONE,WAITING_TWO,FOLLOWUP
    provider_event clear-hint
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/uncertain-hint-cleared.txt"
    grep -E '^❯[[:space:]]*$' "$EVIDENCE/uncertain-hint-cleared.txt"
    if grep -F 'edit queued messages' "$EVIDENCE/uncertain-hint-cleared.txt"; then
        exit 1
    else
        [[ $? -eq 1 ]]
    fi
    resync_marker UNCERTAIN_MARKER_1
    queue_states uncertain submitted queued | tee "$EVIDENCE/queue-uncertain-resync-1.json"
    queue_ui 1 UNCERTAIN_SUBMITTED
    fixture_counts WAITING_ONE,WAITING_TWO,FOLLOWUP,UNCERTAIN_ONE WAITING_ONE,WAITING_TWO,FOLLOWUP
    shot queued-uncertain-resync-1
    fill 'Message verify-session' PHONE_DRAFT_UNCERTAIN
    resync_marker UNCERTAIN_MARKER_2
    queue_states uncertain submitted queued | tee "$EVIDENCE/queue-uncertain-resync-2.json"
    queue_ui 1 UNCERTAIN_SUBMITTED
    dump_ui
    python3 - "$EVIDENCE/current.xml" "$package" <<'QUEUE_DRAFT'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert any(node.get('package') == sys.argv[2] and node.get('class') == 'android.widget.EditText' and node.get('text') == 'PHONE_DRAFT_UNCERTAIN' for node in root.iter('node'))
QUEUE_DRAFT
    fixture_counts WAITING_ONE,WAITING_TWO,FOLLOWUP,UNCERTAIN_ONE WAITING_ONE,WAITING_TWO,FOLLOWUP
    sudo -u verifyssh tmux capture-pane -pt verify-session > "$EVIDENCE/uncertain-draft-after.txt"
    grep -E '^❯[[:space:]]*$' "$EVIDENCE/uncertain-draft-after.txt"
    if grep -F 'edit queued messages' "$EVIDENCE/uncertain-draft-after.txt"; then
        exit 1
    else
        [[ $? -eq 1 ]]
    fi
    shot queued-uncertain-resync-2
    queue_states waiting delivered delivered | tee "$EVIDENCE/queue-waiting-final.json"
    queue_states followup delivered | tee "$EVIDENCE/queue-followup-final.json"
    queue_states uncertain submitted queued | tee "$EVIDENCE/queue-uncertain-final.json"
    queue_states all delivered delivered delivered submitted queued | tee "$EVIDENCE/queue-all-final.json"
    sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/queued-receipts.txt"
    sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-queued-work/a1b2c3d4-1008-4000-8000-000000001008.jsonl > "$EVIDENCE/queued-transcript.jsonl"
    sudo cat /home/verifyssh/shell-events.jsonl > "$EVIDENCE/shell-events.jsonl"
    python3 - "$EVIDENCE" <<'QUEUE_RESULT'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
consumed = ['WAITING_ONE', 'WAITING_TWO', 'FOLLOWUP']
inputs = root.joinpath('queued-receipts.txt').read_text().splitlines()
assert inputs == ['VERIFY_RECEIVED:' + text for text in consumed + ['UNCERTAIN_ONE']], inputs
rows = [json.loads(line) for line in root.joinpath('queued-transcript.jsonl').read_text().splitlines()]
receipts = [r['attachment']['prompt'] for r in rows if r.get('type') == 'attachment' and r.get('attachment', {}).get('type') == 'queued_command' and not r.get('isSidechain')]
assert receipts == consumed, receipts
waiting_names = ('queue-pending.json', 'queue-submitted.json', 'queue-after-resync.json', 'queue-first-delivered.json', 'queue-both-delivered.json', 'queue-waiting-final.json')
waiting = [json.loads(root.joinpath(name).read_text()) for name in waiting_names]
assert all([r['id'] for r in state] == [r['id'] for r in waiting[0]] for state in waiting)
assert [r['receipt_claims'] for r in waiting[-1]] == [r['receipt_claims'] for r in waiting[-2]]
uncertain_names = ('queue-uncertain.json', 'queue-uncertain-resync-1.json', 'queue-uncertain-resync-2.json', 'queue-uncertain-final.json')
uncertain = [json.loads(root.joinpath(name).read_text()) for name in uncertain_names]
assert all([r['id'] for r in state] == [r['id'] for r in uncertain[0]] for state in uncertain)
followup = [json.loads(root.joinpath(name).read_text()) for name in ('queue-followup-submitted.json', 'queue-followup-delivered.json', 'queue-followup-final.json')]
assert all(state[0]['id'] == followup[0][0]['id'] for state in followup)
assert followup[-1][0]['receipt_claims'] == followup[-2][0]['receipt_claims']
ids = [r['id'] for r in waiting[-1] + followup[-1] + uncertain[-1]]
assert len(ids) == len(set(ids)) == 5
all_final = json.loads(root.joinpath('queue-all-final.json').read_text())
assert [r['id'] for r in all_final] == ids
result = dict(consumed_positive=dict(inputs=consumed, synthetic_receipts=receipts, waiting=waiting, followup=followup),
              unresolved_tail=dict(inputs=['UNCERTAIN_ONE'], synthetic_receipts=[], states=uncertain),
              total_inputs=len(inputs), total_synthetic_receipts=len(receipts),
              harness_revision=__import__('os').environ['GITHUB_SHA'],
              app_source=root.joinpath('app-source-sha.txt').read_text().strip(),
              apk_sha256=root.joinpath('app-apk-sha256.txt').read_text().strip(),
              provider='simulated Claude; real SSH, tmux, client and embedded engine')
root.joinpath('queue-result.json').write_text(json.dumps(result, indent=2) + '\n')
print('QUEUE_JOURNEY_RESULT', json.dumps(result))
QUEUE_RESULT
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
    resync_event() {
        sudo -u verifyssh env GITHUB_ACTIONS=true python3 /home/verifyssh/resync-console.py event "$1"
    }
    resync_rows() {
        local phase=$1 identity direction attempt found x1 y1 x2 y2
        for identity in recovery-head pending-one pending-two accepted-fourth; do
            found=false
            for direction in older newer; do
                for attempt in {1..10}; do
                    shot "resync-$phase-$identity-$direction-$attempt"
                    if python3 "$scripts/resync-console.py" ui-row "$EVIDENCE/current.xml" "$identity" "$phase" > "$EVIDENCE/resync-$phase-$identity.json" 2> "$EVIDENCE/resync-row-error.txt"; then
                        found=true
                        break
                    fi
                    read -r x1 y1 x2 y2 < <(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" scroll-back "")
                    if [[ "$direction" == older ]]; then
                        adb shell input swipe "$x1" "$y1" "$x2" "$y2" 450
                    else
                        adb shell input swipe "$x2" "$y2" "$x1" "$y1" 450
                    fi
                    sleep 1
                done
                if [[ "$found" == true ]]; then break; fi
            done
            if [[ "$found" != true ]]; then
                cat "$EVIDENCE/resync-row-error.txt" >&2
                printf 'RESYNC_UI_ROW_NOT_PROVEN:%s:%s\n' "$phase" "$identity" >&2
                return 1
            fi
        done
    }
    unzip -p "$VERIFY_APK" assets/chat_history_reader.py | sha256sum > "$EVIDENCE/embedded-reader-sha256.txt"
    cp tools/vendor/chat-reader/SHA256SUMS "$EVIDENCE/canonical-corpus-sha256.txt"
    resync_event before > "$EVIDENCE/resync-server-before.json"
    resync_rows before
    fill 'Message verify-session' 'draft%sstays%shere'
    shot resync-draft-before
    python3 "$scripts/resync-console.py" draft "$EVIDENCE/current.xml" "$EVIDENCE/resync-draft-before.bin"
    tap_desc 'Session actions'
    wait_for_text 'Resync chat'
    shot resync-menu
    resync_event accept > "$EVIDENCE/resync-receipt-event.json"
    tap_text 'Resync chat'
    toast_shots=()
    for attempt in {1..8}; do
        adb exec-out screencap -p > "$EVIDENCE/toast-$attempt.png"
        toast_shots+=("$EVIDENCE/toast-$attempt.png")
    done
    sleep 5
    adb exec-out screencap -p > "$EVIDENCE/toast-settled.png"
    settled=false
    for attempt in {1..30}; do
        if resync_event after > "$EVIDENCE/resync-server-after.json" 2> "$EVIDENCE/resync-server-error.txt"; then
            settled=true
            break
        fi
        sleep 1
    done
    if [[ "$settled" != true ]]; then
        cat "$EVIDENCE/resync-server-after.json" "$EVIDENCE/resync-server-error.txt" >&2
        exit 1
    fi
    python3 "$scripts/toast.py" "$EVIDENCE/toast-settled.png" "${toast_shots[@]}" | tee "$EVIDENCE/toast-result.txt"
    resync_rows after
    shot resync-draft-after
    python3 "$scripts/resync-console.py" draft "$EVIDENCE/current.xml" "$EVIDENCE/resync-draft-after.bin"
    cmp "$EVIDENCE/resync-draft-before.bin" "$EVIDENCE/resync-draft-after.bin"
    tap_desc 'Session actions'
    wait_for_text 'Resync chat'
    tap_text 'Resync chat'
    sleep 5
    resync_rows repeat
    shot resync-draft-repeat
    python3 "$scripts/resync-console.py" draft "$EVIDENCE/current.xml" "$EVIDENCE/resync-draft-repeat.bin"
    cmp "$EVIDENCE/resync-draft-before.bin" "$EVIDENCE/resync-draft-repeat.bin"
    resync_event repeat > "$EVIDENCE/resync-server-repeat.json"
    printf 'ANDROID_RECOVERY_TWO_ACCEPTED_TWO_PENDING_NO_SENDS\n' | tee "$EVIDENCE/resync-result.txt"
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
    for attempt in {1..30}; do
        if sudo grep -F 'Reaction received.' /home/verifyssh/.claude/projects/-home-verifyssh-reaction-work/a1b2c3d4-0975-4000-8000-000000000975.jsonl >/dev/null; then break; fi
        sleep 1
    done
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
    sudo -u verifyssh python3 /home/verifyssh/reaction-console.py append-mixed
    wait_for_text 'My follow-up stays visible'
    wait_for_text 'Answer to the follow-up'
    shot reactions-mixed
    python3 "$scripts/reactions.py" mixed "$EVIDENCE/current.xml" "$EVIDENCE/reactions-mixed-result.json"
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
    adb shell settings put secure show_ime_with_hard_keyboard 1
    tap_desc 'Session actions'
    tap_text 'Open live terminal'
    wait_for_text 'SSH · tmux attached'
    sudo -u verifyssh tmux list-clients -F '#{client_name} #{client_session}' > "$EVIDENCE/tmux-clients.txt"
    grep -F verify-session "$EVIDENCE/tmux-clients.txt"
    shot terminal-attached
    sudo -u verifyssh tmux capture-pane -p -S - -t verify-session > "$EVIDENCE/shell-history-before.txt"
    grep -Fx SHELL_HISTORY_0001 "$EVIDENCE/shell-history-before.txt"
    grep -Fx SHELL_HISTORY_0500 "$EVIDENCE/shell-history-before.txt"
    for keyboard_state in open closed; do
        adb shell "screenrecord --time-limit 0 --bit-rate 1000000 /sdcard/shell-scroll-$keyboard_state.mp4 & echo \$! > /sdcard/shell-scroll-$keyboard_state.pid; wait" &
        recording_pid=$!
        tap_desc 'Show keyboard'
        if [[ "$keyboard_state" == closed ]]; then
            adb shell input keyevent KEYCODE_BACK
            adb shell dumpsys input_method > "$EVIDENCE/keyboard-closed.txt"
            grep -F 'mInputShown=false' "$EVIDENCE/keyboard-closed.txt"
        else
            adb shell dumpsys input_method > "$EVIDENCE/keyboard-before.txt"
            grep -F 'mInputShown=true' "$EVIDENCE/keyboard-before.txt"
        fi
        adb shell input swipe 200 800 200 400 600
        wait_for_text 'SHELL_HISTORY_'
        select_node desc 'Jump to live'
        if [[ "$keyboard_state" == open ]]; then
            adb shell dumpsys input_method > "$EVIDENCE/keyboard-history.txt"
            grep -F 'mInputShown=true' "$EVIDENCE/keyboard-history.txt"
        fi
        for attempt in {1..100}; do
            dump_ui
            if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains SHELL_HISTORY_0001 >/dev/null 2>&1; then break; fi
            read -r x1 y1 x2 y2 < <(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" scroll-back "")
            for swipe in 1 2 3; do
                adb shell input swipe "$x1" "$y1" "$x2" "$y2" 120
            done
        done
        wait_for_text SHELL_HISTORY_0001
        shot "shell-first-$keyboard_state"
        marker="SHELL_NEW_${keyboard_state^^}"
        sudo -u verifyssh tmux send-keys -l -t verify-session "$marker"
        sudo -u verifyssh tmux send-keys -t verify-session Enter
        wait_receipt "$marker"
        sudo -u verifyssh tmux capture-pane -p -S - -t verify-session > "$EVIDENCE/shell-history-after-$keyboard_state.txt"
        grep -Fx "$marker" "$EVIDENCE/shell-history-after-$keyboard_state.txt"
        shot "shell-anchored-$keyboard_state"
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains SHELL_HISTORY_0001
        python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent "$marker"
        tap_desc 'Jump to live'
        dump_ui
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" desc 'Jump to live' >/dev/null 2>&1; then exit 1; fi
        shot "shell-live-$keyboard_state"
        adb shell kill -2 "$(adb shell cat "/sdcard/shell-scroll-$keyboard_state.pid" | tr -d '\r')"
        wait "$recording_pid"
        adb pull "/sdcard/shell-scroll-$keyboard_state.mp4" "$EVIDENCE/shell-scroll-$keyboard_state.mp4"
    done
    printf 'SHELL_SCROLL_500_KEYBOARD_ANCHOR_LIVE_OK\n' | tee "$EVIDENCE/shell-scroll-result.txt"
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
printf '%s start %s\n' "$(date +%T.%N)" "${SSH_ORIGINAL_COMMAND:0:90}" >> /home/verifyssh/commands.log
case "$SSH_ORIGINAL_COMMAND" in *mp_buffer=*) sleep 15 ;; esac
bash -c "$SSH_ORIGINAL_COMMAND"
code=$?
printf '%s exit %s %s\n' "$(date +%T.%N)" "$code" "${SSH_ORIGINAL_COMMAND:0:60}" >> /home/verifyssh/commands.log
exit "$code"
DELAY
sudo chmod 755 /home/verifyssh/send-delay.sh
sudo sed -i 's|^|command="/home/verifyssh/send-delay.sh" |' /home/verifyssh/.ssh/authorized_keys
fill 'Message verify-session' VERIFY_SENT_528
shot send-draft
tap_desc 'Send prompt'
timeout 15s adb logcat -s SendTiming:I -e box_cleared_frame -m 1 > "$EVIDENCE/cleared-frame-receipt.txt"
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
