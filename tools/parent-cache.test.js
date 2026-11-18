const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { cacheKey, reusable, restore } = require('../.github/scripts/pstack-verifier/parent-cache.cjs');

const sha = 'a'.repeat(40);
const key = 'verify-base-test';

function fixture(work) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'parent-cache-'));
  const directory = path.join(root, 'evidence');
  const evidence = (source = sha, result = { passed: true }) => {
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), source);
    fs.writeFileSync(path.join(directory, 'side-result.json'), JSON.stringify(result));
  };
  try { return work({ root, directory, evidence }); }
  finally { fs.rmSync(root, { recursive: true, force: true }); }
}

test('cache keys bind the base SHA, journey and complete driver inputs, not ticket metadata', () => fixture(({ root }) => {
  const file = (name, content) => {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), content);
  };
  const git = (...args) => execFileSync('git', args, { cwd: root, stdio: 'pipe' });
  git('init');
  file('tools/runtime.sh', 'first');
  file('tools/release-notes/ticket-titles.json', '{}');
  file('.claude/skills/verify-multiprompt-android/scripts/drive.sh', 'driver');
  git('add', '.');
  const first = cacheKey(sha, 'hosts.md', root);
  assert.equal(first, cacheKey(sha, 'hosts.md', root));
  assert.notEqual(first, cacheKey('b'.repeat(40), 'hosts.md', root));
  assert.notEqual(first, cacheKey(sha, 'terminal.md', root));
  file('tools/release-notes/ticket-titles.json', '{"IGSH-2687":"title"}');
  assert.equal(first, cacheKey(sha, 'hosts.md', root));
  file('tools/runtime.sh', 'changed');
  assert.notEqual(first, cacheKey(sha, 'hosts.md', root));
  assert.throws(() => cacheKey('', 'hosts.md', root));
  assert.throws(() => cacheKey(sha, '../hosts.md', root));
}));

test('only exact-SHA passed or product-assertion base results are reusable', () => fixture(({ directory, evidence }) => {
  assert.equal(reusable(directory, sha), false);
  evidence();
  assert.equal(reusable(directory, sha), true);
  evidence('b'.repeat(40));
  assert.equal(reusable(directory, sha), false);
  evidence(sha, { passed: false });
  assert.equal(reusable(directory, sha), false);
  evidence(sha, { passed: false, productAssertion: true });
  assert.equal(reusable(directory, sha), true);
}));

test('another PR can reuse completed same-repository base proof without invoking Android', () => fixture(({ directory, evidence }) => {
  const calls = [];
  const run = args => {
    calls.push(args);
    if (args[0] === 'run') { evidence(); return ''; }
    if (args.at(-1).includes('/actions/runs/')) return JSON.stringify({ status: 'completed', path: '.github/workflows/pstack-verifier.yml', html_url: 'https://example/run/9' });
    return JSON.stringify([{ artifacts: [{ id: 8, workflow_run: { id: 9, repository_id: 1, head_repository_id: 1 } }] }]);
  };
  assert.equal(restore({ repo: 'a/b', sha, feature: 'hosts.md', directory, runId: '10', key, run }), true);
  assert.ok(calls.some(args => args.join(' ') === `run download 9 -R a/b -n ${key} -D ${directory}`));
  assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'base-cache-origin.json'))).artifact, 8);
}));

test('expired, fork, in-flight and invalid artifacts fall back to a fresh base run', () => fixture(({ directory, evidence }) => {
  const artifacts = [
    { expired: true, workflow_run: { id: 1, repository_id: 1, head_repository_id: 1 } },
    { workflow_run: { id: 2, repository_id: 1, head_repository_id: 2 } },
    { workflow_run: { id: 10, repository_id: 1, head_repository_id: 1 } },
    { workflow_run: { id: 3, repository_id: 1, head_repository_id: 1 } },
  ];
  for (const state of ['in_progress', 'completed']) {
    const run = args => {
      if (args[0] === 'run') { evidence(sha, { passed: false }); return ''; }
      if (args.at(-1).includes('/actions/runs/')) {
        assert.ok(args.at(-1).endsWith('/3'));
        return JSON.stringify({ status: state, path: '.github/workflows/pstack-verifier.yml' });
      }
      return JSON.stringify([{ artifacts }]);
    };
    assert.equal(restore({ repo: 'a/b', sha, feature: 'hosts.md', directory, runId: '10', key, run }), false);
    assert.equal(fs.existsSync(directory), false);
  }
}));

test('an unavailable cache API falls back to driving the base', () => fixture(({ directory }) => {
  assert.equal(restore({ repo: 'a/b', sha, feature: 'hosts.md', directory, runId: '10', key, run() { throw new Error('HTTP 503'); } }), false);
  assert.equal(fs.existsSync(directory), false);
}));
