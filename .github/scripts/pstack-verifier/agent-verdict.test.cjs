'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const { VERDICTS, parseAgentVerdict } = require('./agent-verdict.cjs');

for (const verdict of VERDICTS) {
  test(`accepts verdict ${verdict}`, () => {
    const parsed = parseAgentVerdict(JSON.stringify({
      verdict,
      summary: 'proved the journey',
      evidence: [{ file: 'composer-hosted-id.json', what: 'pasted host id matches recipe' }],
    }));
    assert.equal(parsed.verdict, verdict);
    assert.equal(parsed.evidence.length, 1);
  });
}

test('rejects unknown verdicts', () => {
  for (const verdict of ['PASS ', 'pass', 'FAIL_HARD', '', 'OK']) {
    assert.throws(() => parseAgentVerdict(JSON.stringify({ verdict, summary: 'x', evidence: [] })), /no valid verdict/);
  }
});

test('rejects missing summary and malformed evidence', () => {
  assert.throws(() => parseAgentVerdict(JSON.stringify({ verdict: 'PASS', evidence: [] })), /no summary/);
  assert.throws(
    () => parseAgentVerdict(JSON.stringify({ verdict: 'PASS', summary: 'ok', evidence: [{ file: 'a.png' }] })),
    /no what/,
  );
});