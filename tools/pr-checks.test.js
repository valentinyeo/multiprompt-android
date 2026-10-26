const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const path = require('node:path');

const gate = import('./pr-checks.mjs');
const original = JSON.parse(readFileSync(path.join(__dirname, 'fixtures/pr-checks/88904b33.json'), 'utf8'));

async function green() {
  const { requiredChecks } = await gate;
  return {
    ...original,
    statusCheckRollup: requiredChecks.map(name => ({ __typename: 'CheckRun', name, status: 'COMPLETED', conclusion: 'SUCCESS' })),
  };
}

test('original secret-only commit cannot pass the check wait', async () => {
  const { waitForChecks } = await gate;
  await assert.rejects(waitForChecks(() => original), /missing \[test, image-preview-emulator/);
  console.log('ORIGINAL_SECRET_ONLY_REJECTED');
});

test('complete green graph stays accepted', async () => {
  const { waitForChecks } = await gate;
  const pr = await green();
  assert.equal(await waitForChecks(() => pr), `COMPLETE_CHECKS_GREEN ${pr.headRefOid}`);
  console.log('COMPLETE_GREEN_ACCEPTED');
});

test('every unconditional PR job is required', async () => {
  const { requiredChecks } = await gate;
  const directory = path.join(__dirname, '../.github/workflows');
  const jobs = [];
  for (const name of readdirSync(directory).filter(name => name.endsWith('.yml'))) {
    const text = readFileSync(path.join(directory, name), 'utf8');
    if (!/^  pull_request:[ \t]*$/m.test(text)) continue;
    const event = text.split(/^  pull_request:[ \t]*$/m)[1].split(/^[^ \n]|^  \w/m)[0];
    if (/^    paths:/m.test(event)) continue;
    const blocks = text.split(/^jobs:\s*$/m)[1].split(/^  ([\w-]+):\s*$/m);
    for (let i = 1; i < blocks.length; i += 2) {
      const explicit = blocks[i + 1].match(/^    name: (.+)$/m)?.[1];
      jobs.push(explicit ?? blocks[i]);
    }
  }
  assert.deepEqual([...requiredChecks].sort(), jobs.sort());
});

test('missing and empty check graphs never pass', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  assert.equal(checkStatus({ ...pr, statusCheckRollup: [] }).ready, false);
  for (const name of (await gate).requiredChecks) {
    assert.equal(checkStatus({ ...pr, statusCheckRollup: pr.statusCheckRollup.filter(c => c.name !== name) }).ready, false, name);
  }
  assert.throws(() => checkStatus({ ...pr, statusCheckRollup: null }), /Missing or truncated/);
});

test('failed, cancelled, skipped and neutral jobs never pass', async () => {
  const { checkStatus } = await gate;
  for (const conclusion of ['FAILURE', 'CANCELLED', 'SKIPPED', 'NEUTRAL', 'TIMED_OUT', 'ACTION_REQUIRED', null]) {
    const pr = await green();
    pr.statusCheckRollup[0].conclusion = conclusion;
    assert.throws(() => checkStatus(pr), /Checks not green/, String(conclusion));
  }
});

test('pending app checks remain waiting', async () => {
  const { checkStatus } = await gate;
  for (const status of ['QUEUED', 'IN_PROGRESS', 'WAITING', 'REQUESTED']) {
    const pr = await green();
    pr.statusCheckRollup[0].status = status;
    pr.statusCheckRollup[0].conclusion = null;
    assert.equal(checkStatus(pr).ready, false);
  }
});

test('conflicts, closed PRs and unknown mergeability never pass', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  assert.throws(() => checkStatus({ ...pr, mergeable: 'CONFLICTING' }), /conflicts/);
  assert.throws(() => checkStatus({ ...pr, state: 'MERGED' }), /must be open/);
  assert.equal(checkStatus({ ...pr, mergeable: 'UNKNOWN' }).ready, false);
  assert.throws(() => checkStatus({ ...pr, headRefOid: '' }), /exact PR revisions/);
});

test('extra check failures and unknown graph entries fail closed', async () => {
  const { checkStatus } = await gate;
  for (const extra of [
    { __typename: 'CheckRun', name: 'verify', status: 'COMPLETED', conclusion: 'FAILURE' },
    { __typename: 'StatusContext', context: 'external', state: 'FAILURE' },
    { name: 'unknown' },
  ]) {
    const pr = await green();
    pr.statusCheckRollup.push(extra);
    assert.throws(() => checkStatus(pr), /Checks not green/);
  }
});

test('same-name passing run cannot hide another failing run', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  pr.statusCheckRollup.push({ ...pr.statusCheckRollup[0], conclusion: 'FAILURE' });
  assert.throws(() => checkStatus(pr), /Checks not green/);
});

test('status contexts cannot impersonate required app check runs', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  pr.statusCheckRollup[0] = { __typename: 'StatusContext', context: 'test', state: 'SUCCESS' };
  assert.equal(checkStatus(pr).ready, false);
});

test('wait polls once every five minutes and stops before thirty-minute API call', async () => {
  const { waitForChecks, intervalMs, timeoutMs } = await gate;
  assert.equal(intervalMs, 300_000);
  assert.equal(timeoutMs, 1_800_000);
  let clock = 0;
  const calls = [];
  await assert.rejects(waitForChecks(() => { calls.push(clock); return original; }, {
    wait: true,
    now: () => clock,
    pause: async ms => { clock += ms; },
    report: () => {},
  }), /thirty.minute/);
  assert.deepEqual(calls, [0, 300_000, 600_000, 900_000, 1_200_000, 1_500_000]);
  assert.ok(clock <= timeoutMs);
});

test('wait accepts completed checks without starting another watcher', async () => {
  const { waitForChecks } = await gate;
  const pr = await green();
  let calls = 0;
  const pauses = [];
  assert.equal(await waitForChecks(() => ++calls === 1 ? original : pr, {
    wait: true, now: () => calls * 300_000,
    pause: async ms => pauses.push(ms), report: () => {},
  }), `COMPLETE_CHECKS_GREEN ${pr.headRefOid}`);
  assert.equal(calls, 2);
  assert.deepEqual(pauses, [300_000]);
});

test('wait stops on changed head or base instead of retrying', async () => {
  const { waitForChecks } = await gate;
  for (const field of ['headRefOid', 'baseRefOid']) {
    let calls = 0;
    await assert.rejects(waitForChecks(() => ++calls === 1 ? original : { ...original, [field]: 'a'.repeat(40) }, {
      wait: true, pause: async () => {}, report: () => {},
    }), /head or base changed/);
    assert.equal(calls, 2);
  }
});

test('API failures and failed checks stop immediately without retry', async () => {
  const { waitForChecks } = await gate;
  let pauses = 0;
  const options = { wait: true, pause: async () => pauses++ };
  await assert.rejects(waitForChecks(() => { throw new Error('API unavailable'); }, options), /API unavailable/);
  const pr = await green();
  pr.statusCheckRollup[0].conclusion = 'FAILURE';
  await assert.rejects(waitForChecks(() => pr, options), /Checks not green/);
  assert.equal(pauses, 0);
});

test('one wait owns a PR; lock is released on success and failure', async () => {
  const { withWaitLock } = await gate;
  const pr = `${process.pid}1027`;
  await withWaitLock(pr, async () => {
    await assert.rejects(withWaitLock(pr, async () => {}), /Another wait owns/);
  });
  await assert.rejects(withWaitLock(pr, async () => { throw new Error('check failure'); }), /check failure/);
  assert.equal(await withWaitLock(pr, async () => 'released'), 'released');
});
