const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const { patchId, reusable, verdictComment, reusedComment } = require('../.github/scripts/pstack-verifier/reuse.cjs');

const sha = 'a'.repeat(40);
const base = 'b'.repeat(40);
const patch = 'c'.repeat(40);
const current = { repo: 'o/r', pr: 42, sha: 'd'.repeat(40), base: 'e'.repeat(40), mergeBase: 'e'.repeat(40), patchId: patch, runId: 20 };
const record = { version: 1, repo: 'o/r', pr: 42, sha, base, mergeBase: base, patchId: patch, runId: 10, verdict: 'PASS+NOTES' };
const run = { id: 10, status: 'completed', conclusion: 'success', path: '.github/workflows/pstack-verifier.yml', event: 'pull_request', head_sha: sha, head_repository: { full_name: 'o/r' } };
const comment = { id: 123, user: { login: 'github-actions[bot]' }, html_url: 'https://github.com/o/r/pull/42#issuecomment-123', body: `pstack-verifier: PASS+NOTES @ ${sha}\n\nNot covered:\n- real OTP\n\nRun: https://github.com/o/r/actions/runs/10\n\n<!-- pstack-verifier:v1 sha=${sha} -->` };

test('only a successful independent verdict for the same patch is reusable, including a same-base rebase', () => {
  for (const verdict of ['PASS', 'PASS+NOTES']) assert.equal(reusable({ ...record, verdict }, current, run), true);
  assert.equal(reusable(record, { ...current, base: record.base, mergeBase: record.mergeBase }, run), true);
  for (const change of [{ version: 0 }, { pr: 43 }, { sha: current.sha }, { sha: 'bad' }, { base: 'bad' }, { patchId: 'f'.repeat(40) }, { verdict: 'FAIL' }, { verdict: '' }, { runId: 12 }]) {
    assert.equal(reusable({ ...record, ...change }, current, run), false, JSON.stringify(change));
  }
  for (const change of [{ id: 20 }, { conclusion: 'failure' }, { conclusion: 'cancelled' }, { status: 'in_progress' }, { path: 'other.yml' }, { event: 'push' }, { head_sha: current.sha }, { head_repository: { full_name: 'fork/r' } }]) {
    assert.equal(reusable(record, current, { ...run, ...change }), false, JSON.stringify(change));
  }
});

test('a forged, stale or failed verdict comment cannot supply reusable proof', () => {
  const check = value => verdictComment(value, sha, 'PASS+NOTES', 'https://github.com/o/r/actions/runs/10');
  assert.equal(check(comment), true);
  for (const value of [null, { ...comment, user: { login: 'someone' } }, { ...comment, body: comment.body.replace('PASS+NOTES', 'FAIL') }, { ...comment, body: comment.body.replace('runs/10', 'runs/11') }, { ...comment, body: comment.body.replace('v1', 'v2') }]) assert.equal(Boolean(check(value)), false);
});

test('reused verdict names the new head, source verdict and patch-id, retaining limitations', () => {
  const body = reusedComment(record, current, comment);
  assert.equal(body.split('\n')[0], `pstack-verifier: PASS @ ${current.sha}`);
  assert.ok(body.includes(`Reused PASS+NOTES verdict sha ${sha}; git patch-id --stable ${patch}`));
  assert.ok(body.includes(comment.html_url));
  assert.ok(body.includes('- real OTP'));
  assert.ok(body.includes('Original verification run: https://github.com/o/r/actions/runs/10'));
  assert.ok(body.includes('\nRun: https://github.com/o/r/actions/runs/20\n'));
  assert.ok(body.includes(`<!-- pstack-verifier:v1 sha=${current.sha} -->`));
  assert.ok(!body.includes(`<!-- pstack-verifier:v1 sha=${sha} -->`));
});

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'verdict-reuse-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const write = (file, content) => fs.writeFileSync(path.join(dir, file), content);
  const commit = message => { git('add', '.'); git('commit', '-qm', message); return git('rev-parse', 'HEAD'); };
  git('init', '-qb', 'main');
  git('config', 'user.name', 'Test');
  git('config', 'user.email', 'test@users.noreply.github.com');
  write('app.txt', 'before\n');
  const base = commit('base');
  git('checkout', '-qb', 'feature');
  write('app.txt', 'after\n');
  const head = commit('feature');
  git('checkout', '-q', 'main');
  write('unrelated.txt', 'new main\n');
  const nextBase = commit('main moved');
  git('checkout', '-qb', 'rebased');
  git('cherry-pick', head);
  const nextHead = git('rev-parse', 'HEAD');
  git('remote', 'add', 'origin', dir);
  const id = (base, head) => execFileSync(process.execPath, ['-e', `process.stdout.write(require(${JSON.stringify(path.resolve('.github/scripts/pstack-verifier/reuse.cjs'))}).patchId(${JSON.stringify(base)},${JSON.stringify(head)}))`], { cwd: dir, encoding: 'utf8' });
  return { dir, git, write, commit, base, head, nextBase, nextHead, id };
}

test('real git patch-id survives a rebase, but rejects app, test, doc, mode and binary edits', t => {
  const f = fixture(t);
  const original = f.id(f.base, f.head);
  assert.match(original, /^[a-f0-9]{40}$/);
  assert.equal(f.id(f.nextBase, f.nextHead), original);
  for (const [file, content] of [['app.txt', 'different\n'], ['test.js', 'test\n'], ['README.md', 'docs\n'], ['image.bin', Buffer.from([0, 1, 2, 255])]]) {
    f.write(file, content);
    const head = f.commit('changed patch');
    assert.notEqual(f.id(f.nextBase, head), original, file);
  }
  const beforeMode = f.id(f.nextBase, f.git('rev-parse', 'HEAD'));
  f.git('update-index', '--chmod=+x', 'test.js');
  f.git('commit', '-qm', 'mode');
  assert.notEqual(f.id(f.nextBase, f.git('rev-parse', 'HEAD')), beforeMode);
  assert.throws(() => patchId('bad', sha), /exact patch revisions/);
});

test('probe, publish and save use retained proof and rerun on changed or missing evidence', t => {
  const f = fixture(t);
  const old = { ...record, sha: f.head, base: f.base, mergeBase: f.base, patchId: f.id(f.base, f.head) };
  const original = { ...comment, body: comment.body.replaceAll(sha, f.head) };
  const prior = { ...run, head_sha: f.head };
  const pr = { state: 'open', base: { ref: 'main', sha: f.nextBase }, head: { sha: f.nextHead, repo: { full_name: 'o/r' } } };
  const state = { pr, run: prior, record: old, comments: [original], artifacts: [{ name: 'verifier-verdict', expired: false }] };
  const statePath = path.join(f.dir, 'state.json');
  const output = path.join(f.dir, 'output');
  const sent = path.join(f.dir, 'sent');
  f.write('gh', `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const state = JSON.parse(fs.readFileSync(process.env.STATE));
const args = process.argv.slice(2);
if (args[0] === 'run') {
  fs.writeFileSync(path.join(args[args.indexOf('-D') + 1], 'verdict.json'), JSON.stringify(state.record));
} else if (args.includes('-X')) {
  fs.writeFileSync(process.env.SENT, fs.readFileSync(0));
} else {
  const endpoint = args.at(-1);
  let result;
  if (endpoint.endsWith('/pulls/42')) result = state.pr;
  else if (endpoint.includes('/comments?')) result = [state.comments];
  else if (endpoint.includes('/artifacts?')) result = [{ artifacts: state.artifacts }];
  else if (endpoint.endsWith('/runs/10')) {
    if (state.runError) process.exit(1);
    result = state.run;
  }
  else throw Error('Unexpected request: ' + args.join(' '));
  console.log(JSON.stringify(result));
}
`);
  fs.chmodSync(path.join(f.dir, 'gh'), 0o755);
  const command = (mode, env = {}) => {
    fs.writeFileSync(statePath, JSON.stringify(state));
    fs.writeFileSync(output, '');
    return spawnSync(process.execPath, [path.resolve('.github/scripts/pstack-verifier/reuse.cjs'), mode], {
      cwd: f.dir, encoding: 'utf8', env: { ...process.env, PATH: f.dir + ':' + process.env.PATH, STATE: statePath, SENT: sent, REPO: 'o/r', PR_NUMBER: '42', RUN_ID: '20', EVENT_HEAD_SHA: f.nextHead, GITHUB_OUTPUT: output, ...env },
    });
  };
  let result = command('probe');
  assert.equal(result.status, 0, result.stderr);
  assert.match(fs.readFileSync(output, 'utf8'), /reused=true\n/);
  assert.match(fs.readFileSync(output, 'utf8'), /features=\[\]\n/);
  result = command('publish');
  assert.equal(result.status, 0, result.stderr);
  assert.match(fs.readFileSync(sent, 'utf8'), new RegExp(`^pstack-verifier: PASS @ ${f.nextHead}`));
  const sameBaseSha = f.git('commit-tree', f.git('rev-parse', f.nextHead + '^{tree}'), '-p', f.nextBase, '-m', 'another rebased head');
  state.record = { ...old, sha: sameBaseSha, base: f.nextBase, mergeBase: f.nextBase };
  state.run = { ...prior, head_sha: sameBaseSha };
  state.comments = [{ ...original, body: original.body.replaceAll(f.head, sameBaseSha) }];
  assert.equal(command('probe').status, 0);
  assert.match(fs.readFileSync(output, 'utf8'), /reused=true\n/);
  assert.equal(command('publish').status, 0);
  assert.ok(fs.readFileSync(sent, 'utf8').includes(`Reused PASS+NOTES verdict sha ${sameBaseSha}`));
  state.record = old;
  state.run = prior;
  state.comments = [original];
  state.artifacts = [];
  result = command('probe');
  assert.equal(result.status, 0, result.stderr);
  assert.match(fs.readFileSync(output, 'utf8'), /reused=false\n/);
  assert.notEqual(command('publish').status, 0);
  state.artifacts = [{ name: 'verifier-verdict', expired: false }];
  state.record = { ...old, patchId: 'f'.repeat(40) };
  assert.equal(command('probe').status, 0);
  assert.match(fs.readFileSync(output, 'utf8'), /reused=false\n/);
  state.record = null;
  assert.equal(command('probe').status, 0);
  assert.match(fs.readFileSync(output, 'utf8'), /reused=false\n/);
  state.record = old;
  state.runError = true;
  assert.equal(command('probe').status, 0);
  assert.match(fs.readFileSync(output, 'utf8'), /reused=false\n/);
  assert.notEqual(command('publish').status, 0);
  delete state.runError;
  state.pr.head.sha = f.head;
  assert.notEqual(command('probe').status, 0);
  state.pr.head.sha = f.nextHead;
  state.comments = [{ ...original, body: original.body.replaceAll(f.head, f.nextHead).replace('runs/10', 'runs/20') }];
  const file = path.join(f.dir, 'verdict.json');
  result = command('save', { HEAD_SHA: f.nextHead, MERGE_BASE: f.nextBase, VERDICT: 'PASS+NOTES', RECORD_PATH: file });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(fs.readFileSync(file)), { version: 1, repo: 'o/r', pr: 42, sha: f.nextHead, base: f.nextBase, mergeBase: f.nextBase, patchId: old.patchId, runId: 20, verdict: 'PASS+NOTES' });
  assert.notEqual(command('save', { HEAD_SHA: f.nextHead, MERGE_BASE: f.base, VERDICT: 'PASS', RECORD_PATH: file }).status, 0);
  assert.notEqual(command('save', { HEAD_SHA: f.nextHead, MERGE_BASE: f.nextBase, VERDICT: 'FAIL', RECORD_PATH: file }).status, 0);
});
