#!/usr/bin/env bash
set -euo pipefail

: "${EVIDENCE:?}"
: "${FIXTURE:?}"
: "${RUNNER_TEMP:?}"
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "$(adb get-serialno)" =~ ^emulator-[0-9]+$ ]]

mkdir -p "$EVIDENCE" "$FIXTURE"

cleanup() {
    local status=$? pid command_line
    trap - EXIT
    set +e

    adb shell settings put global http_proxy :0
    if [[ $? -ne 0 && $status -eq 0 ]]; then status=1; fi

    if [[ -f "$FIXTURE/offline-proxy.pid" ]]; then
        pid=$(cat "$FIXTURE/offline-proxy.pid")
        if [[ "$pid" =~ ^[1-9][0-9]*$ ]] && kill -0 "$pid" 2>/dev/null; then
            command_line=$(tr '\0' ' ' < "/proc/$pid/cmdline")
            if [[ "$command_line" == *"$PWD/tools/app-name/proxy.py"* ]]; then
                kill "$pid"
                for _ in {1..20}; do
                    kill -0 "$pid" 2>/dev/null || break
                    sleep 0.1
                done
                if kill -0 "$pid" 2>/dev/null; then
                    kill -KILL "$pid"
                    [[ $status -ne 0 ]] || status=1
                fi
            else
                printf 'Proxy PID does not identify the expected process\n' >&2
                [[ $status -ne 0 ]] || status=1
            fi
        fi
    fi

    if [[ -f "$FIXTURE/offline-proxy.log" ]]; then
        cp "$FIXTURE/offline-proxy.log" "$EVIDENCE/offline-proxy.log"
        if [[ $? -ne 0 && $status -eq 0 ]]; then status=1; fi
    fi
    exit "$status"
}
trap cleanup EXIT

mapfile -t plan < <(
    python3 - "$EVIDENCE/name-update-plan.json" <<'PY'
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    plan = json.load(stream)
assert plan["mode"] in ("control", "release")
assert plan["package"] == "dev.multiprompt.companion.m3"
baseline = plan["baseline"]["apk"]
assert isinstance(baseline, str) and os.path.isabs(baseline)
assert os.path.isfile(baseline)
assert "\n" not in baseline and "\r" not in baseline
print(plan["mode"])
print(baseline)
PY
)
[[ ${#plan[@]} -eq 2 ]]
mode=${plan[0]}
baseline=${plan[1]}

grep -Fq 'RELEASE_CHOICES_PROXY_SCRIPT' tools/release-choices/seed.sh
test -f tools/app-name/proxy.py
test -f "$RUNNER_TEMP/release-choices/seed.apk"

adb install -r -g "$baseline"
adb shell appops set dev.multiprompt.companion.m3 REQUEST_INSTALL_PACKAGES allow
adb shell pm grant dev.multiprompt.companion.m3 android.permission.POST_NOTIFICATIONS
adb shell dumpsys package dev.multiprompt.companion.m3 > "$EVIDENCE/baseline-permissions.txt"
adb shell appops get dev.multiprompt.companion.m3 REQUEST_INSTALL_PACKAGES \
    > "$EVIDENCE/baseline-install-appop.txt"

export VERIFY_PACKAGE=dev.multiprompt.companion.m3
export VERIFY_FEATURE=settings-navigation.md
export RELEASE_CHOICES_PROXY_SCRIPT="$PWD/tools/app-name/proxy.py"
bash tools/release-choices/seed.sh

python3 - <<'PY'
import socket

with socket.create_connection(("127.0.0.1", 8788), timeout=5) as connection:
    connection.sendall(
        b"CONNECT account.multiprompt.dev:443 HTTP/1.1\r\n"
        b"Host: account.multiprompt.dev:443\r\n\r\n"
    )
    assert b" 501 " in connection.recv(1024).split(b"\r\n", 1)[0]
PY

python3 -B tools/app-name/drive.py 2>&1 | tee "$EVIDENCE/driver.log"

if [[ "$mode" == control ]]; then
    grep -Fx PUBLISHED_UPDATE_CONTROL_OK "$EVIDENCE/driver.log"
else
    grep -Fx PUBLISHED_NAME_UPDATE_OK "$EVIDENCE/driver.log"
fi

python3 - "$EVIDENCE/name-update-result.json" "$mode" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    result = json.load(stream)
assert result["mode"] == sys.argv[2]
assert result["status"] == "passed"
assert result["appDownloadClicked"] is True
assert result["installedThroughApp"] is True
assert result["uidUnchanged"] is True
assert result["recordingComplete"] is True
assert result["recording"] == "name-update.mp4"
PY

test -s "$EVIDENCE/name-update.mp4"
ffprobe -v error -show_entries format=duration -of json "$EVIDENCE/name-update.mp4" \
    > "$EVIDENCE/recording-probe.json"
python3 - "$EVIDENCE/recording-probe.json" <<'PY'
import json
import math
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    duration = float(json.load(stream)["format"]["duration"])
assert math.isfinite(duration) and duration > 0
PY
