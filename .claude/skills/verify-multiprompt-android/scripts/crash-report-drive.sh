#!/usr/bin/env bash
set -euo pipefail

package=${VERIFY_PACKAGE:-dev.multiprompt.companion.debug}
activity="$package/dev.multiprompt.companion.DebugCrashActivity"
receipts=/home/verifyssh/crash-reports.jsonl
shared_report="$EVIDENCE/crash-exits.txt"
: > "$shared_report"

tap_desc 'Session list actions'
tap_text Settings
tap_text 'Sign in'
wait_for_text 'Continue as fixture account'
shot crash-sign-in
tap_text 'Continue as fixture account'
wait_for_text 'Fixture laptop wants to open your servers.'
shot crash-signed-in

launch() {
    adb shell am force-stop "$package"
    adb shell am start -n "$package/dev.multiprompt.companion.MainActivity" > /dev/null
    sleep 4
}

wait_dead() {
    local attempt
    for attempt in $(seq 1 30); do
        if [[ -z "$(adb shell pidof "$package" 2>/dev/null || true)" ]]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

received() {
    sudo grep -F "\"summary\": \"$1" "$receipts" 2>/dev/null | tail -1
}

wait_report() {
    local needle=$1 attempt
    for attempt in $(seq 1 8); do
        if [[ -n "$(received "$needle")" ]]; then
            return 0
        fi
        launch
        sleep 4
    done
    [[ -n "$(received "$needle")" ]]
}

plant() {
    local exit=$1 label=$2 settle=$3
    adb shell am force-stop "$package"
    printf 'PLANT %s: am start -n %s --es exit %s\n' "$label" "$activity" "$exit" | tee -a "$shared_report"
    timeout 90s adb shell am start -n "$activity" --es exit "$exit" > /dev/null 2>&1 || true
    sleep "$settle"
}

block_main_thread() {
    local attempt
    for attempt in $(seq 1 8); do
        timeout 15s adb shell input keyevent KEYCODE_DPAD_DOWN || true
        timeout 15s adb shell input keyevent KEYCODE_A || true
        sleep 1
    done
}

plant_and_wait() {
    local exit=$1 label=$2 needle=$3
    if [[ "$exit" == anr ]]; then
        plant "$exit" "$label" 9
        block_main_thread
    else
        plant "$exit" "$label" 4
    fi
    if ! wait_dead; then
        printf 'PLANT_ALIVE %s: the app process was still alive after the plant\n' "$label" | tee -a "$shared_report"
    fi
    if ! wait_report "$needle"; then
        printf 'MISSING %s: no crash report with summary starting %s reached the account service\n' "$label" "$needle" | tee -a "$shared_report"
        missing="$missing $exit"
        return 0
    fi
    printf 'REPORTED %s: %s\n' "$label" "$(received "$needle" | python3 -c 'import json,sys
report = json.loads(sys.stdin.read() or "{}")
reason = next((line for line in report.get("stack_trace", "").splitlines() if line.startswith("reason=")), "")
print(report.get("summary", "") + (" | " + reason if reason else ""))')" \
        | tee -a "$shared_report"
}

missing=""
plant_and_wait uncaught 'uncaught exception' 'java.lang.IllegalStateException: IGSH-2840 crash'
plant_and_wait native 'native crash' 'Native crash'
plant_and_wait kill 'system kill' 'Signaled exit'
plant_and_wait anr 'application not responding' 'ANR'

sudo cat "$receipts" > "$EVIDENCE/crash-reports-received.txt" 2>/dev/null || : > "$EVIDENCE/crash-reports-received.txt"

python3 - "$EVIDENCE" "$missing" <<'PYTHON'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
missing = sys.argv[2].split()
reports = [json.loads(line) for line in (root / 'crash-reports-received.txt').read_text().splitlines() if line.strip()]
fields = {'id', 'captured_at', 'app_version', 'android_version', 'thread', 'summary', 'stack_trace'}
needles = {'uncaught': 'java.lang.IllegalStateException: IGSH-2840 crash', 'native': 'Native crash',
           'kill': 'Signaled exit', 'anr': 'ANR'}
labels = {'uncaught': 'uncaught exception', 'native': 'native crash', 'kill': 'system kill',
          'anr': 'application not responding'}
def reason_line(report):
    for line in report['stack_trace'].splitlines():
        if line.startswith('reason='):
            return line.strip()
    return None

summaries = {}
for exit, needle in needles.items():
    matches = [report for report in reports if report['summary'].startswith(needle)]
    if matches:
        summaries[labels[exit]] = {'summary': matches[-1]['summary'], 'reason': reason_line(matches[-1])}
for report in reports:
    assert set(report) == fields, sorted(report)
    assert report['id'] and report['thread'] in {'main', 'background'}, report
    assert isinstance(report['captured_at'], int) and report['captured_at'] > 0, report
    assert report['stack_trace'].strip(), report
    assert report['app_version'].strip() and report['android_version'].strip(), report
(root / 'crash-exit-summaries.json').write_text(json.dumps(summaries, indent=2))
print('REPORTS RECEIVED:', [report['summary'] for report in reports])
for label, found in summaries.items():
    print(label, '->', found['summary'], '|', found['reason'])
if missing:
    print('MISSING_EXITS', ' '.join(labels[exit] for exit in missing))
    sys.exit(1)
print('EVERY_PLANTED_EXIT_ARRIVED_AT_THE_ACCOUNT_SERVICE')
PYTHON

shot crash-reports-received
