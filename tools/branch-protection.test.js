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

function protectionWithContexts(contexts, strict) {
  return { required_status_checks: { contexts, strict } };
}

test('live required contexts must equal the merge helper list, regardless of order', async () => {
  const { requiredChecks } = await import('./pr-checks.mjs');
  const run = await check(protectionWithContexts([...requiredChecks].reverse(), false));
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /8 required checks match/);
  assert.match(run.stdout, /required_status_checks\.strict is false/);
});

test('required_status_checks.strict accepts true until the flag is flipped', async () => {
  const { requiredChecks } = await import('./pr-checks.mjs');
  const run = await check(protectionWithContexts(requiredChecks, true));
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /required_status_checks\.strict is still true/);
});

test('required_status_checks.strict rejects missing or unknown values', async () => {
  const { requiredChecks } = await import('./pr-checks.mjs');
  for (const strict of [undefined, null, 'yes', 2]) {
    const run = await check(protectionWithContexts(requiredChecks, strict));
    assert.equal(run.status, 1, run.stderr);
    assert.match(run.stderr, /required_status_checks\.strict must be false/);
  }
});

test('the audited 13-context protection fails with every missing check named', async () => {
  const contexts = ['build', 'unit-tests', 'm3-unit-tests', 'lint', 'test', 'image-preview-emulator', 'reply-notifications-emulator', 'session-rows-emulator', 'message-actions-emulator', 'approved-style-emulator', 'secret-scan', 'no-paid-ai', 'session-pins-emulator'];
  const run = await check(protectionWithContexts(contexts, false));
  assert.equal(run.status, 1);
  for (const name of ['pstack-verifier']) {
    assert.ok(run.stderr.includes(name), run.stderr);
  }
});

test('unexpected contexts also fail instead of accepting a superset', async () => {
  const { requiredChecks } = await import('./pr-checks.mjs');
  const run = await check(protectionWithContexts([...requiredChecks, 'obsolete-check'], false));
  assert.equal(run.status, 1);
  assert.match(run.stderr, /unexpected \[obsolete-check\]/);
});

test('missing or malformed required contexts and failed API calls never pass', async () => {
  for (const protection of [{}, { required_status_checks: null }, { required_status_checks: { contexts: [] } }, { required_status_checks: { contexts: ['build', null] } }]) {
    const run = await check(protection);
    assert.equal(run.status, 1);
  }
  const { requiredChecks } = await import('./pr-checks.mjs');
  assert.equal((await check(protectionWithContexts(requiredChecks, false), ['--check'], 1)).status, 1);
});

test('only the read-only --check command is accepted', async () => {
  for (const args of [[], ['--apply'], ['--check', '--extra']]) {
    const run = await check({}, args);
    assert.equal(run.status, 1);
    assert.match(run.stderr, /Usage: node tools\/branch-protection.mjs --check/);
  }
});

test('the lead can remove known retired checks after merge, without a worker protection write', async () => {
  const { requiredChecks, retiringChecks } = await import('./pr-checks.mjs');
  const run = await check(protectionWithContexts([...requiredChecks, ...retiringChecks], false));
  assert.equal(run.status, 0, run.stderr);
  assert.match(run.stdout, /8 target required checks present; lead must remove retired checks after merge/);
  assert.match(run.stdout, /phone-routing/);
  const missing = await check(protectionWithContexts([...requiredChecks.filter(name => name !== 'pstack-verifier'), ...retiringChecks], false));
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /missing \[pstack-verifier\]/);
});
