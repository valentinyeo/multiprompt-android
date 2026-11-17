#!/usr/bin/env bash
snippet=VERIFY_SNIPPET_2537
tap_text verify-session
wait_for_text 'Message verify-session'
wait_for_text VERIFY_READY_528
fill 'Message verify-session' "$snippet"
shot snippets-draft
tap_desc Attach
tap_text Snippets
wait_for_text 'No snippets yet'
shot snippets-empty
tap_text 'Save current text'
wait_for_text Delete
wait_for_absence 'No snippets yet'
shot snippets-saved
if sudo grep -F "VERIFY_RECEIVED:$snippet" /home/verifyssh/receipts.txt; then
    printf 'Saving a snippet sent the draft to the host\n' >&2
    exit 1
fi
relaunch_app
tap_text verify-session
wait_for_text "$snippet"
tap_node editor ''
adb shell input keycombination 113 29
adb shell input text replacement%sdraft
dump_ui
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value composer 'replacement draft'
tap_desc Attach
tap_text Snippets
wait_for_text "$snippet"
wait_for_text Delete
shot snippets-retained
tap_text "$snippet"
select_node focused-field "$snippet" >/dev/null
python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value composer "$snippet"
wait_for_absence 'Save current text'
shot snippets-reused
tap_desc 'Send prompt'
wait_receipt "VERIFY_RECEIVED:$snippet"
wait_for_text "VERIFY_RECEIVED:$snippet"
shot snippets-sent
tap_desc Attach
tap_text Snippets
wait_for_text Delete
tap_text Delete
wait_for_text 'No snippets yet'
wait_for_absence Delete
shot snippets-deleted
relaunch_app
tap_text verify-session
wait_for_text 'Message verify-session'
tap_desc Attach
tap_text Snippets
wait_for_text 'No snippets yet'
wait_for_absence Delete
shot snippets-deleted-restarted
sudo cat /home/verifyssh/receipts.txt > "$EVIDENCE/snippets-receipts.txt"
[[ $(grep -Fxc "VERIFY_RECEIVED:$snippet" "$EVIDENCE/snippets-receipts.txt") == 1 ]]
python3 - "$EVIDENCE" "$snippet" <<'PYSNIPPETS'
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
root = Path(sys.argv[1])
snippet = sys.argv[2]
states = {}
for phase in ('empty', 'saved', 'retained', 'reused', 'deleted', 'deleted-restarted'):
    files = list(root.glob(f'[0-9][0-9]-snippets-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    nodes = list(ET.parse(files[0]).getroot().iter('node'))
    texts = [n.get('text') for n in nodes if n.get('text') in (snippet, 'Delete', 'No snippets yet', 'Save current text')]
    editors = [n.get('text') for n in nodes if n.get('class') == 'android.widget.EditText']
    if phase in ('saved', 'retained'):
        assert any(n.get('text') == snippet and n.get('class') != 'android.widget.EditText' for n in nodes), phase
        assert 'Delete' in texts and 'No snippets yet' not in texts, phase
    elif phase == 'reused':
        assert editors == [snippet], editors
        assert 'Save current text' not in texts, texts
    else:
        assert 'No snippets yet' in texts and 'Delete' not in texts, phase
    states[phase] = dict(screenshot=files[0].with_suffix('.png').name, texts=texts, composer=editors)
root.joinpath('snippets-state.json').write_text(json.dumps(states, indent=2) + '\n')
print('SNIPPETS_SAVE_REUSE_DELETE_OK')
PYSNIPPETS
