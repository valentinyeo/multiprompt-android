import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / 'tools' / 'historical-swaps.json'
EXPECTED_PIN = 'PINNED_STRIP_MUST_NOT_SHOW'
COMPILE_MARKERS = ('Compilation error', 'Argument type mismatch', 'e: file://')
PIN_CASES = {'readerNormal', 'readerReversed', 'material3Normal', 'material3Reversed'}
QUOTE = re.compile(r'''git\s+show\s+"([^":]+):([^"]+)"(.*)$''')
MIXED = re.compile(r'''git\s+show\s+([^:\s]+):"([^"]+)"(.*)$''')
BARE = re.compile(r'''git\s+show\s+([^:\s"]+):(\S+)(.*)$''')
ARCHIVE = re.compile(r'git\s+archive\s+(\S+)\s+((?:app/\S+\s*)+)')
PY_SHOW = re.compile(r'''['"]show['"]\s*,\s*['"]([0-9a-fA-F]{7,40}):['"]\s*\+''')
CP = re.compile(r'^\s*cp\s+(\S+)\s+(\S+)\s*$')
SCALAR = re.compile(r'^(\w+)=(\S+)$')
ARRAY = re.compile(r'^(\w+)=\((.*)\)$')


def norm(token):
    return token.strip().strip('"').strip("'")


def shell_bindings(text):
    assigns = {}
    arrays = {}
    for raw in text.splitlines():
        line = raw.strip()
        array = ARRAY.match(line)
        if array:
            arrays[array.group(1)] = array.group(2).split()
            continue
        scalar = SCALAR.match(line)
        if scalar and not scalar.group(2).startswith('$('):
            assigns[scalar.group(1)] = norm(scalar.group(2))
    return assigns, arrays


def resolve(token, assigns):
    if token is None:
        return None
    token = norm(token)
    match = re.fullmatch(r'\$\{?(\w+)\}?', token)
    if match:
        return assigns.get(match.group(1))
    if token.startswith('$'):
        return None
    return token


def show_calls(text):
    found = []
    for raw in text.splitlines():
        line = raw.strip()
        match = MIXED.search(line) or QUOTE.search(line) or BARE.search(line)
        if not match:
            continue
        rest = match.group(3)
        redirect = None
        redirected = re.search(r'>\s*(\S+)', rest)
        if redirected:
            redirect = redirected.group(1).rstrip(';')
        found.append((match.group(1), match.group(2), redirect))
    return found


def app_boundaries(path_token, redirect, assigns, arrays):
    resolved = resolve(path_token, assigns)
    if resolved and resolved.startswith('app/'):
        return [resolved]
    if norm(path_token) in {'$file', '${file}'} and norm(redirect or '') in {'$file', '${file}'}:
        return [path for path in arrays.get('files', []) if path.startswith('app/')]
    if path_token.startswith('app/'):
        return [path_token]
    return []


def add_swap(found, script, revision, boundary):
    if boundary.startswith('app/'):
        found.add((script, revision, boundary))


def find_swaps(text, script):
    assigns, arrays = shell_bindings(text)
    found = set()
    copies = []
    for raw in text.splitlines():
        copied = CP.match(raw.strip())
        if copied:
            copies.append((norm(copied.group(1)), norm(copied.group(2))))
    for revision, path_token, redirect in show_calls(text):
        boundaries = app_boundaries(path_token, redirect, assigns, arrays)
        if not boundaries:
            continue
        dest = resolve(redirect, assigns) if redirect else None
        redirect_norm = norm(redirect) if redirect else None
        shown = 'DAY_THEME_BASE_REF' if revision == '$base' and 'DAY_THEME_BASE_REF' in text else revision
        for boundary in boundaries:
            direct = dest == boundary or redirect_norm == norm(path_token)
            copied_back = any(
                source == redirect_norm and (resolve(target, assigns) == boundary or target == boundary)
                for source, target in copies
            )
            rewritten = 'write_text' in text and dest != boundary
            if direct or copied_back or rewritten:
                add_swap(found, script, shown, boundary)
    if 'write_text' in text:
        for match in PY_SHOW.finditer(text):
            for value in assigns.values():
                if value.startswith('app/'):
                    add_swap(found, script, match.group(1), value)
    for raw in text.splitlines():
        archived = ARCHIVE.search(raw)
        if not archived or '-C' in raw or not re.search(r'\|\s*tar\b[^\n]*-x\b', raw):
            continue
        for boundary in archived.group(2).split():
            add_swap(found, script, archived.group(1), boundary)
    return sorted(found)


def source_files(root=ROOT):
    files = []
    for base in (root / 'tools', root / '.github' / 'workflows'):
        if not base.exists():
            continue
        for path in base.rglob('*'):
            if not path.is_file() or path.suffix not in {'.sh', '.yml', '.yaml'}:
                continue
            if 'vendor' in path.parts or 'third_party' in path.parts:
                continue
            files.append(path)
    return files


def scan(root=ROOT):
    found = []
    for path in source_files(root):
        text = path.read_text(encoding='utf-8')
        script = path.relative_to(root).as_posix()
        found.extend(find_swaps(text, script))
    return sorted(set(found))


def load_baseline(path=BASELINE):
    data = json.loads(path.read_text(encoding='utf-8'))
    return sorted((item['script'], item['revision'], item['boundary']) for item in data['swaps'])


def swap_problem(swap):
    script, revision, boundary = swap
    return (
        f'HISTORICAL_SOURCE_SWAP: {script} writes {revision}:{boundary} into the current app. '
        'Build that complete revision in its own worktree, or install a retained APK. '
        "Do not compile old source against today's APIs."
    )


def baseline_problems(found, baseline):
    problems = [swap_problem(swap) for swap in found if swap not in baseline]
    for swap in baseline:
        if swap not in found:
            script, revision, boundary = swap
            problems.append(
                'HISTORICAL_SWAP_BASELINE_STALE: '
                f'tools/historical-swaps.json still lists {script} {revision}:{boundary}. '
                'Remove that line once the swap is gone.'
            )
    return problems


def control_invalid(expected):
    return (
        'CONTROL_INVALID: baseline did not build or launch; '
        f'expected {expected} was never evaluated. '
        'Do not add a production compatibility overload.'
    )


def failure_text(case):
    parts = []
    for node in list(case.findall('failure')) + list(case.findall('error')):
        parts.append(' '.join(node.itertext()))
        parts.extend(node.attrib.values())
    return '\n'.join(parts)


def load_cases(results):
    cases = []
    root = Path(results)
    if not root.exists():
        return cases
    for path in root.rglob('*.xml'):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        cases.extend(tree.getroot().iter('testcase'))
    return cases


def fields(buildable, launched, observed, expected):
    return (
        f'buildable={str(buildable).lower()} launched={str(launched).lower()} '
        f'assertionObserved={str(observed).lower()} expected={expected}'
    )


def classify_log(log, expected):
    buildable = not any(marker in log for marker in COMPILE_MARKERS)
    launched = False
    observed = False
    header = fields(buildable, launched, observed, expected)
    if buildable:
        return '\n'.join([header, f'compile failure missing; {expected} was not classified']), 1
    return '\n'.join([header, control_invalid(expected)]), 0


def classify_run(phase, expected, log, results, status):
    compile_failed = any(marker in log for marker in COMPILE_MARKERS)
    cases = [case for case in load_cases(results) if case.get('classname', '').endswith('SessionPinsTest') or case.get('classname', '').endswith('HistoricalPinsOracle')]
    if not cases:
        cases = load_cases(results)
    names = {case.get('name') for case in cases}
    failures = [failure_text(case) for case in cases]
    saw = any(expected in text for text in failures)
    clean = bool(cases) and names == PIN_CASES and all(
        not case.findall('failure') and not case.findall('error') and not case.findall('skipped')
        for case in cases
    )
    red = bool(cases) and names == PIN_CASES and all(
        len(case.findall('failure')) == 1 and expected in failure_text(case)
        and not case.findall('error') and not case.findall('skipped')
        for case in cases
    )
    buildable = not compile_failed and (clean or red or saw)
    launched = buildable and bool(cases)
    if phase == 'before':
        observed = red
        ok = red
    else:
        observed = clean and status == 0
        ok = observed
    header = fields(buildable, launched, observed, expected)
    if phase == 'after' and buildable and launched and saw:
        return '\n'.join([header, f'{expected} returned on the current app']), 1
    if phase == 'before' and buildable and launched and clean:
        return '\n'.join([header, f'HISTORICAL_RED_MISSING: the complete old app did not fail {expected}']), 1
    if ok:
        return header, 0
    return '\n'.join([header, control_invalid(expected)]), 1


def main(argv):
    command = argv[1] if len(argv) > 1 else 'scan'
    if command == 'scan':
        problems = baseline_problems(scan(), load_baseline())
        if problems:
            print('\n'.join(problems))
            return 1
        print(f'HISTORICAL_SWAPS_MATCH {len(load_baseline())}')
        return 0
    if command == 'classify-log':
        expected = EXPECTED_PIN
        log_path = None
        args = argv[2:]
        index = 0
        while index < len(args):
            if args[index] == '--expected':
                expected = args[index + 1]
                index += 2
            elif args[index] == '--log':
                log_path = args[index + 1]
                index += 2
            else:
                raise SystemExit(f'unknown argument {args[index]}')
        text, code = classify_log(Path(log_path).read_text(encoding='utf-8'), expected)
        print(text)
        return code
    if command == 'classify-run':
        options = {'--phase': None, '--expected': EXPECTED_PIN, '--log': None, '--results': None, '--status': None}
        args = argv[2:]
        index = 0
        while index < len(args):
            if args[index] not in options:
                raise SystemExit(f'unknown argument {args[index]}')
            options[args[index]] = args[index + 1]
            index += 2
        status = int(Path(options['--status']).read_text(encoding='utf-8').strip())
        log = Path(options['--log']).read_text(encoding='utf-8', errors='replace')
        text, code = classify_run(options['--phase'], options['--expected'], log, options['--results'], status)
        print(text)
        return code
    raise SystemExit(f'unknown command {command}')


if __name__ == '__main__':
    sys.exit(main(sys.argv))
