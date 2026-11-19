#!/usr/bin/env bash
set -euo pipefail

package=${VERIFY_PACKAGE:-dev.multiprompt.companion.debug}
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
    local action=$1 needle=$2 label=$3
    printf 'PLANT %s\n' "$label" | tee -a "$shared_report"
    timeout 90s adb shell am broadcast -n "$package/dev.multiprompt.companion.DebugExitReceiver" -a "$action" \
        > "$EVIDENCE/broadcast-$(printf '%s' "$label" | tr ' ' -).txt" 2>&1 || true
    wait_dead || true
    if ! wait_report "$needle"; then
        printf 'PRODUCT_ASSERTION_FAILED %s: no crash report with summary %s reached the account service\n' "$label" "$needle" >&2
        sudo cat "$receipts" >&2 2>/dev/null || true
        return 1
    fi
    received "$needle" | tee -a "$shared_report"
}

plant dev.multiprompt.companion.debug.THROW 'IGSH-2840 crash' 'uncaught exception'
plant dev.multiprompt.companion.debug.NATIVE 'Native crash' 'native crash'
launch
adb shell input keyevent KEYCODE_HOME
adb shell am kill "$package" || true
wait_dead
if ! wait_report 'Signaled exit'; then
    printf 'PRODUCT_ASSERTION_FAILED signaled process death: no crash report with summary Signaled exit reached the account service\n' >&2
    sudo cat "$receipts" >&2 2>/dev/null || true
    exit 1
fi
received 'Signaled exit' | tee -a "$shared_report"
plant dev.multiprompt.companion.debug.ANR 'ANR' 'application not responding'

sudo cat "$receipts" > "$EVIDENCE/crash-reports-received.txt"
python3 - "$EVIDENCE" <<'PYTHON'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
reports = [json.loads(line) for line in (root / 'crash-reports-received.txt').read_text().splitlines() if line.strip()]
fields = {'id', 'captured_at', 'app_version', 'android_version', 'thread', 'summary', 'stack_trace'}
summaries = {}
for needle, label in (('IGSH-2840 crash', 'uncaught exception'), ('Native crash', 'native crash'),
                      ('Signaled exit', 'signaled process death'), ('ANR', 'application not responding')):
    matches = [report for report in reports if needle in report['summary']]
    assert matches, (label, [report['summary'] for report in reports])
    summaries[label] = matches[-1]['summary']
for report in reports:
    assert set(report) == fields, sorted(report)
    assert report['id'] and report['thread'] in {'main', 'background'}, report
    assert isinstance(report['captured_at'], int) and report['captured_at'] > 0, report
    assert report['stack_trace'].strip(), report
    assert report['app_version'].strip() and report['android_version'].strip(), report
(root / 'crash-exit-summaries.json').write_text(json.dumps(summaries, indent=2))
print('EVERY_PLANTED_EXIT_ARRIVED_AT_THE_ACCOUNT_SERVICE')
for label, summary in summaries.items():
    print(label, '->', summary)
PYTHON

shot crash-reports-received
