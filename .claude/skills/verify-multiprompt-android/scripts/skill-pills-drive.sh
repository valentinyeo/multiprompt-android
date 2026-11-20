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
select_node focused-field '' >/dev/null
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keycombination -t 100 KEYCODE_CTRL_LEFT KEYCODE_DPAD_LEFT
adb shell input text /%s
adb shell input keyevent KEYCODE_DPAD_LEFT
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
long_draft='Checking%swhen%sit%sresets.%sWe%sneed%sinstructions%sfor%sthis,%sthis%snever%sused%sto%shappen.%sWe%shad%sa%svery%sgood%ssetup%sfor%sthis%sin%splace,%sbut%ssince%susing%sthe%snew%sstack%sthis%shas%sbroken%sdown.%s/review-native'
fill 'Message verify-session' "$long_draft"
tap_node editor ''
sleep 2
dump_ui
python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select draft --value "${long_draft//%s/ }" >/dev/null
python3 - "$EVIDENCE/current.xml" <<'PYCOMPOSER'
import re
import sys
import xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))

def rect(node):
    numbers = list(map(int, re.findall(r'-?\d+', node.get('bounds') or '')))
    assert len(numbers) == 4, node.get('bounds')
    return numbers

def by_desc(value):
    found = [node for node in nodes if (node.get('content-desc') or '').strip() == value]
    assert found, f'missing {value!r}'
    return rect(found[0])

root = rect(nodes[0])
field = [node for node in nodes if node.get('class') == 'android.widget.EditText']
assert len(field) == 1, f'expected one composer field, found {len(field)}'
field = rect(field[0])
attach = by_desc('Attach')
mic = by_desc('Start dictation')
send = by_desc('Send prompt')
tolerance = 24
print(f'COMPOSER root={root} field={field} attach={attach} mic={mic} send={send}')
assert field[1] >= root[1] and field[3] <= root[3], 'the text field leaves the window'
assert attach[2] <= field[0] and attach[1] >= field[1] and attach[3] <= field[3], f'the + button leaves the composer row: {attach} vs {field}'
assert abs(attach[3] - field[3]) <= tolerance, f'the + button is not bottom aligned: {attach} vs {field}'
assert abs(mic[3] - field[3]) <= tolerance, f'dictation is not bottom aligned: {mic} vs {field}'
assert abs(send[3] - field[3]) <= tolerance, f'send is not bottom aligned: {send} vs {field}'
assert field[3] >= root[3] - 40, f'the composer row is not at the bottom of the window: {field} vs {root}'
assert field[3] - field[1] >= 110, f'the long draft did not grow the field: {field}'
print('COMPOSER_LONG_DRAFT_BOUNDS_OK')
PYCOMPOSER
shot composer-long-draft
python3 "$scripts/mpa" read --xml "$EVIDENCE/current.xml" --select absent --value 'Message verify-session'
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/skill-pills-receipts.txt"
sudo cat /home/verifyssh/.claude/projects/-home-verifyssh-native-skills-work/a1b2c3d4-1009-4000-8000-000000001009.jsonl > "$EVIDENCE/skill-pills-transcript.jsonl"
python3 - "$EVIDENCE" <<'PYPILLS'
import json
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
expected = ['Please /review-native this', 'Keep this']
receipts = root.joinpath('skill-pills-receipts.txt').read_text().splitlines()
markers = [line for line in receipts if re.fullmatch(r'VERIFY_RECEIVED:\[mp:[0-9a-f]{16}\]', line)]
assert len(markers) == 2 and len(set(markers)) == 2, markers
assert [line for line in receipts if line not in markers] == ['VERIFY_RECEIVED:' + text for text in expected]
turns = [json.loads(line) for line in root.joinpath('skill-pills-transcript.jsonl').read_text().splitlines()]
assert [turn['message']['content'] for turn in turns if turn['type'] == 'user'] == ['VERIFY_READY_528', *expected]
assert 'BODY_MUST_NOT_BE_INJECTED' not in root.joinpath('skill-pills-transcript.jsonl').read_text()
print('SKILL_PILLS_INSERT_SEND_REMOVE_OK')
PYPILLS
