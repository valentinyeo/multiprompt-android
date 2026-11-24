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
  const { waitForChecks, requiredChecks } = await gate;
  const present = new Set(original.statusCheckRollup.filter(check => check.__typename === 'CheckRun').map(check => check.name));
  const missing = requiredChecks.filter(name => !present.has(name));
  assert.ok(missing.includes('test'), 'the original commit must be rejected for missing the long check');
  await assert.rejects(waitForChecks(() => original), new RegExp(`missing \\[${missing.join(', ')}\\]`));
  console.log('ORIGINAL_SECRET_ONLY_REJECTED');
});

test('complete green graph stays accepted', async () => {
  const { waitForChecks } = await gate;
  const pr = await green();
  assert.equal(await waitForChecks(() => pr), `COMPLETE_CHECKS_GREEN ${pr.headRefOid}`);
  console.log('COMPLETE_GREEN_ACCEPTED');
});

test('only the verdict and cheap unconditional PR jobs are required', async () => {
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
      if (/^    uses: /m.test(blocks[i + 1])) continue;
      const explicit = blocks[i + 1].match(/^    name: (.+)$/m)?.[1];
      if (blocks[i] !== 'pick') jobs.push(explicit ?? blocks[i]);
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

test('failed, cancelled, neutral and unexpectedly skipped jobs never pass', async () => {
  const { checkStatus } = await gate;
  for (const conclusion of ['FAILURE', 'CANCELLED', 'SKIPPED', 'NEUTRAL', 'TIMED_OUT', 'ACTION_REQUIRED', null]) {
    const pr = await green();
    pr.statusCheckRollup[0].conclusion = conclusion;
    assert.throws(() => checkStatus(pr), /Checks not green/, String(conclusion));
  }
});

test('skipped required checks cannot pass; only the unexpanded journey matrix may skip', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  for (const check of pr.statusCheckRollup) {
    assert.throws(() => checkStatus({ ...pr, statusCheckRollup: pr.statusCheckRollup.map(c => c === check ? { ...c, conclusion: 'SKIPPED' } : c) }), /Checks not green/, check.name);
  }
  for (const name of ['journey ${{ matrix.feature }}', 'parent journey ${{ matrix.feature }}']) {
    assert.equal(checkStatus({ ...pr, statusCheckRollup: [...pr.statusCheckRollup, { __typename: 'CheckRun', name, status: 'COMPLETED', conclusion: 'SKIPPED' }] }).ready, true);
  }
  for (const name of ['journey native-skills.md', 'parent journey native-skills.md', 'journey native-skills.md / verify']) {
    assert.throws(() => checkStatus({ ...pr, statusCheckRollup: [...pr.statusCheckRollup, { __typename: 'CheckRun', name, status: 'COMPLETED', conclusion: 'SKIPPED' }] }), /Checks not green/, name);
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

test('stale cancelled and failed runs do not block newer successes', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  const stale = pr.statusCheckRollup.filter(check => ['pick journeys', 'pstack-verifier'].includes(check.name)).map(check => ({
    ...check,
    workflowName: 'pstack-verifier',
    startedAt: '2026-11-18T01:00:00Z',
    completedAt: '2026-11-18T03:00:00Z',
    conclusion: check.name === 'pick journeys' ? 'CANCELLED' : 'FAILURE',
  }));
  for (const check of pr.statusCheckRollup) {
    check.workflowName = 'pstack-verifier';
    check.startedAt = '2026-11-18T02:00:00Z';
  }
  for (const statusCheckRollup of [stale.concat(pr.statusCheckRollup), pr.statusCheckRollup.concat(stale)]) {
    assert.equal(checkStatus({ ...pr, statusCheckRollup }).ready, true);
  }
});

test('newer failure cannot be hidden by older success', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  const older = { ...pr.statusCheckRollup[0], startedAt: '2026-11-18T01:00:00Z' };
  const newer = { ...older, startedAt: '2026-11-18T02:00:00Z', conclusion: 'FAILURE' };
  for (const runs of [[older, newer], [newer, older]]) {
    assert.throws(() => checkStatus({ ...pr, statusCheckRollup: runs.concat(pr.statusCheckRollup.slice(1)) }), /Checks not green: build/);
  }
});

test('newer pending run waits instead of accepting older success or failure', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  for (const conclusion of ['SUCCESS', 'FAILURE']) {
    const older = { ...pr.statusCheckRollup[0], startedAt: '2026-11-18T01:00:00Z', conclusion };
    const newer = { ...older, startedAt: '2026-11-18T02:00:00Z', status: 'IN_PROGRESS', conclusion: null };
    for (const runs of [[older, newer], [newer, older]]) {
      const status = checkStatus({ ...pr, statusCheckRollup: runs.concat(pr.statusCheckRollup.slice(1)) });
      assert.equal(status.ready, false);
      assert.match(status.message, /Waiting: missing \[\], pending 1/);
    }
  }
});

test('check recency falls back to completion time then later list entries', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  const older = { ...pr.statusCheckRollup[0], completedAt: '2026-11-18T01:00:00Z', conclusion: 'FAILURE' };
  const newer = { ...older, completedAt: '2026-11-18T02:00:00Z', conclusion: 'SUCCESS' };
  for (const runs of [[older, newer], [newer, older]]) {
    assert.equal(checkStatus({ ...pr, statusCheckRollup: runs.concat(pr.statusCheckRollup.slice(1)) }).ready, true);
  }
  for (const completedAt of [undefined, '2026-11-18T01:00:00Z']) {
    pr.statusCheckRollup[0].completedAt = completedAt;
    pr.statusCheckRollup[0].conclusion = 'FAILURE';
    pr.statusCheckRollup.push({ ...pr.statusCheckRollup[0], conclusion: 'SUCCESS' });
    assert.equal(checkStatus(pr).ready, true);
    pr.statusCheckRollup.pop();
  }
});

test('same-name checks from different workflows remain independent', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  pr.statusCheckRollup[0].workflowName = 'Android tests';
  pr.statusCheckRollup[0].startedAt = '2026-11-18T02:00:00Z';
  pr.statusCheckRollup.push({ ...pr.statusCheckRollup[0], workflowName: 'Other workflow', startedAt: '2026-11-18T01:00:00Z', conclusion: 'FAILURE' });
  assert.throws(() => checkStatus(pr), /Checks not green: build/);
});

test('later same-name failure without timestamps cannot be hidden', async () => {
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

test('wait stops on a changed head instead of retrying', async () => {
  const { waitForChecks } = await gate;
  let calls = 0;
  await assert.rejects(waitForChecks(() => ++calls === 1 ? original : { ...original, headRefOid: 'a'.repeat(40) }, {
    wait: true, pause: async () => {}, report: () => {},
  }), /head changed/);
  assert.equal(calls, 2);
});

test('wait keeps polling when only the base moves forward', async () => {
  const { waitForChecks } = await gate;
  const pr = await green();
  const pending = {
    ...pr,
    statusCheckRollup: pr.statusCheckRollup.map((check, index) => index === 0
      ? { ...check, status: 'IN_PROGRESS', conclusion: null }
      : check),
  };
  let calls = 0;
  const result = await waitForChecks(() => {
    calls += 1;
    if (calls === 1) return pending;
    if (calls === 2) return { ...pending, baseRefOid: 'b'.repeat(40) };
    return pr;
  }, { wait: true, pause: async () => {}, report: () => {} });
  assert.equal(result, `COMPLETE_CHECKS_GREEN ${pr.headRefOid}`);
  assert.equal(calls, 3);
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

test('only an unexpanded journey matrix may skip, and a journey never fails', async () => {
  const { checkStatus } = await gate;
  for (const name of ['journey ${{ matrix.feature }}', 'parent journey ${{ matrix.feature }}', 'journey ${{ matrix.feature }} / verify']) {
    const pr = await green();
    pr.statusCheckRollup.push({ ...pr.statusCheckRollup[0], name, conclusion: 'SKIPPED' });
    assert.equal(checkStatus(pr).ready, true, name);
    pr.statusCheckRollup.at(-1).conclusion = 'FAILURE';
    assert.throws(() => checkStatus(pr), /Checks not green/, name);
  }
  const pr = await green();
  pr.statusCheckRollup.push({ ...pr.statusCheckRollup[0], name: 'journeys-extra', conclusion: 'SKIPPED' });
  assert.throws(() => checkStatus(pr), /Checks not green/);
});

test('the speed check name matches its workflow job name', async () => {
  const { speedCheckJob } = await gate;
  const workflow = readFileSync(path.join(__dirname, '../.github/workflows/speed-check.yml'), 'utf8');
  const jobName = workflow.split(/^jobs:\s*$/m)[1].match(/^ {4}name: (.+?)[ \t]*$/m)?.[1];
  assert.equal(speedCheckJob, jobName);
});

test('a skipped speed check passes only under its workflow name', async () => {
  const { checkStatus, speedCheckJob } = await gate;
  const skipped = await green();
  skipped.statusCheckRollup.push({ ...skipped.statusCheckRollup[0], name: speedCheckJob, conclusion: 'SKIPPED' });
  assert.equal(checkStatus(skipped).ready, true);
  skipped.statusCheckRollup.at(-1).conclusion = 'FAILURE';
  assert.throws(() => checkStatus(skipped), new RegExp(`Checks not green: ${speedCheckJob}`));
  const stale = await green();
  stale.statusCheckRollup.push({ ...stale.statusCheckRollup[0], name: 'chat list remeasures', conclusion: 'SKIPPED' });
  assert.throws(() => checkStatus(stale), /Checks not green: chat list remeasures/);
});

test('a picked journey that skipped blocks the merge', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  pr.statusCheckRollup.push({ ...pr.statusCheckRollup[0], name: 'journey chat-shared.md / verify', conclusion: 'SKIPPED' });
  assert.throws(() => checkStatus(pr), /Checks not green: journey chat-shared\.md \/ verify/);
});

test('a journey red on main is tolerated when pstack-verifier passed', async () => {
  const { checkStatus, reportToleratedRedOnMain } = await gate;
  const pr = await green();
  const nightly = 'https://github.com/example/actions/runs/1';
  const redOnMain = { journeys: ['session-gestures.md', 'chat-shared.md'], url: nightly };
  pr.statusCheckRollup.push(
    { __typename: 'CheckRun', name: 'journey session-gestures.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' },
    { __typename: 'CheckRun', name: 'journey chat-shared.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' },
  );
  const status = checkStatus(pr, { redOnMain });
  assert.equal(status.ready, true);
  assert.deepEqual(status.toleratedRedOnMain.sort(), ['chat-shared.md', 'session-gestures.md']);
  const lines = [];
  reportToleratedRedOnMain(status, line => lines.push(line));
  assert.match(lines.join('\n'), /Red on main tolerated \(nightly https:\/\/github\.com\/example\/actions\/runs\/1\): chat-shared\.md, session-gestures\.md/);
});

const nightlyRunUrl = 'https://github.com/valentinyeo/multiprompt-android/actions/runs/44980692049';

function nightlyRun(overrides = {}) {
  return {
    id: 44980692049, event: 'schedule', head_branch: 'main', status: 'in_progress',
    created_at: '2026-11-22T03:27:07Z', html_url: nightlyRunUrl, ...overrides,
  };
}

function nightlyFetch(runs, jobs, calls = []) {
  return path => {
    calls.push(path);
    const runId = /^repos\/[^/]+\/[^/]+\/actions\/runs\/(\d+)\/jobs/.exec(path)?.[1];
    if (runId) return { jobs: jobs[runId] ?? [] };
    return { workflow_runs: runs };
  };
}

function nightlyJob(name, conclusion = 'success', status = 'completed') {
  return { name: `nightly ${name} / verify`, status, conclusion };
}

test('the newest nightly is the newest scheduled android-full run on main', async () => {
  const { newestNightlyRun, nightlyWorkflow, repo } = await gate;
  const calls = [];
  const fetch = nightlyFetch([
    nightlyRun({ id: 11, status: 'queued', created_at: '2026-11-23T03:27:00Z', html_url: 'https://example/runs/11' }),
    nightlyRun({ id: 12, status: 'completed', created_at: '2026-11-21T03:27:00Z', html_url: 'https://example/runs/12' }),
    nightlyRun({ id: 13, event: 'workflow_dispatch', status: 'completed', created_at: '2026-11-22T17:00:00Z', html_url: 'https://example/runs/13' }),
    nightlyRun({ id: 14, head_branch: 'topic', status: 'completed', created_at: '2026-11-22T09:00:00Z', html_url: 'https://example/runs/14' }),
    nightlyRun({ id: 15, status: 'completed', created_at: '2026-11-20T03:27:00Z', html_url: 'https://example/runs/15' }),
  ], {}, calls);
  assert.deepEqual(newestNightlyRun(fetch), { id: '12', url: 'https://example/runs/12', reason: '' });
  assert.deepEqual(calls, [`repos/${repo}/actions/workflows/${nightlyWorkflow}/runs?branch=main&per_page=30`]);
  assert.equal(nightlyWorkflow, 'android-full.yml');
});

test('the nightly results are read from that run\'s newest job attempt over REST', async () => {
  const { nightlyRedJourneys, repo } = await gate;
  const calls = [];
  const jobs = [nightlyJob('chat-resync.md', 'failure'), nightlyJob('skill-pills.md'), nightlyJob('reader-version.md', null, 'in_progress'), { name: 'build', status: 'completed', conclusion: 'failure' }];
  assert.deepEqual(nightlyRedJourneys('42', nightlyFetch([], { 42: jobs }, calls)), ['chat-resync.md']);
  assert.deepEqual(calls, [`repos/${repo}/actions/runs/42/jobs?filter=latest&per_page=100&page=1`]);
});

test('journey jobs are read across pages until the last short page', async () => {
  const { nightlyRedJourneys, nightlyJobsPerPage } = await gate;
  const filler = Array.from({ length: nightlyJobsPerPage }, (unused, index) => nightlyJob(`filler-${index}.md`));
  const calls = [];
  const fetch = path => {
    calls.push(path);
    return { jobs: path.endsWith('page=1') ? filler : [nightlyJob('chat-clear.md', 'failure')] };
  };
  assert.deepEqual(nightlyRedJourneys('9', fetch), ['chat-clear.md']);
  assert.equal(calls.length, 2);
});

test('a journey red on the newest nightly is tolerated and one green there still blocks', async () => {
  const { checkStatus, loadRedOnMainJourneys } = await gate;
  const jobs = [
    nightlyJob('chat-resync.md', 'failure'),
    nightlyJob('assertions', 'timed_out'),
    nightlyJob('snippets.md', 'startup_failure'),
    nightlyJob('skill-pills.md'),
    nightlyJob('reader-version.md', null, 'in_progress'),
    nightlyJob('chat-clear.md', 'cancelled'),
    nightlyJob('chat-codex.md', 'skipped'),
  ];
  const redOnMain = loadRedOnMainJourneys({ fetch: nightlyFetch([nightlyRun()], { 44980692049: jobs }), report: () => {} });
  assert.deepEqual(redOnMain, {
    journeys: ['assertions', 'chat-resync.md', 'snippets.md'], url: nightlyRunUrl, note: '',
  });
  const tolerated = ['chat-resync.md', 'assertions', 'snippets.md'];
  const blocking = ['skill-pills.md', 'reader-version.md', 'chat-clear.md', 'chat-codex.md'];
  const pr = await green();
  pr.statusCheckRollup.push(...tolerated.concat(blocking).map(name => ({ __typename: 'CheckRun', name: `journey ${name} / verify`, status: 'COMPLETED', conclusion: 'FAILURE' })));
  assert.throws(() => checkStatus(pr, { redOnMain }), /Checks not green/);
  const toleratedOnly = await green();
  toleratedOnly.statusCheckRollup.push(...tolerated.map(name => ({ __typename: 'CheckRun', name: `journey ${name} / verify`, status: 'COMPLETED', conclusion: 'FAILURE' })));
  const status = checkStatus(toleratedOnly, { redOnMain });
  assert.deepEqual(status.toleratedRedOnMain.sort(), [...tolerated].sort());
  assert.equal(status.ready, true);
  for (const name of blocking) {
    const single = await green();
    single.statusCheckRollup.push({ __typename: 'CheckRun', name: `journey ${name} / verify`, status: 'COMPLETED', conclusion: 'FAILURE' });
    assert.throws(() => checkStatus(single, { redOnMain }), new RegExp(`Checks not green: journey ${name.replace(/\./g, '\\.')}`));
  }
});

test('a required check that is not a journey is never tolerated by the nightly', async () => {
  const { checkStatus, loadRedOnMainJourneys } = await gate;
  const redOnMain = loadRedOnMainJourneys({
    fetch: nightlyFetch([nightlyRun()], { 44980692049: [{ name: 'build', status: 'completed', conclusion: 'failure' }, { name: 'test', status: 'completed', conclusion: 'failure' }] }),
    report: () => {},
  });
  assert.deepEqual(redOnMain.journeys, []);
  const pr = await green();
  pr.statusCheckRollup[0].conclusion = 'FAILURE';
  assert.throws(() => checkStatus(pr, { redOnMain }), /Checks not green: build/);
  const channel = await green();
  channel.statusCheckRollup.push({ __typename: 'CheckRun', name: 'nightly build / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
  assert.throws(() => checkStatus(channel, { redOnMain }), /Checks not green/);
});

test('an unreadable or absent nightly tolerates nothing', async () => {
  const { loadRedOnMainJourneys } = await gate;
  const notes = [];
  assert.deepEqual(loadRedOnMainJourneys({ fetch: nightlyFetch([], {}), report: line => notes.push(line) }), {
    journeys: [], url: '', note: 'no android-full nightly run on main has started yet',
  });
  const onlyDispatch = loadRedOnMainJourneys({ fetch: nightlyFetch([nightlyRun({ event: 'workflow_dispatch' })], {}), report: () => {} });
  assert.deepEqual(onlyDispatch.journeys, []);
  const broken = loadRedOnMainJourneys({ fetch: () => { throw new Error('gh is not logged in'); }, report: line => notes.push(line) });
  assert.deepEqual(broken.journeys, []);
  assert.match(notes.join('\n'), /No journey counts as red on main/);
  const jobsFail = loadRedOnMainJourneys({
    fetch: nightlyFetch([nightlyRun()], {}), report: () => {},
  });
  assert.deepEqual(jobsFail.journeys, []);
  const jobsError = loadRedOnMainJourneys({
    fetch: path => (path.includes('/jobs')
      ? (() => { throw new Error('HTTP 502'); })()
      : { workflow_runs: [nightlyRun()] }),
    report: () => {},
  });
  assert.deepEqual(jobsError.journeys, []);
  assert.match(jobsError.note, /HTTP 502/);
});

test('nightly reads ask gh 2.45 for REST endpoints only', async () => {
  const { ghApiRequest, repo } = await gate;
  const calls = [];
  const run = (command, args, options) => {
    calls.push({ command, args, options });
    return JSON.stringify({ total_count: 0 });
  };
  assert.deepEqual(ghApiRequest(`repos/${repo}/actions/runs/42/jobs?filter=latest&per_page=100&page=1`, run), { total_count: 0 });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].command, 'gh');
  assert.deepEqual(calls[0].args, ['api', `repos/${repo}/actions/runs/42/jobs?filter=latest&per_page=100&page=1`]);
  assert.equal(calls[0].options.encoding, 'utf8');
});

test('a failed journey still blocks when it is not red on main', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey chat-table.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
  assert.throws(
    () => checkStatus(pr, { redOnMain: { journeys: ['session-gestures.md'], url: 'https://github.com/example/actions/runs/1' } }),
    /Checks not green: journey chat-table\.md \/ verify/,
  );
});

test('tolerated red-on-main journeys are reported even when another journey still blocks', async () => {
  const { checkStatus, reportToleratedRedOnMain } = await gate;
  const pr = await green();
  const redOnMain = { journeys: ['session-gestures.md'], url: 'https://github.com/example/actions/runs/1' };
  pr.statusCheckRollup.push(
    { __typename: 'CheckRun', name: 'journey session-gestures.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' },
    { __typename: 'CheckRun', name: 'journey chat-table.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' },
  );
  let thrown;
  try {
    checkStatus(pr, { redOnMain });
  } catch (error) {
    thrown = error;
  }
  assert.ok(thrown);
  assert.deepEqual(thrown.toleratedRedOnMain, ['session-gestures.md']);
  const lines = [];
  reportToleratedRedOnMain(thrown, line => lines.push(line));
  assert.match(lines.join('\n'), /session-gestures\.md/);
});

test('a red-on-main journey still blocks when pstack-verifier did not pass', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
  verifier.conclusion = 'FAILURE';
  pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-gestures.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
  assert.throws(
    () => checkStatus(pr, { redOnMain: { journeys: ['session-gestures.md'], url: 'https://github.com/example/actions/runs/1' } }),
    /Checks not green: (?:journey session-gestures\.md \/ verify|pstack-verifier)/,
  );
});

test('a red-on-main journey failure waits while pstack-verifier is pending', async () => {
  const { checkStatus } = await gate;
  const pr = await green();
  const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
  verifier.status = 'IN_PROGRESS';
  verifier.conclusion = null;
  pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
  const status = checkStatus(pr, { redOnMain: { journeys: ['session-drafts.md'], url: 'https://github.com/example/actions/runs/1' } });
  assert.equal(status.ready, false);
  assert.match(status.message, /pending/);
});

test('wait keeps polling when a red-on-main journey fails before pstack-verifier finishes', async () => {
  const { waitForChecks } = await gate;
  const redOnMain = { journeys: ['session-drafts.md'], url: 'https://github.com/example/actions/runs/1' };
  let calls = 0;
  const pauses = [];
  const message = await waitForChecks(async () => {
    calls++;
    const pr = await green();
    if (calls === 1) {
      const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
      verifier.status = 'IN_PROGRESS';
      verifier.conclusion = null;
      pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
      return pr;
    }
    pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
    return pr;
  }, {
    wait: true,
    now: () => calls * 300_000,
    pause: async ms => { pauses.push(ms); },
    report: () => {},
    loadRedOnMain: () => redOnMain,
  });
  assert.match(message, /^COMPLETE_CHECKS_GREEN /);
  assert.equal(calls, 2);
  assert.deepEqual(pauses, [300_000]);
});

test('wait stops immediately when a non-red journey fails while pstack-verifier is pending', async () => {
  const { waitForChecks } = await gate;
  const pr = await green();
  const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
  verifier.status = 'IN_PROGRESS';
  verifier.conclusion = null;
  pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey chat-table.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
  let pauses = 0;
  await assert.rejects(waitForChecks(() => pr, {
    wait: true,
    pause: async () => { pauses++; },
    report: () => {},
    loadRedOnMain: () => ({ journeys: ['session-drafts.md'], url: 'https://github.com/example/actions/runs/1' }),
  }), /Checks not green: journey chat-table\.md \/ verify/);
  assert.equal(pauses, 0);
});

test('wait accepts tolerated red-on-main journeys after pstack-verifier passes', async () => {
  const { waitForChecks, reportToleratedRedOnMain } = await gate;
  const redOnMain = { journeys: ['session-drafts.md'], url: 'https://github.com/example/actions/runs/1' };
  let step = 0;
  const reports = [];
  const message = await waitForChecks(async () => {
    const pr = await green();
    if (step === 0) {
      step++;
      const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
      verifier.status = 'IN_PROGRESS';
      verifier.conclusion = null;
      pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
      return pr;
    }
    pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
    return pr;
  }, {
    wait: true,
    now: () => step * 300_000,
    pause: async () => { step++; },
    report: line => reports.push(line),
    loadRedOnMain: () => redOnMain,
  });
  assert.match(message, /^COMPLETE_CHECKS_GREEN /);
  const status = { toleratedRedOnMain: ['session-drafts.md'], redOnMainUrl: redOnMain.url };
  const lines = [];
  reportToleratedRedOnMain(status, line => lines.push(line));
  assert.match(lines.join('\n'), /session-drafts\.md/);
});

test('wait fails when pstack-verifier fails after a red-on-main journey already failed', async () => {
  const { waitForChecks } = await gate;
  const redOnMain = { journeys: ['session-drafts.md'], url: 'https://github.com/example/actions/runs/1' };
  let step = 0;
  await assert.rejects(waitForChecks(async () => {
    const pr = await green();
    if (step === 0) {
      step++;
      const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
      verifier.status = 'IN_PROGRESS';
      verifier.conclusion = null;
      pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
      return pr;
    }
    const verifier = pr.statusCheckRollup.find(check => check.name === 'pstack-verifier');
    verifier.conclusion = 'FAILURE';
    pr.statusCheckRollup.push({ __typename: 'CheckRun', name: 'journey session-drafts.md / verify', status: 'COMPLETED', conclusion: 'FAILURE' });
    return pr;
  }, {
    wait: true,
    now: () => step * 300_000,
    pause: async () => { step++; },
    report: () => {},
    loadRedOnMain: () => redOnMain,
  }), /Checks not green/);
});

const gh245PrViewFields = new Set(`additions assignees author autoMergeRequest baseRefName body changedFiles closed
closedAt comments commits createdAt deletions files headRefName headRefOid headRepository headRepositoryOwner id
isCrossRepository isDraft labels latestReviews maintainerCanModify mergeCommit mergeStateStatus mergeable mergedAt
mergedBy milestone number potentialMergeCommit projectCards projectItems reactionGroups reviewDecision reviewRequests
reviews state statusCheckRollup title updatedAt url`.split(/\s+/));

test('pr view asks only for fields gh 2.45 knows', async () => {
  const { prFields } = await gate;
  for (const field of prFields) assert.ok(gh245PrViewFields.has(field), `${field} is not a gh 2.45 pr view field`);
  assert.ok(!prFields.includes('baseRefOid'));
  assert.ok(!prFields.join(',').includes('baseRef'));
});

test('the base sha and mergeable_state come from the REST pull request, not pr view', async () => {
  const { inspectPr } = await gate;
  const head = 'a'.repeat(40);
  const base = 'b'.repeat(40);
  const calls = [];
  const run = (command, args) => {
    calls.push([command, ...args]);
    if (args[0] === 'pr') return JSON.stringify({ state: 'OPEN', headRefOid: head, mergeable: 'MERGEABLE', statusCheckRollup: [] });
    return JSON.stringify({ base: { sha: base }, mergeable_state: 'behind' });
  };
  assert.deepEqual(inspectPr('3026', run), {
    state: 'OPEN', headRefOid: head, mergeable: 'MERGEABLE', statusCheckRollup: [], baseRefOid: base,
    mergeableState: 'behind', number: 3026,
  });
  assert.deepEqual(calls, [
    ['gh', 'pr', 'view', '3026', '--repo', 'valentinyeo/multiprompt-android', '--json', 'state,headRefOid,mergeable,statusCheckRollup'],
    ['gh', 'api', 'repos/valentinyeo/multiprompt-android/pulls/3026'],
  ]);
});

test('green checks with mergeable_state behind replay the lost merge turn', async () => {
  const { checkStatus, waitForChecks, behindMessage } = await gate;
  const pr = await green();
  pr.mergeableState = 'behind';
  pr.number = 3188;
  const status = checkStatus(pr);
  assert.equal(status.ready, false);
  assert.equal(status.message, behindMessage(3188));
  let step = 0;
  const message = await waitForChecks(async () => {
    const next = await green();
    next.number = 3188;
    next.mergeableState = step++ === 0 ? 'behind' : 'clean';
    return next;
  }, { wait: true, pause: async () => {}, report: () => {} });
  assert.equal(message, `COMPLETE_CHECKS_GREEN ${pr.headRefOid}`);
  assert.equal(step, 2);
});

test('an unresolvable base sha never passes the wait', async () => {
  const { inspectPr, checkStatus } = await gate;
  const run = (command, args) => (args[0] === 'pr'
    ? JSON.stringify({ state: 'OPEN', headRefOid: 'a'.repeat(40), mergeable: 'MERGEABLE', statusCheckRollup: [] })
    : JSON.stringify({ base: { sha: '' }, mergeable_state: 'unknown' }));
  assert.throws(() => checkStatus(inspectPr('3026', run)), /Missing exact PR revisions/);
});
