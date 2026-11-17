const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const verifier = require('../.github/scripts/pstack-verifier/verifier.cjs');
const reliability = require('../.github/scripts/pstack-verifier/eval.cjs');
const expected = require('../.github/scripts/pstack-verifier/expected.json');
const cases = path.join(__dirname, '../.github/scripts/pstack-verifier/cases');
const read = label => JSON.parse(fs.readFileSync(path.join(cases, `${label}.json`), 'utf8'));
const answer = verdict => JSON.stringify({ verdict, summary: 'The retained evidence shows the result.', changed_outcomes: [], coverage: [] });

function scratch(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'multiprompt-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  return directory;
}

test('all-pass, all-fail, missing, malformed and gate-only answers cannot pass', () => {
  for (const verdict of ['PASS', 'FAIL']) {
    const results = Object.fromEntries(Object.keys(expected).map(label => [label, { rawVerdict: verdict, verdict }]));
    const rows = reliability.grade(results, expected);
    assert.equal(rows.filter(row => !row.matched).length, verdict === 'PASS' ? 4 : 2);
  }
  const results = Object.fromEntries(Object.entries(expected).map(([label, verdict]) => [label, { rawVerdict: verdict, verdict }]));
  assert.ok(reliability.grade(results, expected).every(row => row.matched));
  results.cedar.rawVerdict = 'PASS';
  assert.equal(reliability.grade(results, expected).find(row => row.label === 'cedar').matched, false);
  results.cedar = { error: 'Invalid response' };
  assert.equal(reliability.grade(results, expected).find(row => row.label === 'cedar').matched, false);
  delete results.cedar;
  assert.throws(() => reliability.grade(results, expected), /Missing/);
  assert.throws(() => reliability.grade({}, {}), /four planted/);
});

test('every case uses the production prompt with no answer or label in its context', async t => {
  const directory = scratch(t);
  assert.deepEqual(fs.readdirSync(cases).sort(), Object.keys(expected).map(label => `${label}.json`).sort());
  for (const label of Object.keys(expected)) {
    const sample = read(label);
    assert.equal(sample.expected, undefined);
    assert.match(sample.diff, /^diff --git /);
    await reliability.runCase(sample, {
      output: path.join(directory, label),
      ask: async request => {
        assert.equal(request.system, verifier.JUDGE_INSTRUCTIONS);
        assert.doesNotMatch(JSON.stringify(sample), /\b(?:eval|experiment|benchmark|candidate|arena|expected verdict|planted)\b/i);
        for (const name of Object.keys(expected)) assert.ok(!request.user.includes(name));
        assert.match(request.user, /PARENT \(app before the change/);
        assert.match(request.user, /HEAD \(app after the change/);
        assert.match(request.user, /side-result.json/);
        assert.ok(!fs.existsSync(path.join(request.cwd, 'expected.json')));
        return answer('FAIL');
      },
    });
    const request = JSON.parse(fs.readFileSync(path.join(directory, `${label}.request.json`), 'utf8'));
    assert.equal(request.system, verifier.JUDGE_INSTRUCTIONS);
  }
});

test('a missing structured citation turns a clean model PASS into a gate FAIL', async t => {
  const result = await reliability.runCase(read('aspen'), {
    ask: async () => answer('PASS'), output: path.join(scratch(t), 'aspen'),
  });
  assert.equal(result.rawVerdict, 'PASS');
  assert.equal(result.verdict, 'FAIL');
  assert.match(result.notes.join('\n'), /FEATURE_NOT_EXERCISED/);
});

test('a clean copy change passes the real coverage and verdict gates', async t => {
  const sample = read('aspen');
  const result = await reliability.runCase(sample, {
    output: path.join(scratch(t), 'aspen'),
    ask: async () => JSON.stringify({
      verdict: 'PASS', summary: 'Both chat IDs paste correctly.', changed_outcomes: ['chat.copy-id'],
      coverage: [{ outcome: 'chat.copy-id', journey: 'copy-chat-id.md',
        action: { file: 'driver-transcript.txt', quote: "+ tap_text 'Copy chat ID'" },
        result: { file: 'copy-result.json', quote: '"hostedComposer": "copy-chat-id-2371", "fallbackComposer": "verify-name"' },
      }],
    }),
  });
  assert.equal(result.rawVerdict, 'PASS');
  assert.equal(result.verdict, 'PASS');
  assert.equal(result.featureProof.accepted.length, 1);
});

test('invalid model output fails closed and removes the isolated context', async t => {
  let cwd;
  await assert.rejects(reliability.runCase(read('cedar'), {
    output: path.join(scratch(t), 'cedar'),
    ask: async request => { cwd = request.cwd; return 'not JSON'; },
  }), /not JSON/);
  assert.equal(fs.existsSync(cwd), false);
});

test('Claude sees only stdin evidence, the same system prompt and no tools or unrelated credentials', () => {
  let observed;
  const env = { PATH: '/bin', HOME: '/home/example', CLAUDE_CODE_OAUTH_TOKEN: 'private', DEEPSEEK_API_KEY: 'other', GH_TOKEN: 'github' };
  const response = reliability.claude({ system: 'system', user: 'evidence', cwd: '/tmp/multiprompt-x' }, env, (command, args, options) => {
    observed = { command, args, options };
    return answer('FAIL');
  });
  assert.equal(verifier.parseVerdict(response).verdict, 'FAIL');
  assert.equal(observed.command, 'claude');
  assert.equal(observed.args[observed.args.indexOf('--model') + 1], 'haiku');
  assert.equal(observed.args[observed.args.indexOf('--system-prompt') + 1], 'system');
  assert.equal(observed.args[observed.args.indexOf('--tools') + 1], '');
  assert.ok(observed.args.includes('--strict-mcp-config'));
  assert.equal(observed.options.input, 'evidence');
  assert.equal(observed.options.cwd, '/tmp/multiprompt-x');
  assert.equal(observed.options.env.DEEPSEEK_API_KEY, undefined);
  assert.equal(observed.options.env.GH_TOKEN, undefined);
  assert.throws(() => reliability.claude({}, {}), /missing/);
  assert.throws(() => reliability.claude({}, env, () => { throw new Error('secret'); }), error => !error.message.includes('secret'));
});
