#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
SKILL = Path('.claude/skills/verify-multiprompt-android')
DEDICATED = {
    'app-name.md': ('app-name.yml', 'app_name'),
    'chat-working.md': ('working-dots.yml', 'working_dots'),
    'bot-photo-refresh.md': ('test.yml', 'photo'),
}
FLAGS = ('released_chat', 'released_robots', 'release_choices', 'pins_scroll', 'candidate')


def plan(registry):
    matrix = []
    blocked = {}
    dedicated = {value[1]: False for value in DEDICATED.values()}
    for feature, journey in registry['journeys'].items():
        runner = journey['runner']
        if runner['kind'] in ('debug', 'signed') and runner['workflow'] == 'verify.yml':
            inputs = runner['inputs']
            unknown = set(inputs) - set(FLAGS) - {'feature'}
            if unknown:
                blocked[feature] = f'Unsupported runner inputs: {sorted(unknown)}'
                continue
            matrix.append({
                'journey': feature,
                'feature': inputs.get('feature', feature),
                **{flag: bool(inputs.get(flag, False)) for flag in FLAGS},
                'candidate': runner['kind'] == 'signed',
            })
        elif feature in DEDICATED and runner['workflow'] == DEDICATED[feature][0] and runner['kind'] == 'dedicated':
            dedicated[DEDICATED[feature][1]] = True
        else:
            blocked[feature] = '; '.join(runner['prerequisites']) or f"Unsupported runner: {runner['kind']} / {runner['workflow']}"
    return {'matrix': {'include': matrix}, 'blocked': blocked, **dedicated}


def conclusions(registry, pages):
    blocked = plan(registry)['blocked']
    jobs = [job for page in pages for job in page['jobs']]
    failures = {}
    for feature in registry['journeys']:
        if feature in blocked:
            continue
        prefix = f'journey {feature}'
        matches = [j for j in jobs if prefix in j['name'].split(' / ')]
        if not matches:
            failures[feature] = 'missing job; no live proof'
        elif any(j['conclusion'] != 'success' for j in matches):
            failures[feature] = ', '.join(sorted({j['conclusion'] or j['status'] for j in matches if j['conclusion'] != 'success'}))
    return failures, blocked


def body(source, failures, blocked, run_url):
    lines = [
        '# Android feature-map maintenance', '', f'Run: {run_url}', '',
        f"Source check before / after location refresh: {source['before']} / {source['after']}", '',
        'Only source locations are corrected automatically. Changed controls, choice digests and product failures require review.', '',
        '## Failing journeys', '',
    ]
    lines.extend(f'- `{feature}`: {reason}' for feature, reason in failures.items())
    if not failures:
        lines.append('None.')
    lines.extend(['', '## Blocked journeys (not live coverage)', ''])
    lines.extend(f'- `{feature}`: {reason}' for feature, reason in blocked.items())
    if not blocked:
        lines.append('None.')
    return '\n'.join(lines) + '\n'


def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def publish(evidence, text, source, failures, blocked):
    repo = os.environ['GITHUB_REPOSITORY']
    base = os.environ['DEFAULT_BRANCH']
    branch = 'maintain/' + datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    existing = json.loads(run('gh', 'pr', 'list', '--repo', repo, '--state', 'open', '--head', branch, '--json', 'number,baseRefName,url'))
    if existing and existing[0]['baseRefName'] != base:
        raise RuntimeError('Maintenance branch already has a PR against another base')
    corrected = (evidence / 'controls.json').read_bytes() if (evidence / 'controls.json').is_file() else None
    run('git', 'fetch', 'origin', base)
    remote = run('git', 'ls-remote', '--heads', 'origin', branch)
    if remote:
        run('git', 'fetch', 'origin', branch)
        run('git', 'switch', '-c', branch, 'FETCH_HEAD')
    else:
        run('git', 'switch', '-c', branch, f'origin/{base}')
    if corrected is not None and source['before'] != 0:
        (ROOT / SKILL / 'features/controls.json').write_bytes(corrected)
    (ROOT / SKILL / 'maintenance.md').write_text(text)
    run('git', 'add', str(SKILL / 'features/controls.json'), str(SKILL / 'maintenance.md'))
    changed = subprocess.run(['git', 'diff', '--cached', '--quiet'], cwd=ROOT).returncode
    if changed:
        run('git', '-c', 'user.name=github-actions[bot]', '-c', 'user.email=41898282+github-actions[bot]@users.noreply.github.com', 'commit', '-m', 'IGSH-2665: Refresh Android feature-map evidence')
        run('git', 'push', 'origin', f'HEAD:refs/heads/{branch}')
    pr_body = evidence / 'pr-body.md'
    pr_body.write_text('\n'.join([
        '## Why',
        'Keep the Android feature map true through a weekly hosted run.',
        '## What changed',
        'Refresh moved source locations and record failures without changing product behavior.',
        '## Verification',
        f"Run: {os.environ['RUN_URL']}",
        f"Source check before / after refresh: {source['before']} / {source['after']}",
        'Failing journeys: ' + (', '.join(f'`{feature}` ({reason})' for feature, reason in failures.items()) or 'none') + '.',
        'Blocked journeys: ' + (', '.join(f'`{feature}` ({reason})' for feature, reason in blocked.items()) or 'none') + '.',
        f'[Full report]({SKILL}/maintenance.md). Review source-check logs and journey artifacts; never auto-merge.',
    ]) + '\n')
    if existing:
        run('gh', 'pr', 'edit', str(existing[0]['number']), '--repo', repo, '--body-file', str(pr_body))
        return existing[0]['url']
    return run('gh', 'pr', 'create', '--repo', repo, '--base', base, '--head', branch, '--title', 'IGSH-2665: Keep the Android feature map true', '--body-file', str(pr_body))


def main():
    registry = json.loads((ROOT / SKILL / 'features/registry.json').read_text())
    if sys.argv[1] == 'plan':
        result = plan(registry)
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key in ('matrix', 'app_name', 'working_dots', 'photo'):
                output.write(f'{key}={json.dumps(result[key], separators=(",", ":"))}\n')
        return
    if sys.argv[1] != 'report':
        raise SystemExit('Use plan or report <evidence-directory>')
    evidence = Path(sys.argv[2]).resolve()
    source_path = evidence / 'source-result.json'
    source = json.loads(source_path.read_text()) if source_path.is_file() else {'before': -1, 'after': -1}
    failures, blocked = conclusions(registry, json.loads((evidence / 'jobs.json').read_text()))
    text = body(source, failures, blocked, os.environ['RUN_URL'])
    (evidence / 'report.md').write_text(text)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(text)
    if source['before'] or source['after'] or failures or blocked:
        print(publish(evidence, text, source, failures, blocked))
        raise SystemExit('Android feature-map drift or incomplete journey coverage; review the maintenance PR.')
    print('Android feature map: clean; every registered journey passed')


if __name__ == '__main__':
    main()
