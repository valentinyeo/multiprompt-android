#!/usr/bin/env bash
pills_state() {
    local name=$1 text=$2 pill=${3:-/review-native}
    shot "pills-$name"
    python3 tools/skill-pills/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-pills-$name.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-pills-$name.png" \
        "$name" "$text" "$EVIDENCE/skill-pills-state.json" "$pill"
}

tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text VERIFY_READY_528
fill 'Message verify-session' 'Please%sthis'
pills_state draft 'Please this'
for attempt in {1..3}; do
    tap_node editor ''
    select_node focused-field '' >/dev/null || return
    adb shell input keyevent KEYCODE_MOVE_END
    adb shell input keycombination -t 100 KEYCODE_CTRL_LEFT KEYCODE_DPAD_LEFT
    adb shell input text /%s
    adb shell input keyevent KEYCODE_DPAD_LEFT
    dump_ui || return
    if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value composer 'Please / this' &&
        select_node contains '/pill-guide-01' >/dev/null 2>&1; then
        break
    fi
    printf 'Pill picker missing after typing "/ " (attempt %s); restoring the draft\n' "$attempt" | tee -a "$EVIDENCE/pill-picker-retries.txt"
    fill 'Message verify-session' 'Please%sthis' || return
done
wait_for_text '/pill-guide-01'
pills_state picker 'Please / this'
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent '/review-native'
for attempt in {1..4}; do
    read -r x1 y1 x2 y2 < <(python3 tools/skill-pills/ui.py scroll "$EVIDENCE/current.xml")
    adb shell input swipe "$x1" "$y1" "$x2" "$y2" 500
    dump_ui
    if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text '/review-native' >/dev/null 2>&1; then break; fi
done
pills_state scrolled 'Please / this'
tap_text '/review-native'
dump_ui
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value composer 'Please /review-native this'
pills_state inserted 'Please /review-native this'
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:Please /review-native this'
wait_for_text 'Received Please /review-native this.'
pills_state sent 'Please /review-native this'
fill 'Message verify-session' 'Keep%s/rev%sthis'
tap_node editor ''
select_node focused-field '' >/dev/null
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keycombination -t 100 KEYCODE_CTRL_LEFT KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_LEFT
wait_for_text '/review-native'
tap_text '/review-native'
pills_state removable 'Keep /review-native this'
for position in {1..15}; do adb shell input keyevent KEYCODE_DEL; done
pills_state removed 'Keep this'
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:Keep this'
wait_for_text 'Received Keep this.'
pills_state sent-removed 'Keep this'
fill 'Message verify-session' 'Please%s/home/verifyssh/notes%sthis'
pills_state path 'Please /home/verifyssh/notes this'
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent 'No matching session skills'
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent '/pill-guide-01'
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:Please /home/verifyssh/notes this'
wait_for_text 'Received Please /home/verifyssh/notes this.'
pills_state sent-path 'Please /home/verifyssh/notes this'
fill 'Message verify-session' 'Keep%s/cl%sthis'
tap_node editor ''
select_node focused-field '' >/dev/null
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keycombination -t 100 KEYCODE_CTRL_LEFT KEYCODE_DPAD_LEFT
wait_for_text '/clear'
tap_text '/clear'
dump_ui
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value composer 'Keep /clear this'
pills_state command 'Keep /clear this'
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:Keep /clear this'
wait_for_text 'Received Keep /clear this.'
pills_state sent-command 'Keep /clear this' '/clear'
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/skill-pills-receipts.txt"
sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-native-skills-work/a1b2c3d4-1009-4000-8000-000000001009.jsonl > "$EVIDENCE/skill-pills-transcript.jsonl"
python3 - "$EVIDENCE" <<'PYPILLS'
import json
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
expected = ['Please /review-native this', 'Keep this', 'Please /home/verifyssh/notes this', 'Keep /clear this']
receipts = root.joinpath('skill-pills-receipts.txt').read_text().splitlines()
markers = [line for line in receipts if re.fullmatch(r'VERIFY_RECEIVED:\[mp:[0-9a-f]{16}\]', line)]
assert len(markers) == 4 and len(set(markers)) == 4, markers
assert [line for line in receipts if line not in markers] == ['VERIFY_RECEIVED:' + text for text in expected]
turns = [json.loads(line) for line in root.joinpath('skill-pills-transcript.jsonl').read_text().splitlines()]
assert [turn['message']['content'] for turn in turns if turn['type'] == 'user'] == ['VERIFY_READY_528', *expected]
assert 'BODY_MUST_NOT_BE_INJECTED' not in root.joinpath('skill-pills-transcript.jsonl').read_text()
print('SKILL_PILLS_INSERT_SEND_REMOVE_OK')
PYPILLS
