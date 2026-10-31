[[ "${GITHUB_ACTIONS:-}" == true && "$(adb get-serialno)" == emulator-* ]]
[[ "$package" == dev.multiprompt.companion || "$package" == dev.multiprompt.companion.m3 ]]
[[ "$(adb shell settings get global http_proxy | tr -d '\r')" == 10.0.2.2:8788 ]]
kill -0 "$(cat "$FIXTURE/offline-proxy.pid")"
wait_for_text verify-keep
for attempt in {1..30}; do
    if sudo grep 'Accepted publickey for verifyssh' "$FIXTURE/sshd.log" > "$EVIDENCE/pins-ssh-auth.txt"; then break; fi
    sleep 1
done
test -s "$EVIDENCE/pins-ssh-auth.txt"

sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh bash -c '
    set -euo pipefail
    [[ "$GITHUB_ACTIONS" == true && "$HOME" == /home/verifyssh && "$(id -u)" -ne 0 && "$(id -un)" == verifyssh ]]
    tmux has-session -t =verify-session
    tmux has-session -t =verify-keep
    for i in {0..39}; do
        if (( i < 12 )); then printf -v name "pin-%02d" "$i"
        else printf -v name "row-%02d" "$((i - 12))"; fi
        receipt="/home/verifyssh/pins-$name-receipts.txt"
        tmux new-session -d -s "$name" -x 100 -y 32 \
            "env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/console.py $receipt"
    done
    tmux list-sessions -F "#{session_name}" | grep -E "^(pin|row)-[0-9][0-9]$" | sort > /home/verifyssh/pins-names.txt
    [[ "$(wc -l < /home/verifyssh/pins-names.txt)" == 40 ]]
    for i in {0..39}; do
        if (( i < 12 )); then printf -v name "pin-%02d" "$i"
        else printf -v name "row-%02d" "$((i - 12))"; fi
        tmux has-session -t "=$name"
        grep -Fx "$name" /home/verifyssh/pins-names.txt
    done
'
sudo cat /home/verifyssh/pins-names.txt > "$EVIDENCE/pins-tmux-names.txt"
test "$(wc -l < "$EVIDENCE/pins-tmux-names.txt")" -eq 40

pins_instrument() {
    local mode=$1 reverse=${2:-} output="$EVIDENCE/$1${2:+-$2}.txt"
    adb shell am force-stop "$package"
    if [[ -n "$reverse" ]]; then
        adb shell am instrument -w -e mode "$mode" -e reverse "$reverse" \
            dev.multiprompt.verification/dev.multiprompt.verification.Seed | tr -d '\r' > "$output"
        grep -Fx "INSTRUMENTATION_RESULT: reverse=$reverse" "$output"
        grep -Fx 'INSTRUMENTATION_RESULT: result=ISOLATED_PINS_ORDER_SET' "$output"
    else
        adb shell am instrument -w -e mode "$mode" \
            dev.multiprompt.verification/dev.multiprompt.verification.Seed | tr -d '\r' > "$output"
        grep -Fx 'INSTRUMENTATION_RESULT: result=ISOLATED_PINS_SEEDED' "$output"
    fi
    grep -Fx 'INSTRUMENTATION_CODE: -1' "$output"
}
pins_capture() {
    local label=$1
    shot "$label"
    cp "$EVIDENCE/current.xml" "$EVIDENCE/$label.xml"
    cp "$EVIDENCE/$(printf '%02d' "$step")-$label.png" "$EVIDENCE/$label.png"
    python3 "$scripts/pins-oracle.py" guard "$EVIDENCE/$label.xml" "$package"
}
pins_top() {
    local attempt x y bottom
    for attempt in {1..15}; do
        dump_ui
        if python3 "$scripts/pins-oracle.py" top "$EVIDENCE/current.xml" "$package" >/dev/null 2>&1; then return; fi
        read -r x y bottom < <(python3 "$scripts/pins-oracle.py" swipe-area "$EVIDENCE/current.xml" "$package")
        adb shell input swipe "$x" "$y" "$x" "$bottom" 500
        sleep 1
    done
    python3 "$scripts/pins-oracle.py" top "$EVIDENCE/current.xml" "$package"
}
pins_first_horizontal() {
    local attempt x1 y1 x2 y2
    for attempt in {1..15}; do
        dump_ui
        if python3 "$scripts/pins-oracle.py" pin-bounds "$EVIDENCE/current.xml" "$package" pin-00 >/dev/null 2>&1; then return; fi
        read -r x1 y1 x2 y2 < <(python3 "$scripts/pins-oracle.py" pin-bounds "$EVIDENCE/current.xml" "$package" any)
        adb shell input swipe "$x1" "$y1" "$x2" "$y2" 450
        sleep 1
    done
    return 1
}

pins_instrument pins-seed
for reverse in false true; do
    pins_instrument pins-order "$reverse"
    relaunch_app
    pins_top
    pins_first_horizontal
    pins_capture "pins-$reverse-top"
    python3 "$scripts/pins-oracle.py" top "$EVIDENCE/pins-$reverse-top.xml" "$package"
    python3 "$scripts/pins-oracle.py" header "$EVIDENCE/pins-$reverse-top.xml" "$package" > "$EVIDENCE/pins-$reverse-header.json"
    for attempt in {1..15}; do
        dump_ui
        if python3 "$scripts/pins-oracle.py" pin-bounds "$EVIDENCE/current.xml" "$package" pin-11 >/dev/null 2>&1; then break; fi
        read -r x1 y1 x2 y2 < <(python3 "$scripts/pins-oracle.py" pin-bounds "$EVIDENCE/current.xml" "$package" any)
        adb shell input swipe "$x2" "$y2" "$x1" "$y1" 450
        sleep 1
    done
    pins_capture "pins-$reverse-horizontal"
    python3 "$scripts/pins-oracle.py" pin-bounds "$EVIDENCE/current.xml" "$package" pin-11
    tap_text pin-11
    wait_for_text 'Message pin-11'
    pins_capture "pins-$reverse-opened"
    python3 "$scripts/pins-oracle.py" reader "$EVIDENCE/current.xml" "$package"
    adb shell input keyevent KEYCODE_BACK
    wait_for_text Inbox
    pins_top
    pins_first_horizontal
    dump_ui
    read -r x y bottom < <(python3 "$scripts/pins-oracle.py" swipe-area "$EVIDENCE/current.xml" "$package")
    for swipe in 1 2; do adb shell input swipe "$x" "$bottom" "$x" "$y" 500; sleep 1; done
    pins_capture "pins-$reverse-scrolled"
    if ! python3 "$scripts/pins-oracle.py" scrolled "$EVIDENCE/current.xml" "$package" "$EVIDENCE/pins-$reverse-header.json" \
        > "$EVIDENCE/pins-$reverse-scrolled-oracle.txt" 2>&1; then
        if grep -Fxq PINNED_STRIP_MUST_SCROLL "$EVIDENCE/pins-$reverse-scrolled-oracle.txt"; then
            printf '%s\n' PINNED_STRIP_MUST_SCROLL > "$EVIDENCE/pins-failure.txt"
        else
            cat "$EVIDENCE/pins-$reverse-scrolled-oracle.txt" >&2
        fi
        exit 1
    fi
    pins_top
    pins_capture "pins-$reverse-restored"
    python3 "$scripts/pins-oracle.py" top "$EVIDENCE/current.xml" "$package"
done
printf '%s\n' SIGNED_PINNED_SESSIONS_SCROLL_OK | tee "$EVIDENCE/pins-result.txt"
