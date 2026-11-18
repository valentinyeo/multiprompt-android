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

test('when every journey passed the model verdict stands', () => {
  for (const modelVerdict of ['PASS', 'PASS+NOTES', 'FAIL']) {
    const ruled = verifier.applyRules({ modelVerdict, features: ['hosts.md'], conclusions: { 'hosts.md': 'success' } });
    assert.equal(ruled.verdict, modelVerdict);
  }
});

test('driving nothing caps the verdict at PASS+NOTES and says why', () => {
  const ruled = verifier.applyRules({ modelVerdict: 'PASS', features: [], conclusions: {}, notDrivenReason: 'Only documentation changed.' });
  assert.equal(ruled.verdict, 'PASS+NOTES');
  assert.match(ruled.notes.join(' '), /Nothing was driven/);
  assert.match(ruled.notes.join(' '), /Only documentation changed\./);
  assert.equal(verifier.applyRules({ modelVerdict: 'PASS+NOTES', features: [], conclusions: {} }).verdict, 'PASS+NOTES');
  assert.equal(verifier.applyRules({ modelVerdict: 'FAIL', features: [], conclusions: {} }).verdict, 'FAIL');
});

test('model output that does not parse gives no verdict', () => {
  for (const bad of ['not json', '', '[]', '{}', '{"verdict":"MAYBE","summary":"x"}', '{"verdict":"PASS"}', '{"verdict":"PASS","summary":"  "}', '"PASS"']) {
    assert.throws(() => verifier.parseVerdict(bad), undefined, bad);
  }
  assert.throws(() => verifier.parsePick('not json'));
  assert.throws(() => verifier.parsePick('{"reason":"x"}'));
});

test('a well formed model answer parses, caps its lists and ignores extra keys', () => {
  const evidence = Array.from({ length: 12 }, (_, index) => `line ${index}`);
  const parsed = verifier.parseVerdict(JSON.stringify({ verdict: 'PASS+NOTES', summary: 'It works.', evidence, not_covered: ['dictation'], other: 1 }));
  assert.equal(parsed.verdict, 'PASS+NOTES');
  assert.equal(parsed.evidence.length, 8);
  assert.deepEqual(parsed.notCovered, ['dictation']);
  const fenced = verifier.parseVerdict('```json\n{"verdict":"PASS","summary":"ok","evidence":[],"not_covered":[]}\n```');
  assert.equal(fenced.verdict, 'PASS');
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

test('unknown journey names are dropped and at most three run', () => {
  const picked = verifier.parsePick(JSON.stringify({
    features: ['hosts.md', 'made-up.md', 'settings-navigation.md', '../etc/passwd', 'hosts.md', 'terminal.md', 'sessions.md', 'chat-cancel.md'],
    reason: 'x',
    changed_outcomes: [],
    change_kind: 'tooling',
  }));
  assert.deepEqual(picked.features, ['hosts.md', 'terminal.md', 'sessions.md']);
  assert.ok(picked.dropped.includes('made-up.md'));
  assert.ok(picked.dropped.includes('settings-navigation.md'));
  assert.ok(picked.dropped.includes('chat-cancel.md'));
  assert.deepEqual(verifier.validateFeatures('hosts.md').features, []);
  assert.deepEqual(verifier.validateFeatures(null).features, []);
});

test('the three picked journeys are shared by head and parent, not picked twice', () => {
  assert.equal(verifier.MAX_JOURNEYS, 3);
  const picked = verifier.parsePick(JSON.stringify({
    features: verifier.ALLOWLIST,
    changed_outcomes: [],
    change_kind: 'tooling',
    reason: 'Exercise the cap.',
  }));
  assert.equal(picked.features.length, 3);
  const workflow = read('.github/workflows/pstack-verifier.yml');
  for (const [job, side, ref] of [['journeys', 'head', 'head_sha'], ['parents', 'parent', 'merge_base']]) {
    const block = jobBlock(workflow, job);
    assert.match(block, /^ {4}needs: pick$/m);
    assert.ok(block.includes('        feature: ${{ fromJSON(needs.pick.outputs.features) }}'));
    assert.ok(block.includes('      feature: ${{ matrix.feature }}'));
    assert.ok(block.includes(`      side: ${side}`));
    assert.ok(block.includes(`      app_ref: \${{ needs.pick.outputs.${ref} }}`));
    assert.doesNotMatch(block, /^ {8}(?:include|side):/m);
  }
});

test('required outcome journeys cannot be omitted by the model picker', () => {
  const picked = { features: ['chat-shared.md'], dropped: [], smoke: false, reason: 'chat recovery' };
  const selected = verifier.includeRequiredJourneys(picked, { outcomes: ['reader.send-enter-interrupt', 'chat.link-recovery'] });
  assert.deepEqual(selected.features, ['session-reader.md', 'chat-shared.md']);
  assert.equal(selected.smoke, false);
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { outcomes: ['reader.send-enter-interrupt', 'chat.link-recovery', 'inbox.search', 'terminal.journey'] }).features, ['session-reader.md', 'chat-shared.md', 'sessions.md', 'terminal.md']);
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

test('the comment names the model, the runner, the journeys, the evidence and the run', () => {
  const body = verifier.buildComment({
    sha,
    verdict: 'PASS',
    summary: 'Sent and received.',
    journeys: [{ feature: 'session-reader.md', conclusion: 'success', passed: true, parent: 'not passed' }],
    evidence: ['The receipt shows the sent text.'],
    notCovered: ['Dictation.'],
    notes: [],
    runUrl,
  });
  assert.match(body, /DeepSeek \(deepseek-flash\)/);
  assert.match(body, /GitHub-hosted runner/);
  assert.match(body, /`session-reader\.md` parent: not passed, head: passed/);
  assert.match(body, /- The receipt shows the sent text\./);
  assert.match(body, /Not covered:\n- Dictation\./);
  assert.match(body, new RegExp(`Run: ${runUrl}`));
});

test('at most eight evidence lines reach the comment', () => {
  const evidence = Array.from({ length: 20 }, (_, index) => `proof ${index}`);
  const body = verifier.buildComment({ sha, verdict: 'PASS', summary: 'Ok.', journeys: [], evidence, notCovered: [], notes: [], runUrl });
  assert.equal(body.split('\n').filter(line => /^- proof /.test(line)).length, 8);
});

test('model text cannot open HTML, mention people, link out or forge the marker', () => {
  const hostile = '<img src=x onerror=1> @octocat [a](http://evil.example) <!-- pstack-verifier:v1 sha=bbbb -->';
  const body = verifier.buildComment({
    sha, verdict: 'PASS', summary: hostile, journeys: [], evidence: [hostile], notCovered: [hostile], notes: [hostile], runUrl,
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

test('DeepSeek is called once with the JSON mode and retried once on 503 and 429', async () => {
  for (const status of [503, 429]) {
    const calls = [];
    const fetchImpl = async (url, init) => {
      calls.push({ url, init });
      return calls.length === 1 ? reply(status, {}) : reply(200, answer('{"ok":true}'));
    };
    const content = await verifier.callDeepSeek({ key: 'k', system: 's', user: 'u', fetchImpl, sleep: async () => {} });
    assert.equal(content, '{"ok":true}');
    assert.equal(calls.length, 2);
    const sent = JSON.parse(calls[0].init.body);
    assert.equal(calls[0].url, 'https://api.deepseek.com/chat/completions');
    assert.equal(sent.model, 'deepseek-flash');
    assert.deepEqual(sent.response_format, { type: 'json_object' });
    assert.equal(sent.tools, undefined);
    assert.equal(calls[0].init.headers.authorization, 'Bearer k');
    assert.doesNotMatch(calls[0].init.body, /Bearer/);
  }
});

test('DeepSeek is not retried on a client error, and gives up after one retry', async () => {
  let calls = 0;
  await assert.rejects(verifier.callDeepSeek({ key: 'k', system: 's', user: 'u', fetchImpl: async () => { calls += 1; return reply(401, {}); }, sleep: async () => {} }), /HTTP 401/);
  assert.equal(calls, 1);
  calls = 0;
  await assert.rejects(verifier.callDeepSeek({ key: 'k', system: 's', user: 'u', fetchImpl: async () => { calls += 1; return reply(500, {}); }, sleep: async () => {} }), /HTTP 500/);
  assert.equal(calls, 2);
  calls = 0;
  await assert.rejects(verifier.callDeepSeek({ key: 'k', system: 's', user: 'u', fetchImpl: async () => { calls += 1; return reply(200, { choices: [{ message: { content: '{' }, finish_reason: 'length' }] }); }, sleep: async () => {} }), /cut the answer off/);
  assert.equal(calls, 2);
  calls = 0;
  const recovered = await verifier.callDeepSeek({ key: 'k', system: 's', user: 'u', fetchImpl: async () => { calls += 1; return calls === 1 ? reply(200, { choices: [{ message: { content: '{' }, finish_reason: 'length' }] }) : reply(200, { choices: [{ message: { content: '{"ok":true}' }, finish_reason: 'stop' }] }); }, sleep: async () => {} });
  assert.equal(recovered, '{"ok":true}');
  assert.equal(calls, 2);
});

test('the judge stops with a clear error when the secret is empty', () => {
  const env = { PATH: process.env.PATH, DEEPSEEK_API_KEY: '', PICK_RESULT: 'success', PR_NUMBER: '1', REPO: 'a/b' };
  const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'judge'], { env, encoding: 'utf8' });
  assert.equal(run.status, 1);
  assert.match(run.stderr, /DEEPSEEK_API_KEY is empty or missing/);
});

test('a picker that did not finish gives no verdict', () => {
  const env = { PATH: process.env.PATH, DEEPSEEK_API_KEY: 'k', PICK_RESULT: 'failure' };
  const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'judge'], { env, encoding: 'utf8' });
  assert.notEqual(run.status, 0);
  assert.match(run.stderr, /no verdict/);
});

test('every allowlisted journey is a dispatch option of verify.yml and has a map', () => {
  const options = dispatchFeatureOptions(read('.github/workflows/verify.yml'));
  assert.ok(options.length > 10);
  assert.equal(new Set(verifier.ALLOWLIST).size, verifier.ALLOWLIST.length);
  for (const name of verifier.ALLOWLIST) {
    assert.ok(options.includes(name), `${name} is a feature option in verify.yml`);
    assert.ok(fs.existsSync(path.join(root, '.claude/skills/verify-multiprompt-android/features', name)), `${name} has a journey map`);
  }
});

test('journeys that need a signed APK or release inputs are not allowlisted', () => {
  for (const name of ['settings-navigation.md', 'session-gestures.md', 'session-favorites.md', 'robot-faces.md']) {
    assert.ok(!verifier.ALLOWLIST.includes(name), name);
  }
});

test('the journey catalog describes each allowlisted journey from the feature map', () => {
  const catalog = verifier.journeyCatalog(root);
  for (const name of verifier.ALLOWLIST) assert.match(catalog, new RegExp(`^- ${name.replace('.', '\\.')}: .+`, 'm'));
  assert.doesNotMatch(catalog, /no description/);
});

test('the journeys job runs verify.yml with no secrets and no key', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const journeys = jobBlock(workflow, 'journeys');
  assert.match(journeys, /^ {4}uses: \.\/\.github\/workflows\/verify\.yml$/m);
  assert.match(journeys, /^ {6}feature: \$\{\{ matrix\.feature \}\}$/m);
  assert.match(journeys, /^ {6}side: head$/m);
  assert.match(journeys, /^ {6}app_ref: \$\{\{ needs\.pick\.outputs\.head_sha \}\}$/m);
  assert.match(journeys, /fail-fast: false/);
  assert.doesNotMatch(journeys, /secrets/i);
  assert.doesNotMatch(journeys, /DEEPSEEK/);
  assert.doesNotMatch(workflow, /secrets:\s*inherit/);
});

test('the DeepSeek key reaches only the pick and judge steps, as env', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const uses = workflow.match(/secrets\.[A-Z_]+/g) ?? [];
  assert.deepEqual(uses, ['secrets.DEEPSEEK_API_KEY', 'secrets.DEEPSEEK_API_KEY']);
  for (const job of ['pick', 'pstack-verifier']) {
    assert.match(jobBlock(workflow, job), /^ {10}DEEPSEEK_API_KEY: \$\{\{ secrets\.DEEPSEEK_API_KEY \}\}$/m, job);
  }
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
  assert.match(jobBlock(workflow, 'pstack-verifier'), /needs: \[pick, journeys, parents\]/);
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
  for (const job of ['journeys', 'parents']) {
    const granted = jobBlock(read('.github/workflows/pstack-verifier.yml'), job);
    for (const permission of asked) assert.ok(granted.includes(permission), `${job} grants ${permission}`);
  }
  const caller = topBlock(read('.github/workflows/pstack-verifier.yml'), 'on').join('\n');
  const inputs = [...caller.matchAll(/^ {6}(\w+):$/gm)].map(match => match[1]);
  assert.deepEqual(inputs, ['pr']);
});

test('verify.yml keeps non-PR triggers and delegates PR journeys to the capped picker', () => {
  const verify = read('.github/workflows/verify.yml');
  const triggers = topBlock(verify, 'on').join('\n');
  for (const trigger of ['push', 'schedule', 'workflow_dispatch', 'workflow_call']) {
    assert.match(triggers, new RegExp(`^ {2}${trigger}:`, 'm'), trigger);
  }
  assert.doesNotMatch(triggers, /^ {2}pull_request:/m);
  const call = triggers.slice(triggers.indexOf('  workflow_call:'), triggers.indexOf('  workflow_dispatch:'));
  assert.match(call, /^ {6}feature:\n {8}description: .+\n {8}required: true\n {8}type: string\n/m);
  assert.deepEqual([...call.matchAll(/^ {6}(\w+):$/gm)].map(match => match[1]), ['released_chat', 'released_robots', 'release_choices', 'pins_scroll', 'candidate', 'break_it', 'feature', 'side', 'app_ref']);
  assert.match(call, /^ {6}side:\n {8}description: .+\n {8}required: false\n {8}type: string\n/m);
  assert.match(call, /^ {6}app_ref:\n {8}description: .+\n {8}required: false\n {8}type: string\n$/m);
  assert.match(verify, /^ {2}group: verify-\$\{\{ github\.ref \}\}\$\{\{ inputs\.feature && inputs\.feature != github\.event\.inputs\.feature && format\('-\{0\}', inputs\.feature\) \|\| '' \}\}\$\{\{ inputs\.side && format\('-\{0\}', inputs\.side\) \|\| '' \}\}-\$\{\{ inputs\.break_it_control \|\| 'none' \}\}$/m);
});

test('a change no journey fits still drives the smoke journey and never gets a plain PASS', () => {
  const picked = verifier.parsePick(JSON.stringify({ features: ['not-a-journey.md'], changed_outcomes: [], change_kind: 'tooling', reason: 'Only workflow files changed.' }));
  assert.deepEqual(picked.features, [verifier.SMOKE_JOURNEY]);
  assert.equal(picked.smoke, true);
  assert.ok(verifier.ALLOWLIST.includes(verifier.SMOKE_JOURNEY));
  const passed = verifier.applyRules({ modelVerdict: 'PASS', features: picked.features, conclusions: { [verifier.SMOKE_JOURNEY]: 'success' }, notDrivenReason: picked.reason, smoke: true });
  assert.equal(passed.verdict, 'PASS+NOTES');
  assert.match(passed.notes.join(' '), /Only the smoke journey was driven/);
  const broken = verifier.applyRules({ modelVerdict: 'PASS', features: picked.features, conclusions: { [verifier.SMOKE_JOURNEY]: 'failure' }, parents: { [verifier.SMOKE_JOURNEY]: 'passed' }, smoke: true });
  assert.equal(broken.verdict, 'FAIL');
});

function stepBlocks(source) {
  const verify = jobBlock(source, 'verify');
  const body = verify.slice(verify.indexOf('\n    steps:\n') + '\n    steps:\n'.length);
  return body.split(/^ {6}- /m).slice(1).map(block => `- ${block}`);
}

test('the model is a flash model, never a pro one', () => {
  assert.equal(verifier.MODEL, 'deepseek-flash');
  assert.doesNotMatch(verifier.MODEL, /pro/i);
  assert.doesNotMatch(read('.github/scripts/pstack-verifier/verifier.cjs'), /v4-pro/);
});

test('a feature parent result never changes the verdict', () => {
  for (const parent of ['not passed', 'passed', 'no result', 'product assertion']) {
    for (const modelVerdict of ['PASS', 'PASS+NOTES', 'FAIL']) {
      const ruled = verifier.applyRules({ modelVerdict, features: ['hosts.md'], conclusions: { 'hosts.md': 'success' }, parents: { 'hosts.md': parent } });
      assert.equal(ruled.verdict, modelVerdict, `${parent} ${modelVerdict}`);
      assert.equal(ruled.journeys[0].parent, parent);
    }
  }
});

test('a bug fix can pass when the parent failed a product assertion and head plus stress passed', () => {
  const proven = verifier.applyRules({
    modelVerdict: 'PASS',
    bugFix: true,
    stressProof: { errors: [], evidence: [] },
    features: ['hosts.md'],
    conclusions: { 'hosts.md': 'success' },
    parents: { 'hosts.md': 'product assertion' },
  });
  assert.equal(proven.verdict, 'PASS');
  assert.equal(verifier.applyRules({
    modelVerdict: 'FAIL',
    bugFix: true,
    stressProof: { errors: [], evidence: [] },
    features: ['hosts.md'],
    conclusions: { 'hosts.md': 'success' },
    parents: { 'hosts.md': 'product assertion' },
  }).verdict, 'FAIL');
  assert.equal(verifier.applyRules({
    modelVerdict: 'PASS',
    bugFix: true,
    stressProof: { errors: [], evidence: [] },
    features: ['hosts.md'],
    conclusions: { 'hosts.md': 'failure' },
    parents: { 'hosts.md': 'product assertion' },
  }).verdict, 'FAIL');
});

test('a bug fix fails when the parent passed', () => {
  const ruled = verifier.applyRules({
    modelVerdict: 'PASS',
    bugFix: true,
    features: ['hosts.md', 'terminal.md'],
    conclusions: { 'hosts.md': 'success', 'terminal.md': 'success' },
    parents: { 'hosts.md': 'product assertion', 'terminal.md': 'passed' },
  });
  assert.equal(ruled.verdict, 'FAIL');
  assert.match(ruled.notes.join(' '), /terminal\.md parent passed/);
  assert.match(ruled.notes.join(' '), /not a repro/);
});

test('a bug fix fails when the parent failed in setup rather than a product assertion', () => {
  for (const parent of ['not passed', 'no result']) {
    const ruled = verifier.applyRules({
      modelVerdict: 'PASS',
      bugFix: true,
      features: ['hosts.md'],
      conclusions: { 'hosts.md': 'success' },
      parents: { 'hosts.md': parent },
    });
    assert.equal(ruled.verdict, 'FAIL', parent);
    assert.match(ruled.notes.join(' '), /setup, build or fixture/);
  }
  assert.equal(verifier.applyRules({ modelVerdict: 'PASS', bugFix: true, features: [], conclusions: {} }).verdict, 'FAIL');
});

test('a feature is unaffected by the bug-fix repro rule', () => {
  const ruled = verifier.applyRules({
    modelVerdict: 'PASS',
    bugFix: false,
    features: ['hosts.md'],
    conclusions: { 'hosts.md': 'success' },
    parents: { 'hosts.md': 'passed' },
  });
  assert.equal(ruled.verdict, 'PASS');
  assert.deepEqual(ruled.notes, []);
  const title = 'Add inbox folders';
  assert.equal(verifier.isBugFix({ title, changeKind: 'product', tickets: { 'IGSH-1': 'Add inbox folders' } }), false);
  assert.equal(verifier.isBugFix({ title, changeKind: 'bug' }), true);
});

test('a head journey that failed still forces FAIL whatever the parent did', () => {
  for (const parent of ['passed', 'not passed', 'no result']) {
    const ruled = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'failure' }, parents: { 'hosts.md': parent } });
    assert.equal(ruled.verdict, 'FAIL', parent);
  }
});

test('a missing parent result adds a pipeline note and nothing else', () => {
  const missing = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md', 'terminal.md'], conclusions: { 'hosts.md': 'success', 'terminal.md': 'success' }, parents: { 'hosts.md': 'passed' } });
  assert.equal(missing.verdict, 'PASS');
  assert.match(missing.notes.join(' '), /No parent result for terminal\.md/);
  assert.doesNotMatch(missing.notes.join(' '), /hosts\.md/);
  const present = verifier.applyRules({ modelVerdict: 'PASS', features: ['hosts.md'], conclusions: { 'hosts.md': 'success' }, parents: { 'hosts.md': 'not passed' } });
  assert.deepEqual(present.notes, []);
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

test('the comment lists the parent and the head result of every journey', () => {
  const body = verifier.buildComment({
    sha,
    verdict: 'PASS',
    summary: 'Fixed.',
    journeys: [
      { feature: 'hosts.md', conclusion: 'success', passed: true, parent: 'not passed' },
      { feature: 'terminal.md', conclusion: 'failure', passed: false, parent: 'passed' },
      { feature: 'sessions.md', conclusion: null, passed: false, parent: 'no result' },
    ],
    evidence: [],
    notCovered: [],
    notes: [],
    runUrl,
  });
  assert.match(body, /^- `hosts\.md` parent: not passed, head: passed$/m);
  assert.match(body, /^- `terminal\.md` parent: passed, head: not passed \(failure\)$/m);
  assert.match(body, /^- `sessions\.md` parent: no result, head: no result$/m);
  assert.equal(body.split('\n')[0], `pstack-verifier: PASS @ ${sha}`);
});

test('parent job names and artifact names never collide with the head side', () => {
  const jobs = [
    { name: 'journey hosts.md / verify', status: 'completed', conclusion: 'success' },
    { name: 'parent journey hosts.md / verify', status: 'completed', conclusion: 'success' },
    { name: 'parent journey terminal.md / verify', status: 'completed', conclusion: 'cancelled' },
  ];
  assert.deepEqual(verifier.jobConclusions(jobs, ['hosts.md', 'terminal.md']), { 'hosts.md': 'success', 'terminal.md': null });
  assert.deepEqual(verifier.jobConclusions(jobs, ['hosts.md', 'terminal.md'], 'parent'), { 'hosts.md': 'success', 'terminal.md': 'cancelled' });
  assert.equal(verifier.artifactName('hosts.md', '9', 'head'), 'verify-hosts.md-9');
  assert.equal(verifier.artifactName('hosts.md', '9', 'parent'), 'verify-hosts.md-9-parent');
  const verify = read('.github/workflows/verify.yml');
  assert.match(verify, /name: verify-\$\{\{ env\.VERIFY_FEATURE \}\}-\$\{\{ github\.run_id \}\}\$\{\{ inputs\.side == 'parent' && '-parent' \|\| '' \}\}\$\{\{ inputs\.side && inputs\.side != 'head' && inputs\.side != 'parent' && format\('-\{0\}', inputs\.side\) \|\| '' \}\}\$\{\{ inputs\.native_skills_runs == '10' && format\('-sample-\{0\}', matrix\.sample\) \|\| '' \}\}$/m);
  assert.match(verify, /stage: stage-1\$\{\{ inputs\.side && format\('-\{0\}-\{1\}', inputs\.side, inputs\.feature\) \|\| '' \}\}\$\{\{ inputs\.native_skills_runs == '10' && format\('-sample-\{0\}', matrix\.sample\) \|\| '' \}\}$/m);
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
  const pick = jobBlock(read('.github/workflows/pstack-verifier.yml'), 'pick');
  assert.match(pick, /merge_base: \$\{\{ steps\.pick\.outputs\.merge_base \}\}/);
});

test('the parents job drives the merge base with the head journey scripts and no secrets', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  const parents = jobBlock(workflow, 'parents');
  assert.match(parents, /^ {4}name: parent journey \$\{\{ matrix\.feature \}\}$/m);
  assert.match(parents, /^ {4}uses: \.\/\.github\/workflows\/verify\.yml$/m);
  assert.match(parents, /^ {6}feature: \$\{\{ matrix\.feature \}\}$/m);
  assert.match(parents, /^ {6}side: parent$/m);
  assert.match(parents, /^ {6}app_ref: \$\{\{ needs\.pick\.outputs\.merge_base \}\}$/m);
  assert.match(parents, /fail-fast: false/);
  assert.match(parents, /^ {4}if: needs\.pick\.outputs\.no_product_change != 'true'$/m);
  assert.doesNotMatch(parents, /secrets/i);
  assert.doesNotMatch(parents, /DEEPSEEK/);
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
    if (condition.startsWith('always()')) continue;
    assert.match(step, /^ {8}id: \S+$/m, name);
  }
});

test('the parent result is written into the artifact by an always step before the upload', () => {
  const verify = read('.github/workflows/verify.yml');
  const steps = stepBlocks(verify);
  const record = steps.findIndex(step => /Record the parent result for the judge/.test(step));
  const upload = steps.findIndex(step => /actions\/upload-artifact/.test(step));
  assert.ok(record > 0 && upload > record);
  assert.match(steps[record], /if: always\(\) && inputs\.side == 'parent'/);
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
  assert.match(jobBlock(workflow, 'pick'), /change_kind: \$\{\{ steps\.pick\.outputs\.change_kind \}\}/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /CHANGE_KIND: \$\{\{ needs\.pick\.outputs\.change_kind \}\}/);
  assert.match(jobBlock(workflow, 'pick'), /tools\/release-notes\/ticket-titles\.json/);
  assert.match(jobBlock(workflow, 'pick'), /\.claude\/skills\/verify-multiprompt-android\/scripts\/drive\.sh/);
  assert.match(jobBlock(workflow, 'pstack-verifier'), /tools\/release-notes\/ticket-titles\.json/);
});

test('verify.yml pins the parent app source to a full sha and takes it through app_ref', () => {
  const verify = read('.github/workflows/verify.yml');
  assert.match(verify, /^ {6}APP_REF: \$\{\{ inputs\.app_ref \}\}$/m);
  assert.match(verify, /SIDE: \$\{\{ inputs\.side \}\}/);
  assert.match(verify, /if \[\[ "\$SIDE" == parent \]\]; then\n\s+\[\[ "\$APP_REF" =~ \^\[0-9a-f\]\{40\}\$ \]\]/);
  const checkout = stepBlocks(verify).find(step => /id: app-checkout/.test(step));
  assert.match(checkout, /if: env\.APP_REF != ''/);
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

test('SOFT-2536 schedules all five required journeys beyond the suggestion limit', () => {
  const outcomes = ['sign-in.journey', 'host-changed-key.journey', 'chat-reactions.journey', 'claude.skills-model-effort', 'inbox.search'];
  const plan = { product: true, outcomes };
  const picked = { features: ['sign-in.md', 'host-changed-key.md', 'chat-reactions.md'], smoke: false };
  const selected = verifier.includeRequiredJourneys(picked, plan);
  const expected = [...picked.features, 'native-skills.md', 'sessions.md'];
  assert.deepEqual(selected.features, expected);
  assert.equal(selected.smoke, false);
  assert.deepEqual(verifier.validateFeatures(selected.features, Infinity), { features: expected, dropped: [] });
  const judge = read('.github/scripts/pstack-verifier/verifier.cjs').split('async function judge(')[1];
  assert.match(judge, /validateFeatures\(requested, Infinity\)/);
});

test('required journeys replace a smoke fallback without inventing coverage for manual gaps', () => {
  const picked = { features: [verifier.SMOKE_JOURNEY], smoke: true };
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { outcomes: ['sign-in.journey'] }).features, ['sign-in.md']);
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { outcomes: ['session-settings.journey'] }), picked);
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
  assert.match(build, /gradle -p "\$app_source"/);
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

function pickWithoutSecret(files, { fork = false, draft = false, login = 'author', count = files.length } = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pstack-pick-'));
  try {
    const pr = { state: 'open', draft, user: { login }, changed_files: count, head: { sha, repo: { full_name: fork ? 'fork/repo' : 'a/b' } }, base: { ref: 'main' } };
    fs.writeFileSync(path.join(directory, 'gh'), `#!${process.execPath}
const assert = require('node:assert/strict');
const args = process.argv.slice(2);
if (args.at(-1) === 'repos/a/b/pulls/1') {
  assert.deepEqual(args, ['api', 'repos/a/b/pulls/1']);
  console.log(${JSON.stringify(JSON.stringify(pr))});
} else {
  assert.deepEqual(args, ['api', '--paginate', '--slurp', 'repos/a/b/pulls/1/files?per_page=100']);
  console.log(${JSON.stringify(JSON.stringify([files]))});
}
`, { mode: 0o755 });
    const output = path.join(directory, 'output');
    const run = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'pick'], {
      env: { PATH: `${directory}:${process.env.PATH}`, REPO: 'a/b', PR_NUMBER: '1', EVENT_NAME: 'pull_request', EVENT_HEAD_SHA: sha, GITHUB_OUTPUT: output, DEEPSEEK_API_KEY: '' },
      encoding: 'utf8',
    });
    return { ...run, output: fs.existsSync(output) ? fs.readFileSync(output, 'utf8') : '' };
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

test('docs/tooling PRs, including forks, drafts and dependabot, need no secret or Android journey', () => {
  for (const filename of ['README.md', 'tools/branch-protection.mjs']) {
    for (const options of [{}, { fork: true }, { draft: true }, { login: 'dependabot[bot]' }]) {
      const run = pickWithoutSecret([{ filename }], options);
      assert.equal(run.status, 0, run.stderr);
      assert.match(run.output, /^no_product_change=true$/m);
      assert.match(run.output, /^features=\[\]$/m);
      assert.match(run.output, /No product change/);
      assert.match(run.output, /No Android journey or second-AI product verdict was run/);
    }
  }
});

test('product changes still require the secret, even in drafts and dependabot PRs', () => {
  for (const options of [{}, { draft: true }, { login: 'dependabot[bot]' }]) {
    const run = pickWithoutSecret([{ filename: 'app/src/main/App.kt' }], options);
    assert.equal(run.status, 1);
    assert.match(run.stderr, /DEEPSEEK_API_KEY is empty or missing/);
    assert.doesNotMatch(run.output, /no_product_change=true/);
  }
  const fork = pickWithoutSecret([{ filename: 'app/src/main/App.kt' }], { fork: true });
  assert.equal(fork.status, 1);
  assert.match(fork.stderr, /Product changes from forks cannot be verified/);
  const incomplete = pickWithoutSecret([{ filename: 'README.md' }], { count: 2 });
  assert.equal(incomplete.status, 1);
  assert.match(incomplete.stderr, /Incomplete pull request file list/);
});

test('the no-product result writes an honest summary while product changes still run the judge', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  for (const job of ['journeys', 'parents']) {
    assert.match(jobBlock(workflow, job), /^ {4}if: needs\.pick\.outputs\.no_product_change != 'true'$/m);
  }
  const final = jobBlock(workflow, 'pstack-verifier');
  assert.match(final, /name: Report no product change\n {8}if: needs\.pick\.result == 'success' && needs\.pick\.outputs\.no_product_change == 'true'/);
  assert.match(final, /REASON: \$\{\{ needs\.pick\.outputs\.reason \}\}/);
  assert.match(final, /run: printf '%s\\n' "\$REASON" >> "\$GITHUB_STEP_SUMMARY"/);
  assert.match(final, /name: Judge the change against the journey evidence\n {8}if: needs\.pick\.outputs\.no_product_change != 'true'/);
});
