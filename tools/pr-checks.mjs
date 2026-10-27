import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout } from 'node:timers/promises';

export const requiredChecks = [
  'test',
  'image-preview-emulator',
  'reply-notifications-emulator',
  'session-rows-emulator',
  'message-actions-emulator',
  'approved-style-emulator',
  'secret-scan',
  'no-paid-ai',
];
export const intervalMs = 300_000;
export const timeoutMs = 1_800_000;

export function checkStatus(pr) {
  if (pr.state !== 'OPEN') throw new Error('PR must be open');
  if (!/^[a-f0-9]{40}$/.test(pr.headRefOid) || !/^[a-f0-9]{40}$/.test(pr.baseRefOid)) throw new Error('Missing exact PR revisions');
  if (!Array.isArray(pr.statusCheckRollup) || pr.statusCheckRollup.length >= 100) throw new Error('Missing or truncated check graph');
  if (pr.mergeable === 'CONFLICTING') throw new Error('PR conflicts with base');
  const checks = pr.statusCheckRollup;
  const failed = checks.filter(check => check.__typename === 'CheckRun'
    ? check.status === 'COMPLETED' && check.conclusion !== 'SUCCESS'
    : check.__typename === 'StatusContext' ? !['SUCCESS', 'PENDING', 'EXPECTED'].includes(check.state) : true);
  if (failed.length) throw new Error(`Checks not green: ${failed.map(check => check.name ?? check.context ?? 'unknown check').join(', ')}`);
  const missing = requiredChecks.filter(name => !checks.some(check => check.__typename === 'CheckRun' && check.name === name));
  const pending = checks.filter(check => check.__typename === 'CheckRun' ? check.status !== 'COMPLETED' : check.state !== 'SUCCESS');
  const ready = pr.mergeable === 'MERGEABLE' && missing.length === 0 && pending.length === 0;
  return { ready, message: ready ? `COMPLETE_CHECKS_GREEN ${pr.headRefOid}` : `Waiting: missing [${missing.join(', ')}], pending ${pending.length}, mergeable ${pr.mergeable}` };
}

export async function waitForChecks(inspect, { wait = false, now = Date.now, pause = setTimeout, report = console.error } = {}) {
  const deadline = now() + timeoutMs;
  let head, base;
  while (true) {
    if (now() >= deadline) throw new Error('Check wait exceeded thirty minutes');
    const pr = await inspect();
    if (head && (pr.headRefOid !== head || pr.baseRefOid !== base)) throw new Error('PR head or base changed; stop this wait');
    head = pr.headRefOid;
    base = pr.baseRefOid;
    const status = checkStatus(pr);
    if (now() >= deadline) throw new Error('Check wait exceeded thirty minutes');
    if (status.ready) return status.message;
    if (!wait) throw new Error(status.message);
    report(status.message);
    await pause(Math.min(intervalMs, deadline - now()));
  }
}

export async function withWaitLock(pr, work) {
  const lock = join(tmpdir(), `multiprompt-android-pr-${pr}-checks.lock`);
  try {
    mkdirSync(lock);
  } catch (error) {
    if (error.code === 'EEXIST') throw new Error(`Another wait owns PR ${pr}; do not start a second wait`);
    throw error;
  }
  try {
    return await work();
  } finally {
    rmSync(lock, { recursive: true });
  }
}

async function main() {
  const [pr, option, ...extra] = process.argv.slice(2);
  if (!/^[1-9][0-9]*$/.test(pr ?? '') || (option !== undefined && option !== '--wait') || extra.length) {
    throw new Error('Usage: node tools/pr-checks.mjs <PR> [--wait]');
  }
  const inspect = () => JSON.parse(execFileSync('gh', [
    'pr', 'view', pr, '--repo', 'valentinyeo/multiprompt-android',
    '--json', 'state,headRefOid,baseRefOid,mergeable,statusCheckRollup',
  ], { encoding: 'utf8', timeout: 30_000, maxBuffer: 2 * 1024 * 1024 }));
  const work = () => waitForChecks(inspect, { wait: option === '--wait' });
  console.log(await (option === '--wait' ? withWaitLock(pr, work) : work()));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
