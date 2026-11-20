import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

const ref = process.env.WORKFLOW_REF;
const read = file => ref ? execFileSync('git', ['show', `${ref}:${file}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }) : readFileSync(file, 'utf8');
const jobs = source => [...source.split('\njobs:\n')[1].matchAll(/^  ([\w-]+):$/gm)].map(match => match[1]);
const phones = ['image-preview-emulator', 'reply-notifications-emulator', 'session-rows-emulator', 'session-pins-emulator', 'message-actions-emulator', 'approved-style-emulator'];

test('only cheap rules and the one independent verifier run on PRs', () => {
  const files = ref ? execFileSync('git', ['ls-tree', '--name-only', `${ref}:.github/workflows`], { encoding: 'utf8' }).trim().split('\n') : readdirSync('.github/workflows');
  const triggered = files.filter(file => /^  pull_request:/m.test(read(`.github/workflows/${file}`)));
  assert.deepEqual(triggered.sort(), ['gitleaks.yml', 'no-paid-ai.yml', 'pstack-verifier.yml', 'test.yml']);
  const cheap = read('.github/workflows/test.yml');
  assert.deepEqual(jobs(cheap), ['build', 'test', 'unit-tests', 'm3-unit-tests', 'lint']);
  assert.doesNotMatch(cheap, /android-proof|uses: \.\/\.github\/workflows\/|unit-red|original source|original unshared|original source|git worktree/);
  for (const phase of ['source', 'unit', 'm3', 'lint']) assert.ok(cheap.includes(`run: tools/check.sh ${phase}`), phase);
  const check = read('tools/check.sh');
  assert.match(check, /all\) source_checks; unit_checks; m3_unit_checks; lint_checks ;;/);
  assert.match(check, /tools\/ci\/pr-workflows\.test\.mjs/);
  assert.doesNotMatch(check, /bash tools\/kit-controls\/replay\.sh/);
  assert.match(check, /control_map\.test\.py/);
  assert.doesNotMatch(check, /control_map\.py check/);
  if (!ref) {
    assert.doesNotMatch(cheap, /BASE_SHA/);
    assert.match(cheap, /run: node \.github\/scripts\/pstack-verifier\/coverage\.cjs check/);
    assert.doesNotMatch(cheap, /control_map/);
    assert.doesNotMatch(cheap, /--base/);
    const weekly = read('.github/workflows/maintain-verification.yml');
    assert.match(weekly, /BASE_SHA="\$base" node \.github\/scripts\/pstack-verifier\/coverage\.cjs check/);
    assert.match(weekly, /control_map\.py check --base "\$base"/);
    assert.match(weekly, /CONTROL_MAP_LIVE=1 python3 -B [^\n]*control_map\.test\.py/);
    assert.match(weekly, /continue-on-error: true/);
  }
});

test('all six phone jobs and historical replays run periodically, never on PRs', () => {
  const periodic = read('.github/workflows/android-full.yml');
  assert.match(periodic, /schedule:\n    - cron:/);
  assert.match(periodic, /  workflow_dispatch:/);
  assert.match(periodic, /bash tools\/kit-controls\/replay\.sh/);
  assert.doesNotMatch(periodic, /  pull_request:|phone-routing|needs: build/);
  for (const job of [...phones, 'baseline-profile', 'message-projection-replays', 'historical-controls']) assert.ok(jobs(periodic).includes(job), job);
  for (const name of ['account-journey', 'app-name', 'reply-notifications', 'top-fade', 'multiprompt-parity', 'pstack-verifier-eval']) {
    const workflow = read(`.github/workflows/${name}.yml`);
    assert.doesNotMatch(workflow, /^  pull_request:/m, name);
    assert.match(workflow, /^  schedule:/m, name);
    assert.match(workflow, /^  workflow_dispatch:/m, name);
  }
  const weekly = read('.github/workflows/maintain-verification.yml');
  assert.match(weekly, /Plan every registered journey/);
  const registry = JSON.parse(read('.claude/skills/verify-multiprompt-android/features/registry.json'));
  assert.equal(registry.journeys['bot-photo-refresh.md'].runner.workflow, 'android-full.yml');
  assert.match(read('.claude/skills/verify-multiprompt-android/scripts/maintain.py'), /'bot-photo-refresh.md': \('android-full.yml', 'photo'\)/);
  assert.match(weekly, /maintain\.py plan/);
  assert.match(weekly, /matrix: \$\{\{ fromJSON\(needs\.map\.outputs\.matrix\) \}\}/);
});

test('PR journeys come only from touched registry outcomes; even tooling receives the independent verdict', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.equal((workflow.match(/run: node \.github\/scripts\/pstack-verifier\/verifier\.cjs judge/g) ?? []).length, 1);
  assert.doesNotMatch(workflow, /if:.*no_product_change/);
  assert.match(workflow, /if: needs\.pick\.outputs\.features != '\[\]'/);
  const verifier = read('.github/scripts/pstack-verifier/verifier.cjs');
  assert.doesNotMatch(verifier, /features: \[SMOKE_JOURNEY\]/);
  assert.match(verifier, /validateFeatures\(required\)/);
  assert.doesNotMatch(verifier, /\.\.\.\(picked\.smoke \? \[\] : picked\.features\)/);
  assert.doesNotMatch(workflow, /PARENT_WORKFLOW_EXIT|verifier-tooling-proof|reuse\.cjs/);
});

test('base proof is cached by source and harness, without skipping head or the release gate', () => {
  const workflow = read('.github/workflows/verify.yml');
  assert.match(workflow, /id: parent-cache\n        if: inputs\.side == 'parent'/);
  assert.match(workflow, /parent-cache\.cjs restore/);
  assert.match(workflow, /parent-cache\.cjs save/);
  assert.match(workflow, /name: \$\{\{ steps\.cache-result\.outputs\.key \}\}/);
  const build = workflow.split('      - name: Build debug APK\n')[1].split('      - name:')[0];
  const drive = workflow.split('      - name: Install, doctor and drive the real app\n')[1].split('      - name:')[0];
  for (const step of [build, drive]) assert.match(step, /if:.*steps\.parent-cache\.outputs\.hit != 'true'/);
  assert.doesNotMatch(workflow, /^  (?:pull_request|push|schedule):/m);
  const release = read('.github/workflows/release.yml');
  assert.match(release, /tools\/release-proof\.sh/);
  assert.match(release, /VERIFY_FEATURE: robot-faces\.md/);
});

test('secret-scan runs once per PR head, plus main pushes and periodic full-history scans', () => {
  const workflow = read('.github/workflows/gitleaks.yml');
  const events = workflow.split('\non:\n')[1].split('\npermissions:')[0];
  assert.match(events, /^  push:\n    branches: \[main\]$/m);
  assert.match(events, /^  pull_request:\n  workflow_dispatch:\n  schedule:\n    - cron:/m);
  assert.doesNotMatch(events, /paths|branches-ignore|pull_request_target/);
  assert.deepEqual(jobs(workflow), ['secret-scan']);
  assert.match(workflow, /^    name: secret-scan$/m);
  assert.match(workflow, /group: gitleaks-\$\{\{ github.event.pull_request.number \|\| github.ref \}\}/);
  assert.match(workflow, /fetch-depth: 0/);
  for (const variable of ['PR_BASE_SHA', 'PR_HEAD_SHA', 'PUSH_BEFORE_SHA', 'PUSH_HEAD_SHA']) assert.match(workflow, new RegExp(variable + ': \\$\\{\\{ github\\.'));
  assert.match(workflow, /gitleaks detect.*--redact/);
  assert.match(workflow, /trufflehog git/);
});

test('expensive periodic workflows cancel superseded runs without cancelling sibling matrix jobs', () => {
  for (const name of ['bubble-width', 'long-chat', 'session-rename', 'message-projection']) {
    const workflow = read(`.github/workflows/${name}.yml`);
    assert.ok(workflow.includes(`\nconcurrency:\n  group: ${name}-` + '${{ github.event.pull_request.number || github.ref }}\n  cancel-in-progress: true\n\njobs:\n'), name);
    assert.equal((workflow.match(/concurrency:/g) ?? []).length, 1, name);
  }
});
