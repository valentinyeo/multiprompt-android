const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, writeFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

async function check(protection, args = ['--check'], apiStatus = 0) {
  const directory = mkdtempSync(path.join(tmpdir(), 'branch-protection-'));
  try {
    writeFileSync(path.join(directory, 'gh'), `#!/usr/bin/env node
const assert = require('node:assert/strict');
assert.deepEqual(process.argv.slice(2), ['api', 'repos/valentinyeo/multiprompt-android/branches/main/protection']);
process.stdout.write(${JSON.stringify(JSON.stringify(protection))});
process.exit(${apiStatus});
`, { mode: 0o755 });
    return spawnSync(process.execPath, [path.join(__dirname, 'branch-protection.mjs'), ...args], {
      env: { ...process.env, PATH: `${directory}:${process.env.PATH}` },
      encoding: 'utf8',
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('live required contexts must equal the merge helper list, regardless of order', async () => {
  const { requiredChecks } = await import('./pr-checks.mjs');
  const run = await check({ required_status_checks: { contexts: [...requiredChecks].reverse() } });
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /18 required checks match/);
});

test('the audited 13-context protection fails with every missing check named', async () => {
  const contexts = ['build', 'unit-tests', 'm3-unit-tests', 'lint', 'test', 'image-preview-emulator', 'reply-notifications-emulator', 'session-rows-emulator', 'message-actions-emulator', 'approved-style-emulator', 'secret-scan', 'no-paid-ai', 'session-pins-emulator'];
  const run = await check({ required_status_checks: { contexts } });
  assert.equal(run.status, 1);
  for (const name of ['baseline-profile', 'phone-routing', 'message-projection-proof', 'pick journeys', 'pstack-verifier']) {
    assert.ok(run.stderr.includes(name), run.stderr);
  }
});

test('unexpected contexts also fail instead of accepting a superset', async () => {
  const { requiredChecks } = await import('./pr-checks.mjs');
  const run = await check({ required_status_checks: { contexts: [...requiredChecks, 'obsolete-check'] } });
  assert.equal(run.status, 1);
  assert.match(run.stderr, /unexpected \[obsolete-check\]/);
});

test('missing or malformed required contexts and failed API calls never pass', async () => {
  for (const protection of [{}, { required_status_checks: null }, { required_status_checks: { contexts: [] } }, { required_status_checks: { contexts: ['build', null] } }]) {
    const run = await check(protection);
    assert.equal(run.status, 1);
  }
  const { requiredChecks } = await import('./pr-checks.mjs');
  assert.equal((await check({ required_status_checks: { contexts: requiredChecks } }, ['--check'], 1)).status, 1);
});

test('only the read-only --check command is accepted', async () => {
  for (const args of [[], ['--apply'], ['--check', '--extra']]) {
    const run = await check({}, args);
    assert.equal(run.status, 1);
    assert.match(run.stderr, /Usage: node tools\/branch-protection.mjs --check/);
  }
});
