#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
FEATURES = Path('.claude/skills/verify-multiprompt-android/features')
MAP = FEATURES / 'controls.json'
BASELINE = FEATURES / 'control-gaps.json'
TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|[^\s]')
COMPONENTS = set('Button IconButton TextButton OutlinedButton FilledTonalButton FloatingActionButton ExtendedFloatingActionButton Switch Checkbox RadioButton Slider RangeSlider Tab BasicTextField TextField OutlinedTextField KitRoundButton KitExpandButton KitPrimaryButton KitSecondaryButton KitToggle KitSegmented KitMenuRow KitAnswerOption KitSearchBox PickerRow PickerToggle VersionLabel CornerPill action'.split())
COMPONENTS.add('KeyboardActions')
OPTIONAL = set('KitTitlePill KitListRow ComposerListNote'.split())
GESTURES = set('clickable combinedClickable pointerInput toggleable selectable draggable draggable2D anchoredDraggable swipeable scrollable transformable horizontalSwipe horizontalScroll verticalScroll readerScroll nestedScroll'.split())
SURFACES = set('KitSheet KitMenu ModalBottomSheet AlertDialog Dialog'.split())
OTHER = set('BackHandler LazyColumn LazyRow LazyVerticalGrid LazyHorizontalGrid SelectionContainer AndroidView Terminal Url Clickable setContentIntent addAction onClick onLongClick M3Button M3FilledTonalButton M3OutlinedButton M3TextField M3OutlinedTextField'.split())


def tokens(source):
    return [(m.group(), m.start(), m.end()) for m in TOKEN.finditer(source) if not m.group().startswith(('//', '/*'))]


def scan(source, path):
    aliases = {alias: name for name, alias in re.findall(r'^import\s+[\w.]+\.([\w]+)\s+as\s+(\w+)', source, re.M)}
    ts = tokens(source)
    pairs = {}
    stack = []
    for i, (value, _, _) in enumerate(ts):
        if value in ('(', '[', '{'):
            stack.append(i)
        elif value in (')', ']', '}') and stack:
            opening = stack.pop()
            if ts[opening][0] != {')': '(', ']': '[', '}': '{'}[value]:
                raise ValueError(f'{path}: unbalanced Kotlin near {ts[i]}')
            pairs[opening] = i
    declarations = {}
    for i, (value, _, _) in enumerate(ts):
        if value != 'fun':
            continue
        j = i + 1
        while j < len(ts) and ts[j][0] not in ('(', '{', '=', ';'):
            j += 1
        if j < len(ts) and ts[j][0] == '(':
            declarations[j - 1] = ts[j - 1][0]
    regions = []
    for index, name in declarations.items():
        end = pairs.get(index + 1, index + 1)
        body = end + 1
        while body < len(ts) and ts[body][0] not in ('{', '=', 'fun', '@'):
            body += 1
        if body < len(ts) and ts[body][0] == '{':
            regions.append((index, pairs.get(body, body), name))
    result = []
    for i, (name, start, _) in enumerate(ts):
        if i in declarations:
            continue
        owner = next((name for a, b, name in reversed(regions) if a < i < b), 'top-level')
        if name not in COMPONENTS | OPTIONAL | GESTURES | SURFACES | OTHER:
            name = aliases.get(name, name)
        if name == 'action' and not path.endswith('/ui/ReplyableBubble.kt'):
            continue
        if name == 'Tab' and '/ui/' not in path:
            continue
        if name not in COMPONENTS | OPTIONAL | GESTURES | SURFACES | OTHER:
            continue
        if i + 1 >= len(ts) or ts[i + 1][0] not in ('(', '{'):
            continue
        if i and ts[i - 1][0] in ('import', 'fun'):
            continue
        opening = i + 1
        end = pairs.get(opening)
        if end is None:
            continue
        args_end = end
        if ts[opening][0] == '(' and end + 1 < len(ts) and ts[end + 1][0] == '{':
            end = pairs.get(end + 1, end)
        args = source[ts[opening][1]:ts[args_end][2]]
        if name in OPTIONAL and not re.search(r'\bonClick\s*=', args):
            continue
        if name in ('Url', 'Clickable') and (i < 2 or ts[i - 2][0] != 'LinkAnnotation'):
            continue
        if name in SURFACES or name in ('LazyColumn', 'LazyRow', 'LazyVerticalGrid', 'LazyHorizontalGrid', 'SelectionContainer', 'AndroidView'):
            end = args_end
        normalized = ' '.join(t[0] for t in ts[i:end + 1])
        if name in ('onClick', 'onLongClick') and normalized in ('onClick ( )', 'onLongClick ( )'):
            continue
        digest = hashlib.sha256(normalized.encode()).hexdigest()[:16]
        result.append({
            'site': f'{path}:{owner}:{name}:{digest}',
            'source': f'{path}:{source.count(chr(10), 0, start) + 1}',
            'owner': owner,
            'kind': name,
            'anchor': normalized,
        })
    counts = {}
    for site in result:
        key = site['site']
        counts[key] = counts.get(key, 0) + 1
        site['site'] = f'{key}:{counts[key]}'
    return result


def inventory(root=ROOT, ref=None):
    if ref:
        paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', ref, 'app/src/main'], cwd=root, text=True).splitlines()
    else:
        paths = [p.relative_to(root).as_posix() for p in (root / 'app/src/main').rglob('*.kt')]
    sites = []
    for path in sorted(p for p in paths if p.endswith('.kt')):
        source = subprocess.check_output(['git', 'show', f'{ref}:{path}'], cwd=root, text=True) if ref else (root / path).read_text()
        sites.extend(scan(source, path))
    return sites


def validate(sites, data, baseline, registry, base=None):
    errors = []
    actual = {s['site']: s for s in sites}
    entries = data['controls'] + data['forwarding']
    mapped = {e['site'] for e in entries}
    for s in sites:
        if s['site'] not in mapped:
            errors.append(f"UNMAPPED {s['source']} {s['owner']} / {s['kind']}: {s['anchor'][:160]}. Fix: add each control at this site to {MAP} with a stable id, screen, label and journey or missing reason; never extend the gap baseline for a new control.")
    ids = set()
    for entry in entries:
        if entry['id'] in ids:
            errors.append(f"Duplicate control id: {entry['id']}")
        ids.add(entry['id'])
        site = actual.get(entry['site'])
        if not site:
            errors.append(f"Stale map entry {entry['id']}: {entry['source']}. Fix: re-audit the changed source and update its site/source, or remove the deleted control.")
        elif entry['source'] != site['source']:
            errors.append(f"Moved source {entry['id']}: use {site['source']} (run control_map.py locations).")
    missing = {c['id'] for c in data['controls'] if c.get('journey') is None}
    for c in data['controls']:
        if not c.get('screen') or not c.get('label'):
            errors.append(f"{c['id']}: screen and plain label required")
        if c.get('journey') is None:
            if not c.get('missing'):
                errors.append(f"{c['id']}: missing requires a reason, not unreachable")
        else:
            journey = registry['journeys'].get(c['journey'])
            if not journey or journey['runner']['kind'] == 'manual' or not isinstance(c.get('proof'), dict):
                errors.append(f"{c['id']}: live journey must exist, be automated, and cite its action/result in proof")
            if c.get('missing'):
                errors.append(f"{c['id']}: choose journey or missing, not both")
        if re.search(r'unreachable', json.dumps(c), re.I):
            errors.append(f"{c['id']}: unreachable is not a coverage state")
    gaps = set(baseline['missing'])
    for key in sorted(missing - gaps):
        errors.append(f'NEW GAP {key}: add a live journey; existing gaps cannot excuse a new missing control.')
    for key in sorted(gaps - missing):
        errors.append(f'Stale baseline gap {key}: remove it after adding a live journey or deleting the control.')
    if base:
        old_ids = {c['id'] for c in base[0]['controls']}
        old_gaps = set(base[1]['missing'])
        for key in sorted(gaps - old_gaps):
            errors.append(f'BASELINE GROWTH {key}: new gaps are forbidden; wire a live journey instead.')
        for c in data['controls']:
            if c['id'] in gaps and c['id'] not in old_ids:
                errors.append(f"NEW CONTROL GAP {c['id']}: a new control needs a live journey.")
    for f in data['forwarding']:
        if not f.get('reason') or not f.get('component') or not isinstance(f.get('callers'), list):
            errors.append(f"{f['id']}: forwarding must name mapped callers and explain delegation")
        elif any(caller not in ids for caller in f['callers']):
            errors.append(f"{f['id']}: forwarding caller is not a mapped control")
    for c in data['controls']:
        proof = c.get('proof')
        if c.get('journey') is not None and isinstance(proof, dict):
            expected = (FEATURES / c['journey']).as_posix()
            if proof.get('recipe') != expected or not proof.get('action') or not proof.get('result'):
                errors.append(f"{c['id']}: cite the registered recipe, action and checked result")
    return errors


def choice_errors(data, root=ROOT):
    errors = []
    for choice in data['choice_sources']:
        path = root / choice['path']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != choice['sha256']:
            errors.append(f"CHOICES CHANGED {choice['path']}: {choice['label']}. Fix: audit the controls and finite options in controls.json, then update this digest; new controls need live journeys.")
    return errors


def report(data):
    controls = data['controls']
    covered = sum(c.get('journey') is not None for c in controls)
    lines = [f'{covered} of {len(controls)} controls have a live journey', '', 'Missing controls:']
    for c in controls:
        if c.get('journey') is None:
            lines.append(f"- `{c['id']}` | {c['screen']} | {c['label']} | `{c['source']}` | {c['missing']}")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['check', 'inventory', 'report', 'locations'])
    parser.add_argument('--ref')
    parser.add_argument('--base')
    args = parser.parse_args()
    sites = inventory(ref=args.ref)
    if args.command == 'inventory':
        print(json.dumps(sites, indent=2))
        return
    data = json.loads((ROOT / MAP).read_text())
    if args.command == 'locations':
        locations = {s['site']: s['source'] for s in sites}
        for entry in data['controls'] + data['forwarding']:
            if entry['site'] in locations:
                entry['source'] = locations[entry['site']]
        (ROOT / MAP).write_text(json.dumps(data, indent=2) + '\n')
        return
    baseline = json.loads((ROOT / BASELINE).read_text())
    registry = json.loads((ROOT / FEATURES / 'registry.json').read_text())
    base = None
    if args.base:
        def old(path):
            return json.loads(subprocess.check_output(['git', 'show', f'{args.base}:{path}'], cwd=ROOT, text=True))
        exists = subprocess.run(['git', 'cat-file', '-e', f'{args.base}:{MAP}'], cwd=ROOT, capture_output=True).returncode == 0
        if exists:
            base = (old(MAP), old(BASELINE))
        elif baseline['introduced_from'] != args.base:
            raise SystemExit('Initial control baseline must name the exact base SHA; do not recreate a baseline.')
    errors = validate(sites, data, baseline, registry, base) + choice_errors(data)
    print(report(data), end='')
    if errors:
        raise SystemExit('\n'.join(errors))
    if args.command == 'check':
        print('Android control map: PASS')


if __name__ == '__main__':
    main()
