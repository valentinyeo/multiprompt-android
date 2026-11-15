const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const review = require('../.github/scripts/claude-review/claude-review.cjs');

const sha = 'a'.repeat(40);
const envelope = findings => JSON.stringify({
  structured_output: { verdict: 'APPROVE', summary: 'Read the diff.', findings },
  modelUsage: { 'claude-sonnet-5-5': {} },
});
const finding = (severity, confidence) => ({
  severity, confidence, file: 'app/A.kt', line: 3, title: 'Wrong branch', details: 'The check is inverted.', evidence: 'if (!ok)',
});

test('a high confidence major finding blocks even when the model says approve', () => {
  const parsed = review.parseClaudeOutput(envelope([finding('major', 'high')]));
  assert.equal(parsed.ok, true);
  assert.equal(parsed.verdict, 'CONCERNS');
  assert.equal(parsed.blocking, 1);
});

test('minor and low confidence findings never block', () => {
  const parsed = review.parseClaudeOutput(envelope([finding('minor', 'high'), finding('major', 'low')]));
  assert.equal(parsed.verdict, 'APPROVE');
  assert.equal(parsed.blocking, 0);
});

test('output that is not a review gives no verdict', () => {
  assert.equal(review.parseClaudeOutput('not json').ok, false);
  assert.equal(review.parseClaudeOutput(JSON.stringify({ is_error: true })).ok, false);
  assert.equal(review.parseClaudeOutput(JSON.stringify({ structured_output: { verdict: 'APPROVE', summary: 'x', findings: [] } })).ok, false);
});

test('a rerun reads the verdict back from the review comment of the same head', () => {
  const parsed = review.parseClaudeOutput(envelope([finding('blocker', 'high')]));
  const body = review.buildComment(sha, parsed);
  const bot = { user: { login: 'github-actions[bot]' }, body };
  assert.equal(review.priorVerdict([bot], sha), 'CONCERNS');
  assert.equal(review.priorVerdict([bot], 'b'.repeat(40)), null);
  assert.equal(review.priorVerdict([{ user: { login: 'someone' }, body }], sha), null);
});

test('review text cannot open HTML in the comment', () => {
  const parsed = review.parseClaudeOutput(envelope([{ ...finding('minor', 'high'), title: '<img src=x>' }]));
  assert.doesNotMatch(review.buildComment(sha, parsed).split('<!--')[0], /<img/);
});

test('the review workflow never runs pull request code with the token', () => {
  const workflow = readFileSync(path.join(__dirname, '../.github/workflows/claude-review.yml'), 'utf8');
  assert.match(workflow, /^ {2}workflow_run:$/m);
  assert.doesNotMatch(workflow, /^ {2}pull_request(?:_target)?:/m);
  assert.match(workflow, /^ {10}ref: main$/m);
  assert.match(workflow, /sparse-checkout: \.github\/scripts/);
  const request = readFileSync(path.join(__dirname, '../.github/workflows/claude-review-request.yml'), 'utf8');
  assert.doesNotMatch(request, /secrets\./);
});
