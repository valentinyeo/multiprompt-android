import { execFileSync } from 'node:child_process';
import { hostname } from 'node:os';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

export const repo = 'valentinyeo/multiprompt-android';
export const lockRef = 'merge-lock/main';
export const lockRefFull = 'refs/merge-lock/main';
export const lockExpiryMs = 1_800_000;
export const lockPath = '.merge-lock.json';

export function defaultHolder() {
  return `${hostname()}-${process.pid}`;
}

export function encodeLock(holder, expiresAt) {
  return JSON.stringify({ holder, expiresAt });
}

export function decodeLock(raw) {
  if (typeof raw !== 'string' || !raw.trim()) throw new Error('Lock ref has no payload');
  const lock = JSON.parse(raw);
  if (typeof lock?.holder !== 'string' || !lock.holder || typeof lock.expiresAt !== 'number') {
    throw new Error('Lock ref payload is invalid');
  }
  return lock;
}

export function lockLive(lock, now = Date.now()) {
  return lock.expiresAt > now;
}

export function ghApi(method, path, fields, run = execFileSync) {
  const args = ['api', '-X', method, path];
  if (fields) {
    for (const [key, value] of Object.entries(fields)) args.push('-f', `${key}=${value}`);
  }
  const options = { encoding: 'utf8', timeout: 60_000, maxBuffer: 4 * 1024 * 1024 };
  const out = run('gh', args, options);
  return out.trim() ? JSON.parse(out) : {};
}

export function ghApiMaybe(run, method, path, fields) {
  try {
    return { ok: true, body: ghApi(method, path, fields, run) };
  } catch (error) {
    const text = `${error?.message ?? ''}\n${error?.stderr ?? ''}`;
  if (/HTTP 404|Not Found/i.test(text)) return { ok: false, missing: true };
    throw error;
  }
}

export async function readLockPayload(run = execFileSync) {
  const ref = ghApiMaybe(run, 'GET', `repos/${repo}/git/ref/${lockRef}`);
  if (!ref.ok) return { lock: null, commitSha: '', refSha: '' };
  const commitSha = ref.body?.object?.sha ?? '';
  if (!/^[a-f0-9]{40}$/.test(commitSha)) throw new Error('Lock ref is missing a commit');
  const commit = ghApi('GET', `repos/${repo}/git/commits/${commitSha}`, null, run);
  const treeSha = commit?.tree?.sha;
  if (!/^[a-f0-9]{40}$/.test(treeSha ?? '')) throw new Error('Lock commit has no tree');
  const tree = ghApi('GET', `repos/${repo}/git/trees/${treeSha}`, null, run);
  const entry = (tree?.tree ?? []).find(node => node.path === lockPath && node.type === 'blob');
  if (!entry?.sha) throw new Error('Lock commit is missing its payload file');
  const blob = ghApi('GET', `repos/${repo}/git/blobs/${entry.sha}`, null, run);
  const encoding = blob?.encoding;
  const content = blob?.content?.replace(/\n/g, '') ?? '';
  const raw = encoding === 'base64' ? Buffer.from(content, 'base64').toString('utf8') : content;
  return { lock: decodeLock(raw), commitSha, refSha: ref.body?.object?.sha ?? commitSha };
}

export function writeLockCommit(holder, expiresAt, run = execFileSync) {
  const payload = encodeLock(holder, expiresAt);
  const blob = ghApi('POST', `repos/${repo}/git/blobs`, { content: payload, encoding: 'utf-8' }, run);
  const tree = ghApi('POST', `repos/${repo}/git/trees`, {
    'tree[][path]': lockPath,
    'tree[][mode]': '100644',
    'tree[][type]': 'blob',
    'tree[][sha]': blob.sha,
  }, run);
  const commit = ghApi('POST', `repos/${repo}/git/commits`, {
    message: 'merge-lock',
    tree: tree.sha,
  }, run);
  return commit.sha;
}

export function installLockRef(sha, expectedSha, run = execFileSync) {
  if (expectedSha) {
    ghApi('PATCH', `repos/${repo}/git/refs/${lockRef}`, { sha, force: 'false' }, run);
    return 'updated';
  }
  const created = ghApiMaybe(run, 'POST', `repos/${repo}/git/refs`, { ref: lockRefFull, sha });
  if (created.ok) return 'created';
  ghApi('PATCH', `repos/${repo}/git/refs/${lockRef}`, { sha, force: 'false' }, run);
  return 'updated';
}

export async function acquireLock(holder, { now = Date.now, run = execFileSync } = {}) {
  if (!holder) throw new Error('Lock holder name is required');
  for (let attempt = 0; attempt < 4; attempt++) {
    const current = await readLockPayload(run);
    if (current.lock && lockLive(current.lock, now()) && current.lock.holder !== holder) {
      throw new Error(`Merge lock held by ${current.lock.holder} until ${new Date(current.lock.expiresAt).toISOString()}`);
    }
    const expiresAt = now() + lockExpiryMs;
    const sha = writeLockCommit(holder, expiresAt, run);
    try {
      installLockRef(sha, current.commitSha, run);
      return { holder, expiresAt, renewed: current.lock?.holder === holder };
    } catch (error) {
      if (attempt === 3) throw error;
    }
  }
  throw new Error('Merge lock acquire failed after retries');
}

export async function releaseLock(holder, { run = execFileSync } = {}) {
  const current = await readLockPayload(run);
  if (!current.lock) return { released: false, reason: 'no lock' };
  if (current.lock.holder !== holder) {
    throw new Error(`Merge lock is held by ${current.lock.holder}, not ${holder}`);
  }
  ghApi('DELETE', `repos/${repo}/git/refs/${lockRef}`, null, run);
  return { released: true };
}

export async function lockStatus({ now = Date.now, run = execFileSync } = {}) {
  const current = await readLockPayload(run);
  if (!current.lock) return { held: false };
  const live = lockLive(current.lock, now());
  return { held: live, holder: current.lock.holder, expiresAt: current.lock.expiresAt, stale: !live };
}

async function main() {
  const [command, holderArg] = process.argv.slice(2);
  const holder = holderArg || defaultHolder();
  if (command === 'acquire') {
    const lock = await acquireLock(holder);
    console.log(`MERGE_LOCK_ACQUIRED ${lock.holder} ${lock.expiresAt}`);
    return;
  }
  if (command === 'release') {
    const result = await releaseLock(holder);
    console.log(result.released ? `MERGE_LOCK_RELEASED ${holder}` : 'MERGE_LOCK_ABSENT');
    return;
  }
  if (command === 'status') {
    const status = await lockStatus();
    if (!status.held) {
      console.log(status.stale ? `MERGE_LOCK_STALE was ${status.holder}` : 'MERGE_LOCK_FREE');
      return;
    }
    console.log(`MERGE_LOCK_HELD ${status.holder} until ${new Date(status.expiresAt).toISOString()}`);
    return;
  }
  throw new Error('Usage: node tools/merge-lock.mjs acquire|release|status [holder]');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}