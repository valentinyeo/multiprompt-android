#!/usr/bin/env bash
pills_state() {
    local name=$1 text=$2
    shot "pills-$name"
    python3 tools/skill-pills/ui.py \
        "$EVIDENCE/$(printf '%02d' "$step")-pills-$name.xml" \
        "$EVIDENCE/$(printf '%02d' "$step")-pills-$name.png" \
        "$name" "$text" "$EVIDENCE/skill-pills-state.json"
}

tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text VERIFY_READY_528
fill 'Message verify-session' 'Please%sthis'
pills_state draft 'Please this'
tap_node editor ''
adb shell input keyevent KEYCODE_MOVE_HOME
for position in {1..7}; do adb shell input keyevent KEYCODE_DPAD_RIGHT; done
adb shell input text /%s
adb shell input keyevent KEYCODE_DPAD_LEFT
wait_for_text '/pill-guide-01'
pills_state picker 'Please / this'
plan=$(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" reach-text '/review-native')
[[ "$plan" == swipe\ * ]]
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
adb shell input keyevent KEYCODE_MOVE_HOME
for position in {1..9}; do adb shell input keyevent KEYCODE_DPAD_RIGHT; done
wait_for_text '/review-native'
tap_text '/review-native'
pills_state removable 'Keep /review-native this'
for position in {1..15}; do adb shell input keyevent KEYCODE_DEL; done
pills_state removed 'Keep this'
tap_desc 'Send prompt'
wait_receipt 'VERIFY_RECEIVED:Keep this'
wait_for_text 'Received Keep this.'
pills_state sent-removed 'Keep this'
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/skill-pills-receipts.txt"
sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-native-skills-work/a1b2c3d4-1009-4000-8000-000000001009.jsonl > "$EVIDENCE/skill-pills-transcript.jsonl"
python3 - "$EVIDENCE" <<'PYPILLS'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
expected = ['Please /review-native this', 'Keep this']
assert root.joinpath('skill-pills-receipts.txt').read_text().splitlines() == ['VERIFY_RECEIVED:' + text for text in expected]
turns = [json.loads(line) for line in root.joinpath('skill-pills-transcript.jsonl').read_text().splitlines()]
assert [turn['message']['content'] for turn in turns if turn['type'] == 'user'] == ['VERIFY_READY_528', *expected]
assert 'BODY_MUST_NOT_BE_INJECTED' not in root.joinpath('skill-pills-transcript.jsonl').read_text()
print('SKILL_PILLS_INSERT_SEND_REMOVE_OK')
PYPILLS
