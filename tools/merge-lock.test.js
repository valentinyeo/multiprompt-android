const test = require('node:test');
const assert = require('node:assert/strict');

const gate = import('./merge-lock.mjs');

let blobCounter = 0;

function blobSha(content) {
  blobCounter += 1;
  const hex = Buffer.from(`${blobCounter}:${content}`).toString('hex').padEnd(40, '0').slice(0, 40);
  return hex.replace(/[^a-f0-9]/g, 'a');
}

function commitSha(treeSha) {
  const hex = Buffer.from(`commit:${treeSha}`).toString('hex').padEnd(40, '0').slice(0, 40);
  return hex.replace(/[^a-f0-9]/g, 'c');
}

function treeSha(blobShaValue) {
  const hex = Buffer.from(`tree:${blobShaValue}`).toString('hex').padEnd(40, '0').slice(0, 40);
  return hex.replace(/[^a-f0-9]/g, 'd');
}

function readField(args, key) {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '-f' && args[i + 1]?.startsWith(`${key}=`)) return args[i + 1].slice(key.length + 1);
  }
  return '';
}

function makeStore() {
  blobCounter = 0;
  const blobs = new Map();
  const trees = new Map();
  const commits = new Map();
  let refSha = '';
  const gh = (command, args) => {
    assert.equal(command, 'gh');
    const method = args[2];
    const path = args[3];
    if (method === 'GET' && path.endsWith('/git/ref/merge-lock/main')) {
      if (!refSha) throw new Error('gh: HTTP 404: Not Found\n');
      return JSON.stringify({ object: { sha: refSha } });
    }
    if (method === 'GET' && /\/git\/commits\//.test(path)) {
      const sha = path.split('/').pop();
      return JSON.stringify(commits.get(sha));
    }
    if (method === 'GET' && /\/git\/trees\//.test(path)) {
      const sha = path.split('/').pop();
      return JSON.stringify(trees.get(sha));
    }
    if (method === 'GET' && /\/git\/blobs\//.test(path)) {
      const sha = path.split('/').pop();
      return JSON.stringify({ encoding: 'base64', content: Buffer.from(blobs.get(sha)).toString('base64') });
    }
    if (method === 'POST' && path.endsWith('/git/blobs')) {
      const value = readField(args, 'content');
      const sha = blobSha(value);
      blobs.set(sha, value);
      return JSON.stringify({ sha });
    }
    if (method === 'POST' && path.endsWith('/git/trees')) {
      const blob = readField(args, 'tree[][sha]');
      const tree = treeSha(blob);
      trees.set(tree, { sha: tree, tree: [{ path: '.merge-lock.json', type: 'blob', sha: blob }] });
      return JSON.stringify({ sha: tree });
    }
    if (method === 'POST' && path.endsWith('/git/commits')) {
      const tree = readField(args, 'tree');
      const sha = commitSha(tree);
      commits.set(sha, { sha, tree: { sha: tree } });
      return JSON.stringify({ sha });
    }
    if (method === 'POST' && path.endsWith('/git/refs')) {
      const sha = readField(args, 'sha');
      refSha = sha;
      return JSON.stringify({ ref: 'refs/merge-lock/main', object: { sha } });
    }
    if (method === 'PATCH' && path.endsWith('/git/refs/merge-lock/main')) {
      const sha = readField(args, 'sha');
      refSha = sha;
      return JSON.stringify({ object: { sha } });
    }
    if (method === 'DELETE' && path.endsWith('/git/refs/merge-lock/main')) {
      refSha = '';
      return '';
    }
    throw new Error(`unexpected gh ${method} ${path}`);
  };
  return { gh, getRef: () => refSha, seedLock(lock) {
    const payload = JSON.stringify(lock);
    const blob = blobSha(payload);
    blobs.set(blob, payload);
    const tree = treeSha(blob);
    trees.set(tree, { sha: tree, tree: [{ path: '.merge-lock.json', type: 'blob', sha: blob }] });
    const commit = commitSha(tree);
    commits.set(commit, { sha: commit, tree: { sha: tree } });
    refSha = commit;
  } };
}

test('encode and decode round-trip lock payloads', async () => {
  const { encodeLock, decodeLock, lockLive } = await gate;
  const raw = encodeLock('agent-a', 1_700_000_000_000);
  assert.deepEqual(decodeLock(raw), { holder: 'agent-a', expiresAt: 1_700_000_000_000 });
  assert.equal(lockLive({ holder: 'agent-a', expiresAt: Date.now() + 60_000 }), true);
  assert.equal(lockLive({ holder: 'agent-a', expiresAt: Date.now() - 1 }), false);
});

test('acquire creates a lock ref when none exists', async () => {
  const { acquireLock } = await gate;
  const store = makeStore();
  const lock = await acquireLock('agent-a', { now: () => 1_700_000_000_000, run: store.gh });
  assert.equal(lock.holder, 'agent-a');
  assert.equal(lock.expiresAt, 1_700_000_000_000 + 1_800_000);
  assert.ok(store.getRef());
});

test('acquire fails when another session holds a live lock', async () => {
  const { acquireLock, lockExpiryMs } = await gate;
  const store = makeStore();
  const now = 1_700_000_000_000;
  store.seedLock({ holder: 'agent-b', expiresAt: now + lockExpiryMs - 60_000 });
  await assert.rejects(acquireLock('agent-a', { now: () => now, run: store.gh }), /held by agent-b/);
});

test('stale locks can be taken over', async () => {
  const { acquireLock } = await gate;
  const store = makeStore();
  const now = 1_700_000_000_000;
  store.seedLock({ holder: 'agent-b', expiresAt: now - 1 });
  const lock = await acquireLock('agent-a', { now: () => now, run: store.gh });
  assert.equal(lock.holder, 'agent-a');
});

test('release only removes the matching holder', async () => {
  const { acquireLock, releaseLock } = await gate;
  const store = makeStore();
  const now = () => 1_700_000_000_000;
  await acquireLock('agent-a', { now, run: store.gh });
  await assert.rejects(releaseLock('agent-b', { run: store.gh }), /held by agent-a/);
  const released = await releaseLock('agent-a', { run: store.gh });
  assert.equal(released.released, true);
  assert.equal(store.getRef(), '');
});

test('status reports free, held and stale locks', async () => {
  const { lockStatus, acquireLock } = await gate;
  const store = makeStore();
  const now = () => 1_700_000_000_000;
  assert.deepEqual(await lockStatus({ now, run: store.gh }), { held: false });
  await acquireLock('agent-a', { now, run: store.gh });
  const held = await lockStatus({ now, run: store.gh });
  assert.equal(held.held, true);
  assert.equal(held.holder, 'agent-a');
  store.seedLock({ holder: 'agent-old', expiresAt: now() - 1 });
  const stale = await lockStatus({ now, run: store.gh });
  assert.equal(stale.held, false);
  assert.equal(stale.stale, true);
  assert.equal(stale.holder, 'agent-old');
});

test('acquire writes utf-8 blob payloads through the GitHub git API', async () => {
  const { acquireLock } = await gate;
  const blobs = [];
  const run = (command, args) => {
    assert.equal(command, 'gh');
    const method = args[2];
    const path = args[3];
    if (method === 'GET' && path.endsWith('/git/ref/merge-lock/main')) throw new Error('gh: HTTP 404: Not Found\n');
    if (method === 'POST' && path.endsWith('/git/blobs')) {
      blobs.push(readField(args, 'content'));
      return JSON.stringify({ sha: 'b'.repeat(40) });
    }
    if (method === 'POST' && path.endsWith('/git/trees')) return JSON.stringify({ sha: 't'.repeat(40) });
    if (method === 'POST' && path.endsWith('/git/commits')) return JSON.stringify({ sha: 'c'.repeat(40) });
    if (method === 'POST' && path.endsWith('/git/refs')) return JSON.stringify({ ref: 'refs/merge-lock/main' });
    throw new Error(`unhandled ${method} ${path}`);
  };
  await acquireLock('agent-a', { now: () => 1_700_000_000_000, run });
  assert.match(blobs[0], /"holder":"agent-a"/);
  assert.match(blobs[0], /"expiresAt":/);
});