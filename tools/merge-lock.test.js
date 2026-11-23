const test = require('node:test');
const assert = require('node:assert/strict');
const { hostname } = require('node:os');

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
    if ((args[i] === '-f' || args[i] === '-F') && args[i + 1]?.startsWith(`${key}=`)) return args[i + 1].slice(key.length + 1);
  }
  return '';
}

function fieldFlag(args, key) {
  const at = args.findIndex(arg => arg === `${key}=${readField(args, key)}`);
  return at > 0 ? args[at - 1] : '';
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
    if (method === 'GET' && path.includes('/git/ref/merge-lock/main')) {
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
    if (method === 'GET' && path.includes('/git/ref/merge-lock/main')) throw new Error('gh: HTTP 404: Not Found\n');
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

test('one holder name per chat, not per run', async () => {
  const { defaultHolder } = await gate;
  assert.equal(defaultHolder({ MULTIBOT_SESSION: 'live-27bf76b3' }), 'live-27bf76b3');
  assert.equal(defaultHolder({ ZSB_SESSION: 'live-aa', MULTIBOT_SID: 'sid-1' }), 'live-aa');
  assert.equal(defaultHolder({ MERGE_LOCK_HOLDER: 'agent-9', MULTIBOT_SESSION: 'live-aa' }), 'agent-9');
  assert.equal(defaultHolder({ MULTIBOT_SID: 'e0d52105' }), 'e0d52105');
  assert.equal(defaultHolder({}), hostname());
  assert.doesNotMatch(defaultHolder({}), new RegExp(`-${process.pid}$`));
});

test('a second call in the same chat releases the lock the first one took', async () => {
  const { acquireLock, releaseLock, defaultHolder } = await gate;
  const env = { MULTIBOT_SESSION: 'live-27bf76b3' };
  const store = makeStore();
  await acquireLock(defaultHolder(env), { now: () => 1_700_000_000_000, run: store.gh });
  const released = await releaseLock(defaultHolder(env), { run: store.gh });
  assert.equal(released.released, true);
  assert.equal(store.getRef(), '');
});

test('the ref patch sends force as a JSON false, not the string false', async () => {
  const { acquireLock } = await gate;
  const store = makeStore();
  const patches = [];
  const run = (command, args) => {
    if (args[2] === 'PATCH') patches.push(args);
    return store.gh(command, args);
  };
  const now = 1_700_000_000_000;
  store.seedLock({ holder: 'agent-b', expiresAt: now - 1 });
  await acquireLock('agent-a', { now: () => now, run });
  assert.equal(patches.length, 1);
  assert.equal(fieldFlag(patches[0], 'force'), '-F');
  assert.equal(readField(patches[0], 'force'), 'false');
});

test('status asks GitHub for the lock ref with a cache-busting nonce', async () => {
  const { lockStatus } = await gate;
  const store = makeStore();
  const reads = [];
  store.seedLock({ holder: 'agent-a', expiresAt: Date.now() + 60_000 });
  const run = (command, args) => {
    if (args[2] === 'GET' && args[3].includes('/git/ref/merge-lock/main')) reads.push(args[3]);
    return store.gh(command, args);
  };
  await lockStatus({ run });
  assert.equal(reads.length, 1);
  assert.match(reads[0], /^repos\/valentinyeo\/multiprompt-android\/git\/ref\/merge-lock\/main\?t=\d+$/);
});

test('status reads the lock fresh each time, never a cached copy', async () => {
  const { acquireLock, releaseLock, lockStatus } = await gate;
  const store = makeStore();
  const now = 1_700_000_000_000;
  await acquireLock('agent-a', { now: () => now, run: store.gh });
  assert.equal((await lockStatus({ now: () => now, run: store.gh })).held, true);
  await releaseLock('agent-a', { run: store.gh });
  assert.deepEqual(await lockStatus({ now: () => now, run: store.gh }), { held: false });
});

test('merge refuses to run without the checked head sha', async () => {
  const { mergePullRequest } = await gate;
  await assert.rejects(mergePullRequest('7', ''), /checked head sha/);
});

test('merge runs through the API so it never needs a local checkout', async () => {
  const { mergePullRequest, repo } = await gate;
  const head = 'a'.repeat(40);
  const mergeSha = 'e'.repeat(40);
  const calls = [];
  const run = (command, args) => {
    assert.equal(command, 'gh');
    calls.push(args);
    const method = args[2];
    const path = args[3];
    if (method === 'GET' && path === `repos/${repo}/pulls/7`) return JSON.stringify({ head: { ref: 'mpa/mubo-231' } });
    if (method === 'PUT' && path === `repos/${repo}/pulls/7/merge`) {
      assert.equal(readField(args, 'merge_method'), 'squash');
      assert.equal(readField(args, 'sha'), head);
      return JSON.stringify({ merged: true, sha: mergeSha });
    }
    if (method === 'DELETE' && path === `repos/${repo}/git/refs/heads/mpa/mubo-231`) return '';
    throw new Error(`unexpected gh ${method} ${path}`);
  };
  assert.deepEqual(await mergePullRequest('7', head, { run }), { merged: mergeSha, branch: 'mpa/mubo-231' });
  assert.deepEqual(calls.map(args => [args[0], args[1], args[2]]), [
    ['api', '-X', 'GET'],
    ['api', '-X', 'PUT'],
    ['api', '-X', 'DELETE'],
  ]);
  assert.ok(!calls.some(args => args[0] === 'pr' || args[0] === 'git'), 'the merge never touches the local checkout');
});
