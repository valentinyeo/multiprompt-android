const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const scripts = path.join(root, '.claude/skills/verify-multiprompt-android/scripts');
const driver = readFileSync(path.join(scripts, 'drive.sh'), 'utf8');
const shellFunction = name => driver.match(new RegExp(`^${name}\\(\\) \\{[\\s\\S]*?^\\}`, 'm'))[0];

for (const content of [null, '{', '{"sessions":400}']) {
  test(`driver rejects ${content === null ? 'missing' : content === '{' ? 'malformed' : 'fabricated'} seed evidence before any adb action`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'seed-driver-'));
    try {
      const bin = path.join(directory, 'bin');
      mkdirSync(bin);
      const evidence = path.join(directory, 'evidence');
      mkdirSync(evidence);
      if (content !== null) writeFileSync(path.join(evidence, 'seed-counts.json'), content);
      writeFileSync(path.join(bin, 'sudo'), `#!/usr/bin/env bash\nexec python3 -I ${JSON.stringify(path.join(root, 'tools/verify-seed.py'))} check "$EVIDENCE/seed-counts.json"\n`, { mode: 0o755 });
      writeFileSync(path.join(bin, 'adb'), '#!/usr/bin/env bash\ntouch "$FIXTURE/adb-called"\nexit 99\n', { mode: 0o755 });
      const result = spawnSync('bash', [path.join(scripts, 'drive.sh'), 'session-reader.md'], {
        encoding: 'utf8', cwd: root,
        env: { ...process.env, HOME: directory, PATH: `${bin}:${process.env.PATH}`, EVIDENCE: evidence, FIXTURE: directory, EXPECTED_VERSION: 'test' },
      });
      assert.notEqual(result.status, 0, result.stdout + result.stderr);
      assert.equal(existsSync(path.join(directory, 'adb-called')), false);
      assert.equal(existsSync(path.join(evidence, 'failure-class.txt')), false);
      assert.match(readFileSync(path.join(evidence, 'driver-transcript.txt'), 'utf8'), /verify-seed.py check/);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test('an off-screen named target is revealed without opening it', () => {
  const result = spawnSync('bash', ['-euc', `
${shellFunction('wait_for_text')}
select_node() { return 1; }
dismiss_system_anr() { :; }
sleep() { :; }
python3() { [[ "$*" == *'text Inbox' ]]; }
tap_node() { [[ "$*" == 'text verify-session reveal' ]]; printf 'NAMED_TARGET_REVEALED\\n'; }
scripts=unused
EVIDENCE=unused
wait_for_text verify-session
`], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), 'NAMED_TARGET_REVEALED');
});

test('reveal uses the existing scroll planner but never taps the row', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'seed-reveal-'));
  try {
    const result = spawnSync('bash', ['-euc', `
${shellFunction('tap_node')}
dump_ui() { :; }
python3() { if [[ -f "$EVIDENCE/scrolled" ]]; then echo 'tap 10 20'; else echo 'swipe 10 80 10 20'; fi; }
adb() { printf '%s\\n' "$*"; touch "$EVIDENCE/scrolled"; }
scripts=unused
EVIDENCE=${JSON.stringify(directory)}
tap_node text verify-session reveal
tap_node text verify-session
`], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.stdout.trim().split('\n'), ['shell input swipe 10 80 10 20 500', 'shell input tap 10 20']);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('all fixture consumers reuse setup seeds rather than creating them mid-journey', () => {
  const fixture = readFileSync(path.join(scripts, 'fixture.sh'), 'utf8');
  assert.equal(fixture.match(/bash \/home\/verifyssh\/seed.sh/g).length, 1);
  assert.match(fixture, /verify-seed.py record > "\$EVIDENCE\/seed-counts.json"/);
  assert.doesNotMatch(readFileSync(path.join(scripts, 'pins-drive.sh'), 'utf8'), /tmux new-session/);
  assert.doesNotMatch(readFileSync(path.join(root, '.github/workflows/long-chat.yml'), 'utf8'), /tmux new-session/);
});
