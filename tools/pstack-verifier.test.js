const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const verifier = require('../.github/scripts/pstack-verifier/verifier.cjs');

const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const sha = 'a'.repeat(40);
const runUrl = 'https://github.com/example/repo/actions/runs/1';

function jobBlock(source, name) {
  const lines = source.split('\n');
  const start = lines.findIndex(line => line === `  ${name}:`);
  assert.ok(start >= 0, `job ${name} exists`);
  const end = lines.findIndex((line, index) => index > start && /^ {2}\S/.test(line));
  return lines.slice(start, end < 0 ? undefined : end).join('\n');
}

function topBlock(source, name) {
  const lines = source.split('\n');
  const start = lines.findIndex(line => line === `${name}:`);
  assert.ok(start >= 0, `${name} exists`);
  const end = lines.findIndex((line, index) => index > start && /^\S/.test(line));
  return lines.slice(start, end < 0 ? undefined : end);
}

function dispatchFeatureOptions(source) {
  const lines = topBlock(source, 'on');
  const dispatch = lines.findIndex(line => line === '  workflow_dispatch:');
  const feature = lines.findIndex((line, index) => index > dispatch && line === '      feature:');
  const options = lines.findIndex((line, index) => index > feature && line === '        options:');
  const names = [];
  for (const line of lines.slice(options + 1)) {
    const item = line.match(/^ {10}- (\S+)$/);
    if (!item) break;
    names.push(item[1]);
  }
  return names;
}

function reply(status, body) {
  return { status, ok: status >= 200 && status < 300, json: async () => body };
}

const answer = content => ({ choices: [{ message: { content }, finish_reason: 'stop' }] });

test('a journey that failed forces FAIL even when the model says PASS', () => {
  const ruled = verifier.applyRules({
    modelVerdict: 'PASS',
    features: ['hosts.md', 'terminal.md'],
    conclusions: { 'hosts.md': 'success', 'terminal.md': 'failure' },
  });
  assert.equal(ruled.verdict, 'FAIL');
  assert.match(ruled.notes.join(' '), /terminal\.md \(failure\)/);
  assert.equal(ruled.journeys.find(journey => journey.feature === 'hosts.md').passed, true);
});

test('a cancelled journey or one with no result forces FAIL', () => {
  for (const conclusions of [{ 'hosts.md': 'cancelled' }, { 'hosts.md': 'skipped' }, {}]) {
    const ruled = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions });
    assert.equal(ruled.verdict, 'FAIL', JSON.stringify(conclusions));
  }
  assert.match(verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: {} }).notes.join(' '), /no result/);
});

test('a passing set of journeys stays PASS', () => {
  const ruled = verifier.applyRules({ features: ['hosts.md'], conclusions: { 'hosts.md': 'success' } });
  assert.equal(ruled.verdict, 'PASS');
});

test('a journey that failed still fails the job', () => {
  const ruled = verifier.applyRules({ features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' } });
  assert.equal(ruled.verdict, 'FAIL');
});

test('a journey already red on main becomes a note, not a failed check', () => {
  const red = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' }, redOnMain: ['hosts.md'], redOnMainUrl: runUrl });
  assert.equal(red.verdict, 'PASS+NOTES');
  assert.equal(red.journeys[0].redOnMain, true);
  assert.ok(red.notes.join(' ').includes(`Already red on main (nightly ${runUrl}): hosts.md; tracked as a ticket, not a PR block.`), red.notes.join(' '));
  assert.doesNotMatch(red.notes.join(' '), /Journey not passed/);
});

test('a journey that failed and is not red on main still fails the check', () => {
  const ruled = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md', 'terminal.md'], conclusions: { 'hosts.md': 'failure', 'terminal.md': 'failure' }, redOnMain: ['terminal.md'], redOnMainUrl: runUrl });
  assert.equal(ruled.verdict, 'FAIL');
  assert.match(ruled.notes.join(' '), /Journey not passed: hosts\.md \(failure\)\./);
  assert.match(ruled.notes.join(' '), /Already red on main .*terminal\.md; tracked as a ticket/);
  const clean = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'success' }, redOnMain: ['terminal.md'], redOnMainUrl: runUrl });
  assert.equal(clean.verdict, 'PASS');
  assert.deepEqual(clean.notes, []);
});

test('an empty red list and a missing one behave as before', () => {
  for (const redOnMain of [[], undefined]) {
    assert.equal(verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' }, redOnMain }).verdict, 'FAIL');
    assert.equal(verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'success' }, redOnMain }).verdict, 'PASS');
  }
  const noted = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'success' }, redOnMainNote: 'No journey counts as red on main: no completed android-full run on main yet.' });
  assert.match(noted.notes.join(' '), /No journey counts as red on main/);
});

test('a journey this PR changes blocks even while it is red on main', () => {
  const ruled = verifier.applyRules({
    modelVerdict: 'PASS', features: ['session-pins.md'], conclusions: { 'session-pins.md': 'failure' },
    redOnMain: ['session-pins.md'], redOnMainUrl: runUrl, direct: ['session-pins.md'],
  });
  assert.equal(ruled.verdict, 'FAIL');
  assert.equal(ruled.journeys[0].direct, true);
  assert.match(ruled.notes.join(' '), /Journey not passed: session-pins\.md \(failure\)\./);
  assert.match(ruled.notes.join(' '), /This PR changes session-pins\.md, so being red on main \(nightly \S+\) does not exempt it/);
  assert.doesNotMatch(ruled.notes.join(' '), /tracked as a ticket, not a PR block/);
});

test('a red-on-main journey the PR does not change stays a note', () => {
  const ruled = verifier.applyRules({
    modelVerdict: 'PASS', features: ['session-pins.md'], conclusions: { 'session-pins.md': 'failure' },
    redOnMain: ['session-pins.md'], redOnMainUrl: runUrl, direct: ['terminal.md'],
  });
  assert.equal(ruled.verdict, 'PASS+NOTES');
  assert.equal(ruled.journeys[0].direct, false);
  assert.match(ruled.notes.join(' '), /Already red on main .*session-pins\.md; tracked as a ticket, not a PR block\./);
  assert.equal(verifier.applyRules({ features: ['session-pins.md'], conclusions: { 'session-pins.md': 'failure' }, redOnMain: ['session-pins.md'], direct: [] }).verdict, 'PASS+NOTES');
});

test('only journeys that did not pass are written for the verify chat', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'red-journeys-'));
  try {
    const file = path.join(directory, 'red-journeys.json');
    const jobs = [
      { name: 'nightly hosts.md', status: 'completed', conclusion: 'success' },
      { name: 'nightly terminal.md / verify', status: 'completed', conclusion: 'failure' },
      { name: 'nightly sessions.md / verify', status: 'in_progress', conclusion: null },
      { name: 'journey chat-queued.md', status: 'completed', conclusion: 'success' },
    ];
    assert.deepEqual(verifier.redJourneys({ 'hosts.md': 'success', 'terminal.md': 'failure', 'sessions.md': null, 'chat-queued.md': 'cancelled' }), ['chat-queued.md', 'terminal.md']);
    assert.deepEqual(verifier.writeRedJourneys(file, jobs), ['sessions.md', 'terminal.md']);
    assert.deepEqual(JSON.parse(fs.readFileSync(file, 'utf8')), ['sessions.md', 'terminal.md']);
    const workflow = jobBlock(read('.github/workflows/android-full.yml'), 'red-journeys');
    assert.match(workflow, /needs: nightly-journeys/);
    assert.match(workflow, /if: always\(\)/);
    assert.match(workflow, /run: node \.github\/scripts\/pstack-verifier\/verifier\.cjs red-journeys/);
    assert.match(workflow, /name: red-journeys/);
    assert.match(workflow, /retention-days: 7/);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

const redOnMainRun = url => ['run', 'list', '-w', 'android-full.yml', '-b', 'main', '-s', 'completed', '-L', '1', '--json', 'databaseId,url'];

test('the latest completed nightly run on main is read, and a missing artifact counts as no red journeys', () => {
  const asked = [];
  const run = args => {
    asked.push(args);
    if (args[0] === 'run' && args[1] === 'list') return JSON.stringify([{ databaseId: '42', url: runUrl }]);
    if (args[0] === 'run' && args[1] === 'download') return '';
    throw new Error(`unexpected ${args.join(' ')}`);
  };
  assert.deepEqual(verifier.latestNightlyRun('example/repo', run), { id: '42', url: runUrl, reason: '' });
  assert.deepEqual(asked[0], redOnMainRun(runUrl));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'red-on-main-'));
  try {
    fs.writeFileSync(path.join(directory, 'red-journeys.json'), JSON.stringify(['terminal.md']));
    assert.deepEqual(verifier.downloadRedOnMain('example/repo', directory, run), { journeys: ['terminal.md'], url: runUrl, note: '' });
    assert.deepEqual(asked.find(args => args[1] === 'download'), ['run', 'download', '42', '-R', 'example/repo', '-n', 'red-journeys', '-D', directory]);
    fs.rmSync(path.join(directory, 'red-journeys.json'));
    const missing = verifier.downloadRedOnMain('example/repo', directory, run);
    assert.deepEqual(missing.journeys, []);
    assert.match(missing.note, /no readable red-journeys artifact/);
    const none = verifier.downloadRedOnMain('example/repo', directory, args => (args[1] === 'list' ? '[]' : ''));
    assert.deepEqual(none.journeys, []);
    assert.match(none.note, /no completed android-full run on main yet/);
    const broken = verifier.downloadRedOnMain('example/repo', directory, () => { throw new Error('gh is not logged in'); });
    assert.deepEqual(broken.journeys, []);
    assert.match(broken.note, /the nightly run list failed/);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('driving nothing caps the verdict at PASS+NOTES and says why', () => {
  const ruled = verifier.applyRules({ modelVerdict: 'PASS', features: [], conclusions: {}, notDrivenReason: 'Only documentation changed.' });
  assert.equal(ruled.verdict, 'PASS+NOTES');
  assert.match(ruled.notes.join(' '), /Nothing was driven/);
  assert.match(ruled.notes.join(' '), /Only documentation changed\./);
  assert.equal(verifier.applyRules({ modelVerdict: 'PASS+NOTES', features: [], conclusions: {} }).verdict, 'PASS+NOTES');
  assert.equal(verifier.applyRules({ modelVerdict: 'FAIL', features: [], conclusions: {} }).verdict, 'PASS+NOTES');
});

test('picker and ladder response parsing reject bad JSON', () => {
  assert.throws(() => verifier.parsePick('not json'));
  assert.throws(() => verifier.parsePick('{"reason":"x"}'));
  assert.throws(() => verifier.parseLadderResponse(JSON.stringify({ choices: [{ message: {} }] })), /no content/);
  assert.throws(() => verifier.parseLadderResponse(JSON.stringify({ choices: [{ message: { content: '' }, finish_reason: 'stop' }] })), /no content/);
  assert.throws(() => verifier.parseLadderResponse(JSON.stringify({ choices: [{ message: { content: '{' }, finish_reason: 'length' }] })), /cut the answer off/);
});

test('the picker request is a JSON chat body without a model field', () => {
  const request = verifier.buildPickRequest({ system: 'pick', user: 'context' });
  assert.deepEqual(request.messages, [{ role: 'system', content: 'pick' }, { role: 'user', content: 'context' }]);
  assert.deepEqual(request.response_format, { type: 'json_object' });
  assert.equal(request.max_tokens, 32000);
  assert.equal(request.model, undefined);
  const content = verifier.parseLadderResponse(JSON.stringify({ choices: [{ message: { content: '{"ok":true}' }, finish_reason: 'stop' }] }));
  assert.equal(content, '{"ok":true}');
});

test('picker outcome typos get one correction and genuine unmapped behavior still blocks', async () => {
  const pick = changed_outcomes => JSON.stringify({ features: ['host-restart.md'], changed_outcomes, change_kind: 'bug', reason: 'Follow the live native Claude transcript after restart.' });
  const answers = [pick(['host-restart.md']), pick(['reader.host-restart'])];
  const failures = [];
  let asked = 0;
  const parsed = await verifier.askParsed({
    ask: async error => { if (error) failures.push(error.message); return answers[asked++]; },
    parse: verifier.parsePick,
  });
  assert.equal(asked, 2);
  assert.deepEqual(parsed.changedOutcomes, ['reader.host-restart']);
  assert.match(failures[0], /host-restart\.md/);
  assert.match(failures[0], /Follow the live native Claude transcript/);
  asked = 0;
  await assert.rejects(verifier.askParsed({
    ask: async () => { asked += 1; return pick(['unmapped']); }, parse: verifier.parsePick,
  }), /FEATURE_UNMAPPED.*unmapped.*Follow the live native Claude transcript/);
  assert.equal(asked, 1);
  asked = 0;
  await assert.rejects(verifier.askParsed({
    ask: async () => { asked += 1; return pick(['invented.outcome']); }, parse: verifier.parsePick,
  }), /FEATURE_UNMAPPED.*invented\.outcome/);
  assert.equal(asked, 2);
});

test('code-mapped outcomes decide, so an unmapped answer only blocks a genuinely unmapped product change', () => {
  const pick = outcomes => JSON.stringify({ features: [], changed_outcomes: outcomes, change_kind: 'bug', reason: 'Shared chat reader change.' });
  const mapped = ['codex.transcript', 'reader.host-restart'];
  const mixed = verifier.parsePick(pick(['reader.host-restart', 'unmapped']), { mapped, productSource: true });
  assert.deepEqual(mixed.changedOutcomes, ['reader.host-restart', 'codex.transcript']);
  assert.equal(mixed.unmapped, true);
  assert.deepEqual(verifier.parsePick(pick(['unmapped']), { mapped, productSource: true }).changedOutcomes, mapped);
  assert.deepEqual(verifier.parsePick(pick(['unmapped']), { mapped: ['reader.host-restart'], productSource: true }).changedOutcomes, ['reader.host-restart']);
  assert.deepEqual(verifier.parsePick(pick(['unmapped']), { mapped: [], productSource: false }).changedOutcomes, []);
  assert.throws(() => verifier.parsePick(pick(['unmapped']), { mapped: [], productSource: true }), /FEATURE_UNMAPPED/);
  assert.throws(() => verifier.parsePick(pick(['unmapped'])), /FEATURE_UNMAPPED/);
  assert.throws(() => verifier.parsePick(JSON.stringify({ features: [], changed_outcomes: ['unmapped', 'invented.outcome'], change_kind: 'product' }), { mapped: [], productSource: true }), /invented\.outcome/);
});

test('an exhausted AI picker falls back to the feature map instead of failing the check', async () => {
  const codePlan = { outcomes: ['codex.transcript', 'reader.host-restart'], product: true };
  const failures = [
    () => { throw new Error('ladder-call failed'); },
    async () => { throw new Error('ladder-call failed'); },
    async () => { throw new Error('ladder-call returned no content'); },
    async () => 'not json',
  ];
  for (const ask of failures) {
    const logged = [];
    const picked = await verifier.pickWithFallback({ ask, codePlan, log: message => logged.push(message) });
    assert.equal(logged.length, 1);
    assert.match(logged[0], /^pstack-verifier pick: AI picker unavailable \(.+\); using the feature map\.$/);
    assert.deepEqual(picked.features, []);
    assert.deepEqual(picked.dropped, []);
    assert.deepEqual(picked.changedOutcomes, codePlan.outcomes);
    assert.equal(picked.changeKind, 'product');
    assert.equal(picked.smoke, false);
    assert.match(picked.reason, /AI picker unavailable; journeys chosen from the feature map\./);
  }
  const tooling = await verifier.pickWithFallback({ ask: failures[0], codePlan: { outcomes: [], product: false }, log: () => {} });
  assert.equal(tooling.changeKind, 'tooling');
});

test('a working AI picker still decides, and a genuine unmapped product change still blocks', async () => {
  const codePlan = { outcomes: ['reader.host-restart'], product: true };
  const answer = JSON.stringify({ features: ['host-restart.md'], changed_outcomes: ['reader.host-restart'], change_kind: 'bug', reason: 'Shared chat reader change.' });
  const picked = await verifier.pickWithFallback({ ask: async () => answer, codePlan, log: () => {} });
  assert.deepEqual(picked.features, ['host-restart.md']);
  assert.equal(picked.changeKind, 'bug');
  assert.deepEqual(picked.changedOutcomes, ['reader.host-restart']);
  assert.equal(picked.reason, 'Shared chat reader change.');
  const unmapped = JSON.stringify({ features: [], changed_outcomes: ['unmapped'], change_kind: 'product', reason: 'No registered outcome covers BrandNewThing.' });
  await assert.rejects(verifier.pickWithFallback({ ask: async () => unmapped, codePlan: { outcomes: [], product: true }, log: () => {} }), /FEATURE_UNMAPPED/);
});

test('the picker can classify a bug fix, and only fix or bug words count', () => {
  const picked = verifier.parsePick(JSON.stringify({ features: ['hosts.md'], changed_outcomes: [], change_kind: 'bug', reason: 'Send drops the draft.' }));
  assert.equal(picked.changeKind, 'bug');
  assert.throws(() => verifier.parsePick(JSON.stringify({ features: ['hosts.md'], changed_outcomes: [], change_kind: 'feature', reason: 'x' })));
  const tickets = { 'IGSH-9': 'Checks and fixes, no visible change', 'IGSH-2': 'Add inbox folders' };
  assert.equal(verifier.isBugFix({ title: 'Fix the crash' }), true);
  assert.equal(verifier.isBugFix({ title: 'IGSH-9: tidy the checks', tickets }), true);
  assert.equal(verifier.isBugFix({ title: 'IGSH-2: tidy', body: 'See IGSH-9.', tickets }), true);
  assert.equal(verifier.isBugFix({ title: 'IGSH-2: Add folders', changeKind: 'product', tickets }), false);
  for (const title of ['Update the fixture', 'Debug logging', 'A prefix change', 'Leave it unfixed']) {
    assert.equal(verifier.saysBug(title), false, title);
  }
  assert.equal(verifier.loadTicketTitles()['IGSH-2568'], 'Feature freeze until each repo\'s verification is complete');
  assert.equal(verifier.saysBug(verifier.loadTicketTitles()['IGSH-2568']), false);
});

test('unknown journey names are dropped and every named journey runs', () => {
  const picked = verifier.parsePick(JSON.stringify({
    features: ['hosts.md', 'made-up.md', 'session-settings.md', '../etc/passwd', 'hosts.md', 'terminal.md', 'sessions.md', 'chat-cancel.md'],
    reason: 'x',
    changed_outcomes: [],
    change_kind: 'tooling',
  }));
  assert.deepEqual(picked.features, ['hosts.md', 'terminal.md', 'sessions.md', 'chat-cancel.md']);
  assert.ok(picked.dropped.includes('made-up.md'));
  assert.ok(picked.dropped.includes('session-settings.md'));
  assert.deepEqual(verifier.validateFeatures('hosts.md').features, []);
  assert.deepEqual(verifier.validateFeatures(null).features, []);
});

test('every picked journey runs on head, and no parent matrix exists', () => {
  const picked = verifier.parsePick(JSON.stringify({
    features: verifier.ALLOWLIST,
    changed_outcomes: [],
    change_kind: 'tooling',
    reason: 'Exercise every journey.',
  }));
  assert.equal(picked.features.length, verifier.ALLOWLIST.length);
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.doesNotMatch(workflow, /^ {2}parents:$/m);
  const block = jobBlock(workflow, 'journeys');
  assert.match(block, /^ {4}needs: pick$/m);
  assert.ok(block.includes('        feature: ${{ fromJSON(needs.pick.outputs.features) }}'));
  assert.ok(block.includes('      feature: ${{ matrix.feature }}'));
  assert.ok(block.includes('      side: head'));
  assert.ok(block.includes('      app_ref: ${{ needs.pick.outputs.head_sha }}'));
  assert.doesNotMatch(block, /^ {8}(?:include|side):/m);
});

test('required outcome journeys cannot be omitted by the model picker', () => {
  const picked = { features: ['chat-shared.md'], dropped: [], smoke: false, reason: 'chat recovery' };
  const selected = verifier.includeRequiredJourneys(picked, { outcomes: ['reader.send-enter-interrupt', 'chat.link-recovery'] });
  assert.deepEqual(selected.features, ['session-reader.md', 'chat-shared.md']);
  assert.equal(selected.smoke, false);
  const every = verifier.includeRequiredJourneys(picked, { outcomes: ['reader.send-enter-interrupt', 'chat.link-recovery', 'inbox.search', 'terminal.journey'] });
  assert.deepEqual(every.features, ['session-reader.md', 'chat-shared.md', 'sessions.md', 'terminal.md']);
});

test('changed recipes and owned drivers are mandatory before model suggestions', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const files = ['session-pins.md', 'session-gestures.md'].map(name => ({ filename: `${gate.REGISTRY_PATH}/${name}` }));
  files.push({ filename: 'tools/session-gestures/drive.py' });
  const required = gate.changedJourneys('', files);
  assert.deepEqual(required, ['session-gestures.md', 'session-pins.md', 'session-summaries.md']);
  const picked = { features: ['native-skills.md'], dropped: [], smoke: false };
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { outcomes: [] }, '', required).features, required);
  assert.deepEqual(gate.changedJourneys('', [{ filename: 'docs/moved.md', previous_filename: '.claude/skills/verify-multiprompt-android/scripts/pins-drive.sh' }]), ['session-pins.md']);
  assert.equal(verifier.noProductChange([{ filename: 'tools/session-gestures/drive.py' }], 1), false);
  assert.equal(verifier.noProductChange([{ filename: 'tools/session-gestures/scroll.test.py' }], 1), true);
});

test('a harness-only change runs the fixed smoke set instead of every journey', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const changed = [
    { filename: 'tools/journey-actions/action.test.py' },
    { filename: 'tools/journey-actions/fixtures/chat-shared-verify-session-text-visible.xml' },
    { filename: '.claude/skills/verify-multiprompt-android/scripts/journey_action.py' },
  ];
  assert.deepEqual(gate.changedJourneys('', changed), gate.HARNESS_SMOKE_JOURNEYS);
  assert.ok(!gate.changedJourneys('', changed).includes('chat-shared.md'));
  const diff = changed.map(file => `diff --git a/${file.filename} b/${file.filename}\n+changed`).join('\n');
  assert.deepEqual(gate.changedJourneys(diff), gate.HARNESS_SMOKE_JOURNEYS);
  assert.deepEqual(verifier.includeRequiredJourneys({ features: [], dropped: [] }, { outcomes: [] }, diff).features, gate.HARNESS_SMOKE_JOURNEYS);
  assert.equal(verifier.noProductChange([{ filename: 'tools/journey-actions/action.test.py' }], 1), false);
  assert.deepEqual(gate.changedJourneys('', [{ filename: '.claude/skills/verify-multiprompt-android/scripts/pins-drive.sh' }]), ['session-pins.md']);
  assert.equal(gate.MAX_PR_JOURNEYS, gate.PR_CHECK_CAP - gate.PR_NON_JOURNEY_CHECKS);
  assert.equal(gate.PR_NON_JOURNEY_CHECKS, 10);
});

test('the journey cap keeps direct feature checks ahead of harness smoke', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const recipes = gate.prJourneys.slice(0, gate.MAX_PR_JOURNEYS + 1);
  const diff = [
    'diff --git a/.claude/skills/verify-multiprompt-android/scripts/drive.sh b/.claude/skills/verify-multiprompt-android/scripts/drive.sh',
    ...recipes.map(name => `diff --git a/${gate.REGISTRY_PATH}/${name} b/${gate.REGISTRY_PATH}/${name}`),
  ].join('\nchanged\n') + '\n+changed';
  const selected = verifier.includeRequiredJourneys({ features: [], dropped: [] }, { outcomes: [] }, diff);
  assert.equal(selected.features.length, gate.MAX_PR_JOURNEYS);
  for (const name of recipes.slice(0, gate.MAX_PR_JOURNEYS)) assert.ok(selected.features.includes(name), name);
  assert.ok(selected.dropped.length >= 1);
});

test('a shared-driver change runs every journey it touches instead of deferring guards', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const files = ['.claude/skills/verify-multiprompt-android/scripts/drive.sh', `${gate.REGISTRY_PATH}/chat-shared.md`];
  const diff = files.map(file => `diff --git a/${file} b/${file}\n+changed`).join('\n');
  const picked = { features: ['session-reader.md', 'chat-codex.md', 'terminal.md'], dropped: ['made-up.md'] };
  const selected = verifier.includeRequiredJourneys(picked, { outcomes: [] }, diff);
  const required = gate.changedJourneys(diff);
  assert.deepEqual(selected.features, required);
  assert.deepEqual(selected.dropped, ['made-up.md']);
  const ruled = verifier.applyRules({ modelVerdict: 'PASS', features: selected.features, conclusions: Object.fromEntries(selected.features.map(name => [name, 'success'])) });
  assert.equal(ruled.verdict, 'PASS');
  const body = verifier.buildComment({ sha, ...ruled, summary: 'Full proof.', evidence: [], notCovered: [], runUrl });
  assert.doesNotMatch(body, /Deferred/);
  for (const name of required) assert.ok(body.includes(name), name);
});

test('a change to the inbox, chat list or reader path schedules the speed check, and a slow head blocks the merge', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const inbox = [{ filename: 'app/src/main/java/dev/multiprompt/companion/ui/ChatHistoryList.kt' }];
  assert.equal(gate.speedRelevant('', inbox), true);
  assert.equal(gate.speedRelevant('', [{ filename: 'app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt' }]), true);
  assert.equal(gate.speedRelevant('', [{ filename: 'app/src/main/java/dev/multiprompt/companion/reader/ChatHistoryCache.kt' }]), true);
  assert.equal(gate.speedRelevant('', [{ filename: 'app/src/main/assets/chat_history_reader.py' }]), true);
  assert.equal(gate.speedRelevant('', [{ filename: 'tools/speed-check/report.py' }]), true);
  assert.equal(gate.speedRelevant('', [{ filename: 'app/src/test/java/dev/multiprompt/companion/ui/ChatListRemeasureTest.kt' }]), true);
  assert.equal(gate.speedRelevant('', [{ filename: 'app/src/main/java/dev/multiprompt/companion/ui/DesignKit.kt' }]), false);
  assert.equal(gate.speedRelevant('', [{ filename: 'app/src/main/java/dev/multiprompt/companion/terminal/ShellView.kt' }]), false);
  const diff = 'diff --git a/app/src/main/java/dev/multiprompt/companion/ui/SessionPreview.kt b/app/src/main/java/dev/multiprompt/companion/ui/SessionPreview.kt\n+changed\n';
  assert.equal(gate.speedRelevant(diff), true);
  assert.equal(verifier.applyRules({ features: [], conclusions: {}, speed: { required: true, result: 'failure' } }).verdict, 'FAIL');
  const slow = verifier.applyRules({ features: [], conclusions: {}, speed: { required: true, result: 'failure' } });
  assert.match(slow.notes.join(' '), /speed check did not pass \(failure\)/);
  const fast = verifier.applyRules({ features: [], conclusions: {}, speed: { required: true, result: 'success' } });
  assert.equal(fast.verdict, 'PASS+NOTES');
  assert.match(fast.notes.join(' '), /speed check passed/);
  const skipped = verifier.applyRules({ features: [], conclusions: {}, speed: { required: false, result: 'skipped' } });
  assert.equal(skipped.verdict, 'PASS+NOTES');
  assert.doesNotMatch(skipped.notes.join(' '), /speed check/);
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const job = jobBlock(workflow, 'speed');
  assert.match(job, /if: needs\.pick\.outputs\.speed == 'yes'/);
  assert.match(job, /uses: \.\/\.github\/workflows\/speed-check\.yml/);
  assert.match(job, /base_sha: \$\{\{ needs\.pick\.outputs\.merge_base \}\}/);
  assert.match(job, /head_sha: \$\{\{ needs\.pick\.outputs\.head_sha \}\}/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /SPEED_REQUIRED: \$\{\{ needs\.pick\.outputs\.speed \}\}/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /SPEED_RESULT: \$\{\{ needs\.speed\.result \}\}/);
  assert.match(jobBlock(workflow, 'pick'), /speed: \$\{\{ steps\.pick-retry\.outputs\.speed/);
});

test('nine directly edited recipes pick nine journeys', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const recipes = ['hosts.md', 'terminal.md', 'chat-shared.md', 'chat-codex.md', 'sessions.md', 'session-reader.md', 'chat-search.md', 'ai-usage.md', 'snippets.md'];
  const diff = recipes.map(name => `diff --git a/${gate.REGISTRY_PATH}/${name} b/${gate.REGISTRY_PATH}/${name}\n+changed`).join('\n');
  const selected = verifier.includeRequiredJourneys({ features: [], dropped: [] }, { outcomes: [] }, diff);
  assert.equal(selected.features.length, 9);
  assert.deepEqual(new Set(selected.features), new Set(recipes));
});

test('every scheduled outcome still needs its own journalled proof', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  const plan = { product: true, outcomes: ['reader.send-enter-interrupt', 'chat.link-recovery', 'inbox.search', 'terminal.journey', 'app-name.journey'] };
  const selected = verifier.includeRequiredJourneys({ features: ['session-reader.md', 'chat-shared.md', 'sessions.md'] }, plan);
  assert.ok(selected.features.includes('terminal.md'));
  const proof = gate.featureErrors({ plan, sha });
  assert.match(proof.errors.join(' '), /reader.send-enter-interrupt/);
  assert.match(proof.errors.join(' '), /app-name.journey/);
});

test('all signed registry routes build candidates with the same journey identity for evidence', () => {
  const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
  for (const [name, journey] of Object.entries(gate.registry.journeys)) {
    if (journey.runner.kind !== 'signed') continue;
    assert.ok(verifier.ALLOWLIST.includes(name), name);
    const routes = verifier.journeyRunners([name]);
    assert.equal(routes[name].candidate, true, name);
    assert.equal(routes[name].feature, journey.runner.inputs.feature ?? name);
    for (const flag of ['released_chat', 'released_robots', 'pins_scroll', 'release_choices']) {
      assert.equal(routes[name][flag], journey.runner.inputs[flag] === true, `${name} ${flag}`);
    }
  }
  assert.ok(!verifier.ALLOWLIST.includes('session-settings.md'));
  assert.ok(!verifier.ALLOWLIST.includes('chat-working.md'));
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const block = jobBlock(workflow, 'journeys');
  for (const flag of ['candidate', 'released_chat', 'released_robots', 'release_choices', 'pins_scroll']) {
    assert.ok(block.includes(`      ${flag}: \${{ fromJSON(needs.pick.outputs.runners)[matrix.feature].${flag} }}`));
  }
  assert.ok(block.includes('      driver_feature: ${{ fromJSON(needs.pick.outputs.runners)[matrix.feature].feature }}'));
});

test('every signed route accepts pinned parent and head candidates but keeps published pin guards', () => {
  const prepare = stepBlocks(read('.github/workflows/verify.yml')).find(step => /id: validate-channel/.test(step));
  const script = prepare.split('        run: |\n')[1].replace(/^          /gm, '');
  const defaults = Object.fromEntries([...script.matchAll(/\$\{?([A-Z_]+)/g)].map(match => [match[1], '']));
  for (const [journey, runner] of Object.entries(verifier.journeyRunners(verifier.ALLOWLIST))) {
    if (!runner.candidate) continue;
    for (const side of ['head', 'parent']) {
      const env = {
        ...defaults, ...Object.fromEntries(Object.entries(runner).map(([key, value]) => [key.toUpperCase(), String(value)])),
        SIDE: side, VERIFY_FEATURE: runner.feature, PIN_SCROLL_PROOF: String(runner.pins_scroll),
        APP_REF: sha, RELEASE_CHANNEL: 'android-latest', BREAK_IT: 'false',
      };
      const run = spawnSync('bash', ['-euc', script], { env, encoding: 'utf8' });
      assert.equal(run.status, 0, `${journey} ${side}: ${run.stderr}`);
      if (runner.pins_scroll) {
        const published = spawnSync('bash', ['-euc', script], { env: { ...env, CANDIDATE: 'false' }, encoding: 'utf8' });
        assert.notEqual(published.status, 0);
      }
    }
  }
});

test('signed candidate preparation builds and records each pinned revision with a mocked Gradle boundary', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'signed-candidate-source-'));
  try {
    const source = path.join(directory, 'app-source');
    const evidence = path.join(directory, 'evidence');
    const bin = path.join(directory, 'bin');
    const work = path.join(directory, 'signing');
    for (const folder of [source, evidence, bin, work]) fs.mkdirSync(folder);
    const git = args => {
      const run = spawnSync('git', ['-C', source, ...args], { encoding: 'utf8' });
      assert.equal(run.status, 0, run.stderr);
      return run.stdout.trim();
    };
    git(['init', '-q']);
    const refs = [];
    for (const side of ['parent', 'head']) {
      fs.writeFileSync(path.join(source, 'source-bytes'), side);
      git(['add', 'source-bytes']);
      git(['-c', 'user.name=Fixture', '-c', 'user.email=noreply@openai.com', 'commit', '-qm', side]);
      refs.push([side, git(['rev-parse', 'HEAD'])]);
    }
    fs.writeFileSync(path.join(bin, 'gradle'), '#!/bin/sh\nset -eu\ntest "$1" = -p\ntest -s "$2/keystore.properties"\nmkdir -p "$2/app/build/outputs/apk/release"\ncp "$2/source-bytes" "$2/app/build/outputs/apk/release/app-release.apk"\n', { mode: 0o755 });
    const block = read('tools/release-choices/prepare.sh').match(/elif \[\[ "\$\{CANDIDATE:-false\}" == true \]\]; then\n([\s\S]*?)^else$/m)[1];
    for (const [side, ref] of refs) {
      git(['checkout', '--detach', '-q', ref]);
      const run = spawnSync('bash', ['-euc', `work="$WORK"; app_apk="$EVIDENCE/app.apk"; task=assembleRelease; apk=app/build/outputs/apk/release/app-release.apk; suffix=\n${block}`], {
        encoding: 'utf8', env: {
          PATH: `${bin}:${process.env.PATH}`, HOME: process.env.HOME, GITHUB_WORKSPACE: directory, GITHUB_SHA: sha, APP_REF: ref,
          EVIDENCE: evidence, WORK: work, VERIFY_FEATURE: 'settings-navigation.md', EXPECTED_COMMIT: '',
          ANDROID_KEYSTORE_PASSWORD: 'fixture', ANDROID_KEY_ALIAS: 'fixture', ANDROID_KEY_PASSWORD: 'fixture',
        },
      });
      assert.equal(run.status, 0, run.stderr);
      assert.equal(fs.readFileSync(path.join(evidence, 'app-source-sha.txt'), 'utf8').trim(), ref);
      assert.equal(fs.readFileSync(path.join(evidence, 'app.apk'), 'utf8'), side);
      assert.doesNotMatch(run.stdout + run.stderr, /storePassword|keyPassword/);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the judge receives recovery UI and host receipts before large diagnostic files', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-link-proof-'));
  try {
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), '+ tap_desc Send prompt\n' + 'diagnostic line\n'.repeat(5000));
    for (const [file, text] of [['04-shared-link-down.xml', 'Retry'], ['05-shared-link-waiting.xml', 'Waiting for chat connection'], ['06-shared-link-recovered.xml', 'SHARED_PHONE_SYNTHETIC']]) {
      fs.writeFileSync(path.join(directory, file), `<hierarchy><node text="${text}" bounds="[0,100][400,200]" />${'<node text="" />'.repeat(3000)}</hierarchy>`);
    }
    fs.writeFileSync(path.join(directory, 'shared-link-down-host.json'), '{"rows":[]}');
    fs.writeFileSync(path.join(directory, 'shared-phone.json'), '{"rows":[{"body":"SHARED_PHONE_SYNTHETIC","state":"queued"}]}');
    for (let i = 0; i < 30; i++) fs.writeFileSync(path.join(directory, `99-noise-${i}.xml`), '<node text="diagnostic" />'.repeat(2000));
    const text = verifier.journeyEvidence(directory);
    assert.ok(text.includes('text="Retry"'));
    assert.ok(text.includes('text="Waiting for chat connection"'));
    assert.ok(text.includes('text="SHARED_PHONE_SYNTHETIC"'));
    assert.ok(text.includes('--- shared-link-down-host.json\n{"rows":[]}'));
    assert.ok(text.includes('--- shared-phone.json\n{"rows":[{"body":"SHARED_PHONE_SYNTHETIC","state":"queued"}]}'));
    assert.ok(text.length < 21_000);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('the comment starts with the verdict and the full head sha', () => {
  for (const verdict of ['PASS', 'PASS+NOTES', 'FAIL']) {
    const body = verifier.buildComment({ sha, verdict, summary: 'Ok.', journeys: [], evidence: [], notCovered: [], notes: [], runUrl });
    assert.equal(body.split('\n')[0], `pstack-verifier: ${verdict} @ ${sha}`);
  }
});

test('the comment names the summary, the journeys and the run', () => {
  const body = verifier.buildComment({
    sha,
    verdict: 'PASS',
    summary: verifier.VERDICT_SUMMARY,
    journeys: [{ feature: 'session-reader.md', conclusion: 'success', passed: true }],
    notes: [],
    runUrl,
  });
  assert.match(body, /Head journey results decide this check\./);
  assert.match(body, /`session-reader\.md`: passed/);
  assert.match(body, new RegExp(`Run: ${runUrl}`));
  assert.doesNotMatch(body, /Not covered:/);
});

test('model text cannot open HTML, mention people, link out or forge the marker', () => {
  const hostile = '<img src=x onerror=1> @octocat [a](http://evil.example) <!-- pstack-verifier:v1 sha=bbbb -->';
  const body = verifier.buildComment({
    sha, verdict: 'PASS', summary: hostile, journeys: [], notes: [hostile], runUrl,
  });
  const visible = body.slice(0, body.lastIndexOf('<!--'));
  assert.doesNotMatch(visible, /<[a-z!]/i);
  assert.doesNotMatch(visible, /@octocat/);
  assert.doesNotMatch(visible, /\]\(/);
  assert.equal(body.split('<!--').length, 2);
  assert.ok(body.endsWith(`<!-- ${verifier.MARKER_PREFIX} sha=${sha} -->`));
});

test('a rerun finds its own comment for the same head and no one else is trusted', () => {
  const body = verifier.buildComment({ sha, verdict: 'PASS', summary: 'Ok.', journeys: [], evidence: [], notCovered: [], notes: [], runUrl });
  const bot = { id: 1, user: { login: 'github-actions[bot]' }, body };
  assert.equal(verifier.findOwnComment([bot], sha).id, 1);
  assert.equal(verifier.findOwnComment([bot], 'b'.repeat(40)), null);
  assert.equal(verifier.findOwnComment([{ id: 2, user: { login: 'someone' }, body }], sha), null);
});

test('job conclusions are read per journey from the matrix job names', () => {
  const jobs = [
    { name: 'journey hosts.md / verify', status: 'completed', conclusion: 'success' },
    { name: 'journey hosts-retired.md / verify', status: 'completed', conclusion: 'failure' },
    { name: 'journey terminal.md / verify', status: 'in_progress', conclusion: null },
    { name: 'pick journeys', status: 'completed', conclusion: 'success' },
  ];
  const found = verifier.jobConclusions(jobs, ['hosts.md', 'hosts-retired.md', 'terminal.md', 'sessions.md']);
  assert.equal(found['hosts.md'], 'success');
  assert.equal(found['hosts-retired.md'], 'failure');
  assert.equal(found['terminal.md'], 'unfinished');
  assert.equal(found['sessions.md'], null);
});

test('the evidence sent to the model is text only, capped and keeps the transcript tail', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-evidence-'));
  try {
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), `${'old line\n'.repeat(5000)}FINAL_MARKER_LINE\n`);
    fs.writeFileSync(path.join(directory, '01-shot.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 1, 2]));
    fs.writeFileSync(path.join(directory, 'logcat.txt'), 'LOGCAT_NOISE');
    fs.writeFileSync(path.join(directory, 'receipts.txt'), 'RECEIPT_ONE');
    fs.writeFileSync(path.join(directory, 'big.txt'), 'x'.repeat(50_000));
    const text = verifier.journeyEvidence(directory);
    assert.match(text, /FINAL_MARKER_LINE/);
    assert.match(text, /RECEIPT_ONE/);
    assert.match(text, /1 screenshots you cannot see/);
    assert.doesNotMatch(text, /LOGCAT_NOISE/);
    assert.doesNotMatch(text, /x{100}/);
    assert.ok(text.length < 10_000);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
  assert.match(verifier.journeyEvidence(path.join(os.tmpdir(), 'pstack-missing-dir')), /No evidence artifact/);
});

test('registered queue UI and receipts survive generic evidence and oversized actions', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-queue-proof-'));
  try {
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), `${'+ fill Message '.repeat(300)}WAITING_ONE\n`.repeat(60));
    for (let n = 0; n < 20; n++) fs.writeFileSync(path.join(directory, `${n}-generic.xml`), 'GENERIC_UI'.repeat(500));
    fs.writeFileSync(path.join(directory, '00-queue-ui.json'), JSON.stringify({ visible: ['WAITING_ONE', 'WAITING_TWO'], answer: 'Both messages received.' }));
    fs.writeFileSync(path.join(directory, '00-queue-receipts.txt'), 'VERIFY_RECEIVED:WAITING_ONE\nVERIFY_RECEIVED:WAITING_TWO\n');
    const text = verifier.journeyEvidence(directory, 'chat-queued.md');
    assert.ok(text.includes('"answer":"Both messages received."'));
    assert.ok(text.includes('VERIFY_RECEIVED:WAITING_TWO'));
    assert.ok(text.includes('--- driver-transcript.txt (user actions)'));
    assert.ok(text.length <= 20_000, `Evidence exceeded its budget: ${text.length}`);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('migrated CLI journeys show their mpa actions in the user-action summary', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-mpa-actions-'));
  try {
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), "+ /home/runner/scripts/mpa fill 'Message verify-session' /cl\n+ /home/runner/scripts/mpa read --wait-text /client\n+dump_ui\n");
    const text = verifier.journeyEvidence(directory, 'clear-ranking.md');
    assert.ok(text.includes('--- driver-transcript.txt (user actions)'));
    assert.ok(text.includes("mpa fill 'Message verify-session' /cl"));
    assert.ok(text.includes('mpa read --wait-text /client'));
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('process exit plants stay citable under a tail of dump noise', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-exit-actions-'));
  try {
    const plant = '++ timeout 90s adb shell am start -n dev.multiprompt.companion.debug/dev.multiprompt.companion.DebugCrashActivity --es exit anr';
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'),
      `${plant}\n${'+ uiautomator dump\n'}${'<node text="" />'.repeat(4000)}\nFINAL_NOISE\n`);
    const text = verifier.journeyEvidence(directory, 'crash-report.md');
    assert.ok(text.includes('--- driver-transcript.txt (process exits)'));
    assert.ok(text.includes(plant));
    assert.ok(text.length <= 20_000, `Evidence exceeded its budget: ${text.length}`);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('identity evidence retains complete independent receipt JSON bodies', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-identity-proof-'));
  try {
    const before = JSON.stringify({ accepted: [{ phone_id: 'phone-before-2532', native_id: 'native-before-2532' }], transcript: 'before'.repeat(340) });
    const reconnect = JSON.stringify({ accepted: [{ phone_id: 'phone-before-2532' }, { phone_id: 'phone-after-2532' }], events: 'turn.started'.repeat(380) });
    fs.writeFileSync(path.join(directory, 'projection-receipts-before.json'), before);
    fs.writeFileSync(path.join(directory, 'projection-receipts-reconnect.json'), reconnect);
    fs.writeFileSync(path.join(directory, 'projection-state-reconnect.json'), '{"occurrences":1,"visible":["PHONE_BEFORE_2532","REPLY_BEFORE_2532","PHONE_AFTER_2532","REPLY_AFTER_2532"]}');
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), '+ tap_desc Send prompt\n');
    for (let n = 0; n < 20; n++) fs.writeFileSync(path.join(directory, `${n}-generic.xml`), 'GENERIC_UI'.repeat(500));
    const text = verifier.journeyEvidence(directory, 'chat-identity.md');
    assert.ok(text.includes(before), 'The full before receipt body must reach the verifier');
    assert.ok(text.includes(reconnect), 'The full reconnect receipt body must reach the verifier');
    assert.ok(text.includes('"occurrences":1'));
    assert.ok(text.includes('+ tap_desc Send prompt'));
    assert.ok(text.length <= 20_000);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('the Done-when lines of the description are passed on', () => {
  const body = '## Why\nBecause.\n\n## Done when\n- The list scrolls.\n- Nothing jumps.\n\n## Notes\nExtra.';
  const done = verifier.doneWhen(body);
  assert.match(done, /The list scrolls/);
  assert.match(done, /Nothing jumps/);
  assert.doesNotMatch(done, /Extra/);
  assert.equal(verifier.doneWhen('No such section.'), '');
});

test('the verdict job posts journey-only PASS+NOTES when nothing was driven', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-judge-'));
  try {
    fs.writeFileSync(path.join(directory, 'change.diff'), 'diff --git a/tools/x.sh b/tools/x.sh\n+echo\n');
    fs.writeFileSync(path.join(directory, 'gh'), `#!${process.execPath}
const fs = require('node:fs');
const args = process.argv.slice(2);
if (args.some(arg => arg.includes('/jobs?'))) console.log(JSON.stringify([{ jobs: [] }]));
else if (args.includes('Accept: application/vnd.github.diff')) process.stdout.write(fs.readFileSync('${path.join(directory, 'change.diff').replace(/\\/g, '\\\\')}', 'utf8'));
else console.log(JSON.stringify({ number: 1, title: 'Tidy the checks', body: 'tooling', head: { sha: '${sha}' } }));
`, { mode: 0o755 });
    const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'judge'], {
      cwd: directory,
      encoding: 'utf8',
      env: {
        PATH: `${directory}:${process.env.PATH}`, PICK_RESULT: 'success', REPO: 'a/b', PR_NUMBER: '1',
        HEAD_SHA: sha, RUN_ID: '1', RUN_URL: runUrl, FEATURES: '[]', CHANGED_OUTCOMES: '[]', VERIFIER_DRY: '1',
        COVERAGE_REPORT: path.join(directory, 'coverage.json'),
      },
    });
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /^pstack-verifier: PASS\+NOTES @ /m);
    assert.match(run.stdout, /Head journey results decide this check\./);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('the judge blocks a changed journey that is red on main', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-judge-direct-'));
  try {
    const recipe = '.claude/skills/verify-multiprompt-android/features/session-pins.md';
    fs.writeFileSync(path.join(directory, 'change.diff'), `diff --git a/${recipe} b/${recipe}\n+Pin the row.\n`);
    fs.writeFileSync(path.join(directory, 'gh'), `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
if (args[0] === 'run' && args[1] === 'list') console.log(JSON.stringify([{ databaseId: '42', url: '${runUrl}' }]));
else if (args[0] === 'run' && args[1] === 'download') {
  const out = args[args.indexOf('-D') + 1];
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'red-journeys.json'), JSON.stringify(['session-pins.md']));
}
else if (args.includes('Accept: application/vnd.github.diff')) process.stdout.write(fs.readFileSync('${path.join(directory, 'change.diff').replace(/\\/g, '\\\\')}', 'utf8'));
else if (args.some(arg => String(arg).includes('/jobs?'))) console.log(JSON.stringify([{ jobs: [{ name: 'journey session-pins.md / verify', status: 'completed', conclusion: 'failure' }] }]));
else console.log(JSON.stringify({ number: 1, title: 'IGSH-3028: Session phone tests read the screen correctly again', body: 'Fixes the session pins journey.', head: { sha: '${sha}' } }));
`, { mode: 0o755 });
    const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'judge'], {
      cwd: directory,
      encoding: 'utf8',
      env: {
        PATH: `${directory}:${process.env.PATH}`, PICK_RESULT: 'success', REPO: 'a/b', PR_NUMBER: '1',
        HEAD_SHA: sha, RUN_ID: '1', RUN_URL: runUrl, FEATURES: '["session-pins.md"]', CHANGED_OUTCOMES: '[]', VERIFIER_DRY: '1',
        COVERAGE_REPORT: path.join(directory, 'coverage.json'),
      },
    });
    assert.equal(run.status, 1, run.stderr);
    assert.match(run.stdout, /^pstack-verifier: FAIL @ /m);
    assert.match(run.stdout, /This PR changes session-pins\.md, so being red on main/);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('a picker that did not finish gives no verdict', () => {
  const env = { PATH: process.env.PATH, PICK_RESULT: 'failure' };
  const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'judge'], { env, encoding: 'utf8' });
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /no verdict/);
});

test('every allowlisted journey is a dispatch option of verify.yml and has a map', () => {
  const options = dispatchFeatureOptions(read('.github/workflows/verify.yml'));
  assert.ok(options.length > 10);
  assert.equal(new Set(verifier.ALLOWLIST).size, verifier.ALLOWLIST.length);
  for (const name of verifier.ALLOWLIST) {
    assert.ok(options.includes(verifier.journeyRunners([name])[name].feature), `${name} has a driver option in verify.yml`);
    assert.ok(fs.existsSync(path.join(root, '.claude/skills/verify-multiprompt-android/features', name)), `${name} has a journey map`);
  }
});

test('dedicated and manual journeys are not allowlisted', () => {
  for (const name of ['app-name.md', 'bot-photo-refresh.md', 'chat-working.md', 'session-settings.md']) {
    assert.ok(!verifier.ALLOWLIST.includes(name), name);
  }
});

test('the journey catalog describes each allowlisted journey from the feature map', () => {
  const catalog = verifier.journeyCatalog(root);
  for (const name of verifier.ALLOWLIST) assert.match(catalog, new RegExp(`^- ${name.replace('.', '\\.')}: .+`, 'm'));
  assert.doesNotMatch(catalog, /no description/);
});

test('the journeys job passes only the existing signing secrets to verify.yml', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const journeys = jobBlock(workflow, 'journeys');
  assert.match(journeys, /^ {4}uses: \.\/\.github\/workflows\/verify\.yml$/m);
  assert.match(journeys, /^ {6}feature: \$\{\{ matrix\.feature \}\}$/m);
  assert.match(journeys, /^ {6}side: head$/m);
  assert.match(journeys, /^ {6}app_ref: \$\{\{ needs\.pick\.outputs\.head_sha \}\}$/m);
  assert.match(journeys, /fail-fast: false/);
  assert.deepEqual([...journeys.matchAll(/secrets\.([A-Z0-9_]+)/g)].map(match => match[1]), ['ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']);
  assert.doesNotMatch(journeys, /DEEPSEEK/);
  assert.doesNotMatch(workflow, /secrets:\s*inherit/);
});

test('model keys reach only Token Ladder ladder-call steps in the pick job', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const uses = workflow.match(/secrets\.DEEPSEEK_API_KEY/g) ?? [];
  assert.equal(uses.length, 2);
  const pick = jobBlock(workflow, 'pick');
  assert.match(pick, /valentinyeo\/token-ladder\/\.github\/actions\/ladder-call@main/);
  assert.match(pick, /purpose: pr-journey-picker/);
  assert.match(pick, /node \.github\/scripts\/pstack-verifier\/verifier\.cjs pick-request/);
  assert.doesNotMatch(jobBlock(workflow, 'pstack-verifier'), /DEEPSEEK|ladder-call/);
  assert.doesNotMatch(workflow, /^ {2}\S.*\n(?:.*\n)*?\s+run:.*DEEPSEEK/m);
});

test('the pick and judge jobs check out only the scripts and the journey maps, never PR app code', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  for (const job of ['pick', 'pstack-verifier']) {
    const block = jobBlock(workflow, job);
    assert.match(block, /sparse-checkout:/, job);
    assert.match(block, /persist-credentials: false/, job);
    assert.doesNotMatch(block, /gradle|assemble|setup-java|emulator/i, job);
  }
  assert.match(jobBlock(workflow, 'pstack-verifier'), /\.claude\/skills\/verify-multiprompt-android\/features/);
});

test('every PR to main gets an unconditional pstack-verifier result, including drafts, forks and dependabot', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.match(jobBlock(workflow, 'pstack-verifier'), /^ {4}name: pstack-verifier$/m);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /needs: \[pick, journeys, speed\]/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /pull-requests: write/);
  const pick = jobBlock(workflow, 'pick');
  assert.doesNotMatch(pick, /^ {4}if:/m);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /^ {4}if: \$\{\{ always\(\) \}\}$/m);
  const triggers = topBlock(workflow, 'on').join('\n');
  assert.match(triggers, /branches: \[main\]/);
  assert.doesNotMatch(triggers, /paths(?:-ignore)?:/);
  assert.match(workflow, /types: \[opened, reopened, synchronize, ready_for_review, edited\]/);
  assert.match(workflow, /cancel-in-progress: true/);
});

test('the caller grants the permissions verify.yml asks for, and dispatch adds no input that verify.yml reads', () => {
  const verify = read('.github/workflows/verify.yml');
  const asked = topBlock(verify, 'permissions').slice(1).filter(Boolean).map(line => line.trim());
  const granted = jobBlock(read('.github/workflows/pstack-verifier.yml'), 'journeys');
  for (const permission of asked) assert.ok(granted.includes(permission), `journeys grants ${permission}`);
  const caller = topBlock(read('.github/workflows/pstack-verifier.yml'), 'on').join('\n');
  const inputs = [...caller.matchAll(/^ {6}(\w+):$/gm)].map(match => match[1]);
  assert.deepEqual(inputs, ['pr']);
});

test('verify.yml keeps non-PR triggers and delegates PR journeys to the verifier picker', () => {
  const verify = read('.github/workflows/verify.yml');
  const triggers = topBlock(verify, 'on').join('\n');
  for (const trigger of ['workflow_dispatch', 'workflow_call']) {
    assert.match(triggers, new RegExp(`^ {2}${trigger}:`, 'm'), trigger);
  }
  assert.doesNotMatch(triggers, /^ {2}pull_request:/m);
  const call = triggers.slice(triggers.indexOf('  workflow_call:'), triggers.indexOf('  workflow_dispatch:'));
  assert.match(call, /^ {6}feature:\n {8}description: .+\n {8}required: true\n {8}type: string\n/m);
  assert.deepEqual([...call.slice(call.indexOf('    inputs:')).matchAll(/^ {6}(\w+):$/gm)].map(match => match[1]), ['driver_feature', 'released_chat', 'released_robots', 'release_choices', 'pins_scroll', 'candidate', 'break_it', 'feature', 'side', 'app_ref']);
  assert.match(call, /^ {6}side:\n {8}description: .+\n {8}required: false\n {8}type: string\n/m);
  assert.match(call, /^ {6}app_ref:\n {8}description: .+\n {8}required: false\n {8}type: string\n$/m);
  assert.match(verify, /^ {2}group: verify-\$\{\{ github\.ref \}\}\$\{\{ inputs\.feature && inputs\.feature != github\.event\.inputs\.feature && format\('-\{0\}', inputs\.feature\) \|\| '' \}\}\$\{\{ inputs\.side && format\('-\{0\}', inputs\.side\) \|\| '' \}\}-\$\{\{ inputs\.break_it_control \|\| 'none' \}\}$/m);
});

test('a change no journey fits schedules no unrelated Android smoke', () => {
  const picked = verifier.parsePick(JSON.stringify({ features: ['not-a-journey.md'], changed_outcomes: [], change_kind: 'tooling', reason: 'Only workflow files changed.' }));
  assert.deepEqual(picked.features, []);
  assert.equal(picked.smoke, false);
  const result = verifier.applyRules({ modelVerdict: 'PASS', features: [], conclusions: {}, notDrivenReason: picked.reason });
  assert.equal(result.verdict, 'PASS+NOTES');
  assert.match(result.notes.join(' '), /Nothing was driven/);
});

function stepBlocks(source) {
  const verify = jobBlock(source, 'verify');
  const body = verify.slice(verify.indexOf('\n    steps:\n') + '\n    steps:\n'.length);
  return body.split(/^ {6}- /m).slice(1).map(block => `- ${block}`);
}

test('verifier.cjs has no direct provider URL or model key env reads', () => {
  const source = read('.github/scripts/pstack-verifier/verifier.cjs');
  assert.doesNotMatch(source, /api\.deepseek\.com|DEEPSEEK_API_KEY|CLAUDE_CODE_OAUTH/);
  assert.match(read('.github/workflows/test.yml'), /lint-direct-model-calls@main/);
});

test('coverage gaps are notes and never change the verdict', () => {
  const gap = 'FEATURE_NOT_EXERCISED: required journey terminal.md was not scheduled.';
  const passed = verifier.applyRules({ features: ['hosts.md'], conclusions: { 'hosts.md': 'success' }, featureProof: { accepted: [], errors: [gap] } });
  assert.equal(passed.verdict, 'PASS');
  assert.deepEqual(passed.notes, [gap]);
  const failed = verifier.applyRules({ features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' }, featureProof: { accepted: [], errors: [gap] } });
  assert.equal(failed.verdict, 'FAIL');
  assert.match(failed.notes.join(' '), /Journey not passed: hosts\.md/);
  assert.match(failed.notes.join(' '), /FEATURE_NOT_EXERCISED/);
});

test('ladder failure in the picker falls back to the feature map', async () => {
  const codePlan = { outcomes: ['codex.transcript'], product: true };
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-ladder-'));
  try {
    const env = { LADDER_FAILED: 'true', LADDER_ANSWER_FILE: path.join(directory, 'missing.json') };
    const picked = await verifier.pickWithFallback({
      codePlan,
      ask: () => verifier.ladderAnswerContent(env),
      log: () => {},
    });
    assert.deepEqual(picked.changedOutcomes, codePlan.outcomes);
    assert.match(picked.reason, /AI picker unavailable/);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('a bug fix is checked by its head journeys, not by a parent repro or a stressed replay', () => {
  const ruled = verifier.applyRules({
    stressProof: { errors: ['BREAK_IT_FAILED: retained stress evidence is required for every bug-fix journey.'], evidence: [] },
    features: ['reader-version.md', 'settings-controls.md'],
    conclusions: { 'reader-version.md': 'success', 'settings-controls.md': 'success' },
  });
  assert.equal(ruled.verdict, 'PASS');
  assert.doesNotMatch(ruled.notes.join(' '), /No recorded repro|setup, build or fixture|BREAK_IT_FAILED/);
  assert.equal(verifier.applyRules({ features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' } }).verdict, 'FAIL');
});

test('two passing head journeys leave no notes', () => {
  const ruled = verifier.applyRules({
    features: ['reader-version.md', 'settings-controls.md'],
    conclusions: { 'reader-version.md': 'success', 'settings-controls.md': 'success' },
  });
  assert.equal(ruled.verdict, 'PASS');
  assert.deepEqual(ruled.notes, []);
});

test('one head journey that did not pass fails the job whatever the other journeys showed', () => {
  for (const conclusion of ['failure', 'cancelled', 'skipped', null]) {
    const ruled = verifier.applyRules({
      features: ['reader-version.md', 'settings-controls.md'],
      conclusions: { 'reader-version.md': 'success', 'settings-controls.md': conclusion },
    });
    assert.equal(ruled.verdict, 'FAIL', conclusion);
    assert.match(ruled.notes.join(' '), /Journey not passed: settings-controls\.md/);
  }
});

test('the bug-fix label comes from the title or the ticket, never from a feature', () => {
  const ruled = verifier.applyRules({
    features: ['hosts.md'],
    conclusions: { 'hosts.md': 'success' },
  });
  assert.equal(ruled.verdict, 'PASS');
  assert.deepEqual(ruled.notes, []);
  const title = 'Add inbox folders';
  assert.equal(verifier.isBugFix({ title, changeKind: 'product', tickets: { 'IGSH-1': 'Add inbox folders' } }), false);
  assert.equal(verifier.isBugFix({ title, changeKind: 'bug' }), true);
});

test('a failed head journey forces FAIL and an all-passed set stays PASS', () => {
  const ruled = verifier.applyRules({ features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' } });
  assert.equal(ruled.verdict, 'FAIL');
  assert.equal(verifier.applyRules({ features: ['hosts.md', 'terminal.md'], conclusions: { 'hosts.md': 'success', 'terminal.md': 'success' } }).verdict, 'PASS');
  assert.match(ruled.notes.join(' '), /Journey not passed: hosts\.md/);
});

test('the parent result comes from the recorded result file, the job conclusion and the app source', () => {
  const base = 'b'.repeat(40);
  const result = parent => verifier.parentResult({ mergeBase: base, sourceSha: base, ...parent });
  assert.equal(result({ conclusion: 'success', result: { passed: true } }), 'passed');
  assert.equal(result({ conclusion: 'success', result: { passed: false } }), 'not passed');
  assert.equal(result({ conclusion: 'failure', result: { passed: true } }), 'not passed');
  assert.equal(result({ conclusion: 'cancelled', result: null }), 'not passed');
  assert.equal(result({ conclusion: 'success', result: null }), 'no result');
  assert.equal(result({ conclusion: null, result: { passed: true } }), 'no result');
  assert.equal(result({ conclusion: null, result: null }), 'no result');
  assert.equal(verifier.parentResult({ mergeBase: base, sourceSha: sha, conclusion: 'success', result: { passed: true } }), 'no result');
  assert.equal(verifier.parentResult({ mergeBase: base, sourceSha: '', conclusion: 'success', result: { passed: false } }), 'not passed');
  assert.equal(result({ conclusion: 'success', result: { passed: false, productAssertion: true } }), 'product assertion');
  assert.equal(result({ conclusion: 'failure', result: { passed: false, productAssertion: false } }), 'not passed');
  assert.equal(verifier.parentResult({ mergeBase: base, sourceSha: sha, conclusion: 'success', result: { passed: false, productAssertion: true } }), 'no result');
});

test('the result file and source sha are read from the parent artifact only when well formed', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-side-'));
  try {
    assert.deepEqual(verifier.readSideFiles(directory), { result: null, sourceSha: '' });
    assert.deepEqual(verifier.readSideFiles(null), { result: null, sourceSha: '' });
    fs.writeFileSync(path.join(directory, verifier.RESULT_FILE), '{"side":"parent","passed":false,"steps":{"native-ui":"failure"}}');
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), `${sha}\n`);
    const files = verifier.readSideFiles(directory);
    assert.equal(files.result.passed, false);
    assert.equal(files.sourceSha, sha);
    fs.writeFileSync(path.join(directory, verifier.RESULT_FILE), '{"passed":"yes"}');
    assert.equal(verifier.readSideFiles(directory).result, null);
    fs.writeFileSync(path.join(directory, verifier.RESULT_FILE), 'not json');
    assert.equal(verifier.readSideFiles(directory).result, null);
    fs.writeFileSync(path.join(directory, 'failure-class.txt'), `${verifier.PRODUCT_ASSERTION}\n`);
    assert.deepEqual(verifier.readSideFiles(directory).result, { passed: false, productAssertion: true });
    fs.writeFileSync(path.join(directory, 'failure-class.txt'), 'SETUP_FAILED\n');
    fs.writeFileSync(path.join(directory, verifier.RESULT_FILE), '{"passed":false}');
    assert.deepEqual(verifier.readSideFiles(directory).result, { passed: false, productAssertion: false });
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('the comment lists the head result of every journey', () => {
  const body = verifier.buildComment({
    sha,
    verdict: 'PASS',
    summary: 'Fixed.',
    journeys: [
      { feature: 'hosts.md', conclusion: 'success', passed: true },
      { feature: 'terminal.md', conclusion: 'failure', passed: false },
      { feature: 'sessions.md', conclusion: null, passed: false },
    ],
    evidence: [],
    notCovered: [],
    notes: [],
    runUrl,
  });
  assert.match(body, /^Journeys driven:$/m);
  assert.match(body, /^- `hosts\.md`: passed$/m);
  assert.match(body, /^- `terminal\.md`: not passed \(failure\)$/m);
  assert.match(body, /^- `sessions\.md`: no result$/m);
  assert.doesNotMatch(body, /parent/);
  assert.equal(body.split('\n')[0], `pstack-verifier: PASS @ ${sha}`);
});

test('journey job names and artifact names match the head side only', () => {
  const jobs = [
    { name: 'journey hosts.md / verify', status: 'completed', conclusion: 'success' },
    { name: 'parent journey terminal.md / verify', status: 'completed', conclusion: 'cancelled' },
  ];
  assert.deepEqual(verifier.jobConclusions(jobs, ['hosts.md', 'terminal.md']), { 'hosts.md': 'success', 'terminal.md': null });
  assert.equal(verifier.artifactName('hosts.md', '9'), 'verify-hosts.md-9');
  const verify = read('.github/workflows/verify.yml');
  assert.match(verify, /name: verify-\$\{\{ env\.VERIFY_JOURNEY \}\}-\$\{\{ github\.run_id \}\}\$\{\{ inputs\.side == 'parent' && '-parent' \|\| '' \}\}\$\{\{ inputs\.side && inputs\.side != 'head' && inputs\.side != 'parent' && format\('-\{0\}', inputs\.side\) \|\| '' \}\}$/m);
  assert.match(verify, /stage: stage-1\$\{\{ inputs\.side && format\('-\{0\}-\{1\}', inputs\.side, inputs\.feature\) \|\| '' \}\}$/m);
});

test('the merge base comes from the compare API and must be a full sha', () => {
  const asked = [];
  const base = 'c'.repeat(40);
  const found = verifier.fetchMergeBase('o/r', 'main', sha, args => { asked.push(args); return JSON.stringify({ merge_base_commit: { sha: base } }); });
  assert.equal(found, base);
  assert.deepEqual(asked, [['api', `repos/o/r/compare/main...${sha}`]]);
  for (const bad of [{}, { merge_base_commit: {} }, { merge_base_commit: { sha: 'abc' } }, { merge_base_commit: { sha: 'C'.repeat(40) } }]) {
    assert.throws(() => verifier.fetchMergeBase('o/r', 'main', sha, () => JSON.stringify(bad)), /no merge base/);
  }
  const pick = read('.github/workflows/pstack-verifier.yml').split('\njobs:\n')[1].split('\n  journeys:')[0];
  assert.match(pick, /merge_base: \$\{\{ steps\.pick-retry\.outputs\.merge_base \|\| steps\.pick\.outputs\.merge_base/);
});

test('no PR run drives the parent side', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.doesNotMatch(workflow, /^ {2}parents:$/m);
  assert.doesNotMatch(workflow, /side: parent/);
  assert.doesNotMatch(workflow, /parent journey/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /MERGE_BASE: \$\{\{ needs\.pick\.outputs\.merge_base \}\}/);
});

test('every step of verify.yml cannot turn the parent run red, and the head run is unchanged', () => {
  const verify = read('.github/workflows/verify.yml');
  const steps = stepBlocks(verify);
  assert.ok(steps.length > 20);
  for (const step of steps) {
    const name = step.match(/name: (.+)/)?.[1] ?? step.split('\n')[0];
    assert.match(step, /^ {8}continue-on-error: (?:\$\{\{ inputs\.side == 'parent'(?: \|\| .+)? \}\}|true)$/m, name);
  }
  assert.doesNotMatch(jobBlock(verify, 'verify'), /^ {4}continue-on-error:/m);
  const drive = steps.find(step => /id: native-ui/.test(step));
  assert.match(drive, /continue-on-error: \$\{\{ inputs\.side == 'parent' \|\| github\.event_name == 'workflow_dispatch' && \(/);
  assert.match(drive, /timeout-minutes: \$\{\{ inputs\.feature == 'reader-controls\.md' && 40 \|\| inputs\.side == 'parent' && 20 \|\| inputs\.break_it && 55 \|\| 35 \}\}/);
  assert.match(jobBlock(verify, 'verify'), /^ {4}timeout-minutes: \$\{\{ inputs\.feature == 'reader-controls\.md' && 50 \|\| inputs\.side == 'parent' && 50 \|\| inputs\.break_it && 55 \|\| 35 \}\}$/m);
  const ids = steps.map(step => step.match(/^ {8}id: (\S+)$/m)?.[1]).filter(Boolean);
  assert.equal(new Set(ids).size, ids.length);
  assert.ok(ids.includes('native-ui'));
});

test('a parent journey that fails records its exit code and leaves the job green', () => {
  const verify = read('.github/workflows/verify.yml');
  assert.match(jobBlock(verify, 'verify'), /^ {6}VERIFY_SIDE: \$\{\{ inputs\.side \}\}$/m);
  const drive = stepBlocks(verify).find(step => /id: native-ui/.test(step));
  assert.match(drive, /if \[ "\$\{VERIFY_SIDE:-\}" = parent \]; then\n\s+set \+e\n\s+\(set -euo pipefail; proof\)\n\s+code=\$\?\n\s+set -e\n/);
  assert.match(drive, /printf '%s\\n' "\$code" > "\$EVIDENCE\/parent-driver-exit\.txt"\n\s+else\n\s+proof\n\s+fi/);
});

test('every step that can fail a head run has an id so the parent result counts it', () => {
  const steps = stepBlocks(read('.github/workflows/verify.yml'));
  for (const step of steps) {
    const name = step.match(/name: (.+)/)?.[1] ?? step.split('\n')[0];
    const condition = step.match(/^ {8}if: (.+)$/m)?.[1] ?? '';
    if (condition.startsWith('always()') || condition.startsWith('(always()')) continue;
    assert.match(step, /^ {8}id: \S+$/m, name);
  }
});

test('the parent result is written into the artifact by an always step before the upload', () => {
  const verify = read('.github/workflows/verify.yml');
  const steps = stepBlocks(verify);
  const record = steps.findIndex(step => /Record the parent result for the judge/.test(step));
  const upload = steps.findIndex(step => /name: Upload evidence even on failure/.test(step));
  assert.ok(record > 0 && upload > record);
  assert.match(steps[record], /if: \(always\(\) && inputs\.side == 'parent'\) && steps\.parent-cache\.outputs\.hit != 'true'/);
  assert.match(steps[record], /STEPS_JSON: \$\{\{ toJSON\(steps\) \}\}/);
  assert.match(steps[record], /\$EVIDENCE\/side-result\.json/);
  assert.match(steps[record], /\$steps\["native-ui"\]\.outcome == "success"/);
  assert.match(steps[record], /\$driver == 0/);
  assert.match(steps[record], /parent-driver-exit\.txt/);
  assert.match(steps[record], /failure-class\.txt/);
  assert.match(steps[record], /PRODUCT_ASSERTION_FAILED/);
  assert.match(steps[record], /productAssertion: \$product/);
  assert.match(steps[upload], /if: always\(\)/);
});

test('drive.sh prints a product-assertion marker and keeps setup failures separate', () => {
  const drive = read('.claude/skills/verify-multiprompt-android/scripts/drive.sh');
  assert.match(drive, /^product_checks=1$/m);
  assert.match(drive, /printf 'PRODUCT_ASSERTION_FAILED\\n' \| tee "\$EVIDENCE\/failure-class\.txt"/);
  assert.match(drive, /printf 'SETUP_FAILED\\n' \| tee "\$EVIDENCE\/failure-class\.txt"/);
  assert.match(drive, /if \[\[ "\$code" != 0 && "\$product_checks" == 1 && "\$setup_failure" != 1 \]\]; then/);
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.match(read('.github/workflows/pstack-verifier.yml').split('\njobs:\n')[1].split('\n  journeys:')[0], /change_kind: \$\{\{ steps\.pick-retry\.outputs\.change_kind/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /CHANGE_KIND: \$\{\{ needs\.pick\.outputs\.change_kind \}\}/);
  assert.match(jobBlock(workflow, 'pick'), /^\s+tools\/release-notes\/titles$/m);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /^\s+tools\/release-notes\/titles$/m);
});

test('verify.yml pins the parent app source to a full sha and takes it through app_ref', () => {
  const verify = read('.github/workflows/verify.yml');
  assert.match(verify, /^ {6}APP_REF: \$\{\{ inputs\.app_ref \}\}$/m);
  assert.match(verify, /SIDE: \$\{\{ inputs\.side \}\}/);
  assert.match(verify, /if \[\[ "\$SIDE" == parent \]\]; then\n\s+\[\[ "\$APP_REF" =~ \^\[0-9a-f\]\{40\}\$ \]\]/);
  const checkout = stepBlocks(verify).find(step => /id: app-checkout/.test(step));
  assert.match(checkout, /if: \(env\.APP_REF != ''\)/);
  assert.match(checkout, /ref: \$\{\{ env\.APP_REF \}\}/);
  assert.match(checkout, /path: app-source/);
});

test('a verdict wrapped in prose or a fence still parses, and a bad answer is asked for once more', async () => {
  assert.deepEqual(verifier.parseJson('Here it is:\n```json\n{"verdict":"PASS"}\n```\nDone.'), { verdict: 'PASS' });
  assert.deepEqual(verifier.parseJson('Verdict: {"verdict":"FAIL"} end'), { verdict: 'FAIL' });
  assert.equal(verifier.parseJson('no object here'), null);
  const answers = ['not json', '{"ok":true}'];
  let asked = 0;
  const parse = text => { const value = verifier.parseJson(text); if (!value) throw new Error('bad'); return value; };
  assert.deepEqual(await verifier.askParsed({ ask: async () => answers[asked++], parse }), { ok: true });
  assert.equal(asked, 2);
  asked = 0;
  await assert.rejects(verifier.askParsed({ ask: async () => { asked += 1; return 'still bad'; }, parse }), /bad/);
  assert.equal(asked, 2);
  asked = 0;
  await assert.rejects(verifier.askParsed({ ask: async () => { asked += 1; throw Object.assign(new Error('cut'), { fatal: true }); }, parse }), /cut/);
  assert.equal(asked, 1);
});

test('every required outcome journey runs, so nothing waits for nightly', () => {
  const outcomes = ['sign-in.journey', 'host-changed-key.journey', 'chat-reactions.journey', 'claude.skills-model-effort', 'inbox.search'];
  const selected = verifier.includeRequiredJourneys({ features: ['sign-in.md'], smoke: false }, { product: true, outcomes });
  assert.deepEqual(selected.features, ['sign-in.md', 'host-changed-key.md', 'chat-reactions.md', 'native-skills.md', 'sessions.md']);
});

test('android-full schedules every deferred candidate with its registered runner inputs', () => {
  const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'nightly'], { encoding: 'utf8', env: { ...process.env, GITHUB_OUTPUT: '' } });
  assert.equal(run.status, 0, run.stderr);
  const matrix = JSON.parse(run.stdout.trim().slice('matrix='.length));
  assert.deepEqual(matrix.include.map(row => row.journey), verifier.ALLOWLIST);
  for (const row of matrix.include) assert.deepEqual(row, { journey: row.journey, ...verifier.journeyRunners([row.journey])[row.journey] });
  const nightly = read('.github/workflows/android-full.yml');
  assert.match(jobBlock(nightly, 'journey-plan'), /run: node \.github\/scripts\/pstack-verifier\/verifier\.cjs nightly/);
  const job = jobBlock(nightly, 'nightly-journeys');
  assert.match(job, /matrix: \$\{\{ fromJSON\(needs\.journey-plan\.outputs\.matrix\) \}\}/);
  assert.match(job, /uses: \.\/\.github\/workflows\/verify\.yml/);
  assert.match(job, /feature: \$\{\{ matrix\.journey \}\}/);
  assert.match(job, /driver_feature: \$\{\{ matrix\.feature \}\}/);
  for (const flag of ['candidate', 'released_chat', 'released_robots', 'release_choices', 'pins_scroll']) assert.ok(job.includes(`      ${flag}: \${{ matrix.${flag} }}`), flag);
  assert.match(job, /app_ref: \$\{\{ github\.sha \}\}/);
});

test('required journeys replace a smoke fallback without inventing coverage for manual gaps', () => {
  const picked = { features: [verifier.SMOKE_JOURNEY], smoke: true };
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { outcomes: ['sign-in.journey'] }).features, ['sign-in.md']);
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { outcomes: ['session-settings.journey'] }).features, []);
});

test('PR392 native model and effort receipts reach the judge before diagnostic evidence fills its budget', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-native-proof-'));
  try {
    const fixture = path.join(root, 'tools/fixtures/feature-coverage/987d3f8e-native');
    fs.cpSync(fixture, directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), 'diagnostic line\n'.repeat(5000));
    for (let index = 0; index < 30; index += 1) fs.writeFileSync(path.join(directory, `aux-${String(index).padStart(2, '0')}.txt`), 'diagnostic '.repeat(100));
    const evidence = verifier.journeyEvidence(directory, 'native-skills.md');
    assert.match(evidence, /--- native-result\.json\n[\s\S]*"model": "Sonnet 5\.5"/);
    assert.match(evidence, /--- native-receipts\.txt\n[\s\S]*VERIFY_RECEIVED:\/model sonnet/);
    assert.match(evidence, /VERIFY_RECEIVED:\/effort high/);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test('journey builds reuse cached fixed-version dependencies during repository outages', () => {
  const build = stepBlocks(read('.github/workflows/verify.yml')).find(step => /id: build-apk/.test(step));
  assert.match(build, /tools\/ci\/gradle-retry\.sh -p "\$app_source"/);
  assert.doesNotMatch(build, /--refresh-dependencies/);
});

test('proof uploads survive the same-run retry that failed PR392 at c2f673c6', () => {
  const requireReplayable = source => {
    const lines = source.split('\n');
    let found = 0;
    lines.forEach((line, index) => {
      if (!line.includes('uses: actions/upload-artifact@')) return;
      found += 1;
      let end = index + 1;
      while (end < lines.length && !/^\s*- /.test(lines[end])) end += 1;
      assert.match(lines.slice(index, end).join('\n'), /^\s+overwrite: true$/m, 'A repeated check must replace its own per-run artifact, not fail with HTTP 409');
    });
    assert.ok(found > 0);
  };
  for (const file of ['.github/workflows/pstack-verifier.yml', '.github/workflows/verify.yml', '.github/workflows/test.yml', '.github/workflows/account-journey.yml', '.github/actions/android-proof/action.yml']) requireReplayable(read(file));
  assert.throws(() => requireReplayable('uses: actions/upload-artifact@v4\nwith:\n  name: verifier-tooling-proof\n  path: tooling-proof.txt\n'));
});

test('only a complete docs/tooling file list gets a no-product result', () => {
  for (const filename of ['README.md', 'CLAUDE.md', 'docs/reader.md', 'tools/check.sh', '.github/workflows/test.yml']) {
    assert.equal(verifier.noProductChange([{ filename }], 1), true, filename);
  }
  for (const filename of ['app/src/main/App.kt', 'app/src/main/README.md', 'app/build.gradle.kts', 'gradle/libs.versions.toml', 'build.gradle.kts', 'scripts/build.sh', '.claude/skills/verify-multiprompt-android/features/hosts.md', 'unknown/file']) {
    assert.equal(verifier.noProductChange([{ filename: 'README.md' }, { filename }], 2), false, filename);
    assert.equal(verifier.noProductChange([{ filename: 'docs/old.md', previous_filename: filename }], 1), false, filename);
  }
  for (const [files, count] of [[[], 0], [[], 1], [[{ filename: 'README.md' }], 2], [null, 1]]) {
    assert.throws(() => verifier.noProductChange(files, count), /Incomplete/);
  }
});

function pickWithoutSecret(files, { fork = false, draft = false, login = 'author', count = files.length, diff = '', ladderAnswer = '', ladderFailed = '' } = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-pick-'));
  try {
    const pr = { state: 'open', draft, user: { login }, changed_files: count, head: { sha, repo: { full_name: fork ? 'fork/repo' : 'a/b' } }, base: { ref: 'main' } };
    fs.writeFileSync(path.join(directory, 'diff.patch'), diff);
    fs.writeFileSync(path.join(directory, 'gh'), `#!${process.execPath}
const assert = require('node:assert/strict');
const fs = require('node:fs');
const args = process.argv.slice(2);
if (args.includes('Accept: application/vnd.github.diff')) {
  assert.deepEqual(args, ['api', 'repos/a/b/pulls/1', '-H', 'Accept: application/vnd.github.diff']);
  process.stdout.write(fs.readFileSync('${path.join(directory, 'diff.patch').replace(/\\/g, '\\\\')}', 'utf8'));
} else if (args.at(-1) === 'repos/a/b/pulls/1') {
  assert.deepEqual(args, ['api', 'repos/a/b/pulls/1']);
  console.log(${JSON.stringify(JSON.stringify(pr))});
} else if (args.at(-1).includes('/compare/')) {
  console.log(JSON.stringify({ merge_base_commit: { sha: '${sha}' } }));
} else {
  assert.deepEqual(args, ['api', '--paginate', '--slurp', 'repos/a/b/pulls/1/files?per_page=100']);
  console.log(${JSON.stringify(JSON.stringify([files]))});
}
`, { mode: 0o755 });
    const stateFile = path.join(directory, 'state.json');
    const answerFile = path.join(directory, 'answer.json');
    if (ladderAnswer) fs.writeFileSync(answerFile, JSON.stringify({ choices: [{ message: { content: ladderAnswer }, finish_reason: 'stop' }] }));
    const output = path.join(directory, 'output');
    const env = {
      PATH: `${directory}:${process.env.PATH}`, REPO: 'a/b', PR_NUMBER: '1', EVENT_NAME: 'pull_request', EVENT_HEAD_SHA: sha, GITHUB_OUTPUT: output,
      PICK_STATE_FILE: stateFile,
    };
    if (ladderFailed) env.LADDER_FAILED = ladderFailed;
    else if (ladderAnswer) env.LADDER_ANSWER_FILE = answerFile;
    const request = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'pick-request'], {
      env: { ...env, PICK_REQUEST_FILE: path.join(directory, 'request.json'), PICK_STATE_FILE: stateFile },
      encoding: 'utf8',
    });
    const recorded = fs.existsSync(output) ? fs.readFileSync(output, 'utf8') : '';
    if (request.status !== 0) return { ...request, output: recorded };
    if (!fs.existsSync(stateFile)) return { ...request, output: recorded };
    const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'pick'], { env, encoding: 'utf8' });
    return { ...run, output: fs.existsSync(output) ? fs.readFileSync(output, 'utf8') : recorded };
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

test('docs/tooling skips Android but retains exact base and non-UI classification for the judge', () => {
  for (const filename of ['README.md', 'tools/branch-protection.mjs']) {
    for (const options of [{}, { fork: true }, { draft: true }, { login: 'dependabot[bot]' }]) {
      const run = pickWithoutSecret([{ filename }], options);
      assert.equal(run.status, 0, run.stderr);
      assert.match(run.output, /^no_product_change=true$/m);
      assert.match(run.output, /^features=\[\]$/m);
      assert.match(run.output, /No product change/);
      assert.match(run.output, /No Android journey is scheduled/);
      assert.match(run.output, /^change_kind=tooling$/m);
      assert.match(run.output, new RegExp(`^merge_base=${sha}$`, 'm'));
    }
  }
});

test('a pull request never schedules the break-it replay', () => {
  const run = pickWithoutSecret([{ filename: 'README.md' }]);
  assert.equal(run.status, 0, run.stderr);
  assert.doesNotMatch(run.output, /^break_it=/m);
  assert.doesNotMatch(read('.github/scripts/pstack-verifier/verifier.cjs'), /BREAK_IT_TOOLS|break_it/);
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.doesNotMatch(jobBlock(workflow, 'pick'), /break_it/);
  assert.doesNotMatch(jobBlock(workflow, 'journeys'), /break_it/);
});

test('a product change is picked from the feature map when the ladder cannot answer', () => {
  const file = 'app/src/main/assets/chat_history_reader.py';
  const diff = `diff --git a/${file} b/${file}\n--- a/${file}\n+++ b/${file}\n@@ -1 +1 @@\n-old\n+hosted_claude_transcript_sid\n`;
  const cases = [
    { options: {}, ladderFailed: 'true' },
    { options: { draft: true }, ladderFailed: 'true' },
    { options: { login: 'dependabot[bot]' }, ladderFailed: 'true' },
  ];
  for (const { options, ladderFailed, ladderAnswer } of cases) {
    const run = pickWithoutSecret([{ filename: file }], { ...options, diff, ladderFailed, ladderAnswer });
    assert.equal(run.status, 0, run.stderr);
    assert.doesNotMatch(run.output, /no_product_change=true/);
    assert.match(run.output, /^change_kind=product$/m);
    assert.deepEqual(JSON.parse(run.output.match(/^features=(.+)$/m)[1]), ['host-restart.md', 'chat-codex.md']);
    assert.deepEqual(JSON.parse(run.output.match(/^changed_outcomes=(.+)$/m)[1]), ['reader.host-restart', 'codex.transcript']);
    assert.match(run.output, /^reason=AI picker unavailable; journeys chosen from the feature map\.$/m);
    assert.match(run.stderr, /pstack-verifier pick: AI picker unavailable \((?:ladder-call failed|the picker answer is not the expected JSON)\); using the feature map\./);
  }
  const fork = pickWithoutSecret([{ filename: 'app/src/main/App.kt' }], { fork: true });
  assert.equal(fork.status, 1);
  assert.match(fork.stderr, /Product changes from forks cannot be verified/);
  const incomplete = pickWithoutSecret([{ filename: 'README.md' }], { count: 2 });
  assert.equal(incomplete.status, 1);
  assert.match(incomplete.stderr, /Incomplete pull request file list/);
});

test('tooling and product changes both post a journey-based verdict', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.match(jobBlock(workflow, 'journeys'), /^ {4}if: needs\.pick\.outputs\.features != '\[\]'$/m);
  const final = jobBlock(workflow, 'pstack-verifier');
  assert.doesNotMatch(final, /if:.*no_product_change/);
  assert.match(final, /name: Post verifier verdict from journey results\n {8}id: judge\n {8}env:/);
  assert.doesNotMatch(final, /CLAUDE_CODE_OAUTH|DEEPSEEK_API_KEY/);
});

test('pick-request writes a ladder request file for product changes', () => {
  const file = 'app/src/main/assets/chat_history_reader.py';
  const diff = `diff --git a/${file} b/${file}\n--- a/${file}\n+++ b/${file}\n@@ -1 +1 @@\n-old\n+new\n`;
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-req-'));
  try {
    fs.writeFileSync(path.join(directory, 'diff.patch'), diff);
    fs.writeFileSync(path.join(directory, 'gh'), `#!${process.execPath}
const fs = require('node:fs');
const args = process.argv.slice(2);
if (args.includes('Accept: application/vnd.github.diff')) process.stdout.write(fs.readFileSync('${path.join(directory, 'diff.patch').replace(/\\/g, '\\\\')}', 'utf8'));
else if (args.at(-1).includes('/compare/')) console.log(JSON.stringify({ merge_base_commit: { sha: '${sha}' } }));
else if (args.at(-1) === 'repos/a/b/pulls/1') console.log(JSON.stringify({ number: 1, state: 'open', changed_files: 1, head: { sha: '${sha}', repo: { full_name: 'a/b' } }, base: { ref: 'main' }, title: 'T', body: '' }));
else console.log(JSON.stringify([{ filename: '${file}' }]));
`, { mode: 0o755 });
    const requestFile = path.join(directory, 'request.json');
    const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'pick-request'], {
      env: {
        PATH: `${directory}:${process.env.PATH}`, REPO: 'a/b', PR_NUMBER: '1', EVENT_NAME: 'pull_request', EVENT_HEAD_SHA: sha,
        PICK_REQUEST_FILE: requestFile, PICK_STATE_FILE: path.join(directory, 'state.json'),
      },
      encoding: 'utf8',
    });
    assert.equal(run.status, 0, run.stderr);
    const request = JSON.parse(fs.readFileSync(requestFile, 'utf8'));
    assert.deepEqual(request.response_format, { type: 'json_object' });
    assert.equal(request.max_tokens, 32000);
    assert.equal(request.model, undefined);
    assert.ok(request.messages[0].content.includes('journey picker'));
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
