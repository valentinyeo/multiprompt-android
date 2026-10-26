#!/usr/bin/env bash
set -euo pipefail
: "${EVIDENCE:?Set evidence directory}"
: "${MULTIPROMPT_DIR:?Set the multiprompt checkout}"
: "${FIXTURE:?Set the ssh fixture directory}"
package=dev.multiprompt.companion.debug
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
state="$work/service-state.json"
mkdir -p "$EVIDENCE"
exec > >(tee -a "$EVIDENCE/driver.log") 2>&1
now() { date +%s.%N; }
dump_ui() {
    timeout 30s adb shell uiautomator dump /sdcard/journey-ui.xml >/dev/null || return 1
    timeout 30s adb pull /sdcard/journey-ui.xml "$EVIDENCE/current.xml" >/dev/null
}
shot() { dump_ui || true; cp "$EVIDENCE/current.xml" "$EVIDENCE/$1.xml" 2>/dev/null || true; adb exec-out screencap -p > "$EVIDENCE/$1.png"; }
has_text() { dump_ui && python3 "$here/ui.py" "$EVIDENCE/current.xml" "${3:-has}" "$1"; }
wait_text() {
    local deadline=$(( $(date +%s) + ${2:-60} ))
    until has_text "$1" 0 "${3:-has}"; do
        if (( $(date +%s) > deadline )); then printf 'Timed out waiting for %s\n' "$1"; shot "failure-${1// /-}"; return 1; fi
        sleep 0.3
    done
}
tap_text() {
    dump_ui
    local point x y
    point=$(python3 "$here/ui.py" "$EVIDENCE/current.xml" tap "$1")
    read -r x y <<< "$point"
    adb shell input tap "$x" "$y"
}
layout() {
    local key=$1; shift
    sudo -u valentin env "MULTIPROMPT_KEY=$key" "MULTIPROMPT_URL=http://127.0.0.1:8787" "$(command -v node)" /home/valentin/mp-layout.mjs "$@"
}
json() { node -e 'const s=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));process.stdout.write(String(s[process.argv[2]]))' "$state" "$1"; }
cleanup() {
    code=$?
    trap - EXIT
    set +e
    [[ -n "${server_pid:-}" ]] && kill "$server_pid"
    timeout 15s adb logcat -d > "$EVIDENCE/logcat.txt"
    rm -rf "$work"
    printf 'DRIVER EXIT: %s\n' "$code"
    exit "$code"
}
trap cleanup EXIT

node "$MULTIPROMPT_DIR/service/journey/server.mjs" --port=8787 "--state=$state" > "$work/service.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 60); do [[ -s "$state" ]] && break; sleep 1; done
test -s "$state"
admin=$(json admin_key)
member=$(json workspace_key)
other=$(json other_key)

adb shell settings put global hide_error_dialogs 1
adb shell am instrument -w -e class dev.multiprompt.companion.account.SeedAccountDeviceTest \
    -e refresh_token "$(json refresh_token)" -e account_id "$(json account)" -e device_id journey-device \
    -e ssh_private_key "$(base64 -w0 "$FIXTURE/user-key")" -e ssh_host_key "$(awk '{print $2}' "$FIXTURE/host-key.pub")" \
    -e ssh_host 10.0.2.2 -e ssh_port 2222 \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$EVIDENCE/seed.txt"
grep -q 'OK (1 test)' "$EVIDENCE/seed.txt"
adb shell am start -W -n "$package/dev.multiprompt.companion.MainActivity"

wait_text Work 120
tap_text Work
wait_text work-main 60 hasi
wait_text runner-3 30 hasi
shot android-before

layout "$admin" list | tee "$EVIDENCE/cli-list.txt"
start=$(now)
layout "$admin" move --to Runners --create --match 'runner-*' | tee "$EVIDENCE/cli-move.txt"
wait_text Runners 30
section_seconds=$(python3 -c "print(round($(now) - $start, 2))")
tap_text Runners
wait_text runner-1 10 hasi
wait_text runner-2 10 hasi
wait_text runner-3 10 hasi
shot android-after

layout "$member" list | tee "$EVIDENCE/cli-workspace-list.txt"
if grep -q runner- "$EVIDENCE/cli-workspace-list.txt"; then exit 1; fi
grep -q notes-a "$EVIDENCE/cli-workspace-list.txt"
layout "$member" open notes-new --no-tmux | tee "$EVIDENCE/cli-workspace-open.txt"
if layout "$member" move --to Work notes-a > "$EVIDENCE/cli-workspace-refused.txt" 2>&1; then exit 1; fi
grep -Eq 'refused|not found' "$EVIDENCE/cli-workspace-refused.txt"
if layout "$other" list > "$EVIDENCE/cli-other-workspace.txt" 2>&1; then exit 1; fi

start=$(now)
layout "$admin" open fresh-session --workspace Runners --title 'Fresh session' | tee "$EVIDENCE/cli-open.txt"
wait_text 'fresh' 30 hasi
open_seconds=$(python3 -c "print(round($(now) - $start, 2))")
shot android-opened

printf '{"section_seconds": %s, "open_seconds": %s}\n' "$section_seconds" "$open_seconds" | tee "$EVIDENCE/result.json"
python3 -c "import sys; sys.exit(0 if $section_seconds <= 10 and $open_seconds <= 10 else 1)"
