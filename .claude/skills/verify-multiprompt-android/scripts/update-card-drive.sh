#!/usr/bin/env bash
manifest_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3-update.json
if [[ "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion ]]; then
    manifest_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json
fi
curl -fsSL --retry 3 "$manifest_url" -o "$EVIDENCE/offered-manifest.json"
python3 -B tools/update-card/notes.py "$EVIDENCE/offered-manifest.json" > "$EVIDENCE/offered-notes.json"
offered=$(python3 - "$EVIDENCE/offered-notes.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1]))['versionName'])
PY
)
installed_version_code() {
    adb shell dumpsys package "$package" | grep -o 'versionCode=[0-9]*' | head -1 | cut -d= -f2 | tr -d '\r'
}
expected_lines() {
    python3 - "$EVIDENCE/offered-notes.json" "$1" <<'PY'
import json
import sys
report = json.load(open(sys.argv[1]))
print('\n'.join(report['preview'] if sys.argv[2] == 'preview' else report['lines']))
PY
}
scroll_card() {
    local x1 y1 x2 y2
    dump_ui || return 1
    read -r x1 y1 x2 y2 < <(python3 "$scripts/ui.py" "$EVIDENCE/current.xml" scroll-back "") || return 1
    adb shell input swipe "$x2" "$y2" "$x1" "$y1" 400
}
observe_lines() {
    local view=$1 line found
    : > "$EVIDENCE/update-card-$view-lines.txt"
    while IFS= read -r line; do
        found=0
        for attempt in 1 2 3 4 5 6; do
            if select_node text "$line" >/dev/null 2>&1; then found=1; break; fi
            scroll_card || break
        done
        [[ "$found" == 1 ]]
        printf 'FOUND %s\n' "$line" | tee -a "$EVIDENCE/update-card-$view-lines.txt"
    done < <(expected_lines "$view")
}
card_state() {
    local name=$1 view=$2
    shot "$name"
    python3 - "$EVIDENCE" "$name" "$view" "$package" <<'PY'
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

evidence, name, view, package = sys.argv[1:]
root = Path(evidence)
xml = sorted(root.glob(f"[0-9][0-9]-{name}.xml"))[-1]
texts = [
    node.get('text') or ''
    for node in ET.parse(xml).getroot().iter('node')
    if node.get('package') == package
]
report = json.loads((root / 'offered-notes.json').read_text(encoding='utf-8'))
lines, preview = report['lines'], report['preview']
assert lines, 'the offered release carries no change line to show'
if view == 'preview':
    for line in lines[3:]:
        assert line not in texts, f'the card shows more than three changes: {line}'
    assert 'See all' in texts, 'the card offers no See all'
    assert 'Show less' not in texts, 'the card already shows every change'
    assert f"v{report['versionName']} available" in texts, 'the offered version is not shown'
else:
    assert 'Show less' in texts, 'See all did not open the complete notes'
    assert 'See all' not in texts, 'the complete notes still offer See all'
(root / f'{name}.json').write_text(
    json.dumps({'view': view, 'offered': report['versionName'], 'lines': lines,
                'preview': preview, 'xml': xml.name, 'texts': [t for t in texts if t]},
               ensure_ascii=False) + '\n',
    encoding='utf-8',
)
print(f'UPDATE_CARD_{view.upper()}_OK')
PY
}
before=$(installed_version_code)
[[ -n "$before" ]]
tap_text verify-session
wait_for_text 'Message verify-session' 1 60
tap_desc "Version ${EXPECTED_VERSION}, open release notes"
wait_for_text 'App updates' 1 60
for attempt in 1 2 3; do
    if select_node text "v${offered} available" >/dev/null 2>&1; then break; fi
    if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text 'Check now' >/dev/null 2>&1; then
        tap_text 'Check now'
    fi
    sleep 5
done
wait_for_text "v${offered} available" 2 120
card_state update-card-preview preview
observe_lines preview
tap_node text 'Download and update' reveal
tap_text 'See all'
tap_node text 'Show less' reveal
card_state update-card-notes notes
observe_lines notes
after=$(installed_version_code)
[[ "$after" == "$before" ]]
python3 - "$EVIDENCE" "$before" <<'PY'
import json
import sys
from pathlib import Path

evidence = Path(sys.argv[1])
requested = json.loads((evidence / 'offered-notes.json').read_text(encoding='utf-8'))


def found(view):
    lines = (evidence / f'update-card-{view}-lines.txt').read_text(encoding='utf-8').splitlines()
    return [line[len('FOUND '):] for line in lines if line.startswith('FOUND ')]


assert found('preview') == requested['preview'], found('preview')
assert found('notes') == requested['lines'], found('notes')
result = {
    'offeredVersionName': requested['versionName'],
    'offeredVersionCode': requested['versionCode'],
    'installedVersionCode': sys.argv[2],
    'previewLines': found('preview'),
    'allLines': found('notes'),
    'installActionLeftUntouched': True,
}
source = evidence / 'app-source-sha.txt'
result['appSourceSha'] = source.read_text().strip() if source.is_file() else ''
(evidence / 'update-card-result.json').write_text(
    json.dumps(result, indent=2, sort_keys=True) + '\n', encoding='utf-8')
print('UPDATE_CARD_OK')
PY
