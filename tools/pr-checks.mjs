import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout } from 'node:timers/promises';

const moduleRequire = createRequire(import.meta.url);
const { downloadRedOnMain } = moduleRequire('../.github/scripts/pstack-verifier/verifier.cjs');

export const requiredChecks = [
  'build',
  'test',
  'unit-tests',
  'm3-unit-tests',
  'lint',
  'secret-scan',
  'no-paid-ai',
  'pstack-verifier',
];
export const retiringChecks = [
  'baseline-profile',
  'phone-routing',
  'message-projection-proof',
  'image-preview-emulator',
  'reply-notifications-emulator',
  'session-rows-emulator',
  'session-pins-emulator',
  'message-actions-emulator',
  'approved-style-emulator',
  'pick journeys',
];
export const emptyJourneyMatrix = /^(?:parent )?journey \$\{\{ matrix\.feature \}\}(?: \/ .*)?$/;
export const journeyVerifyCheck = /^journey (.+) \/ verify$/;
export const intervalMs = 300_000;
export const timeoutMs = 1_800_000;
const repo = 'valentinyeo/multiprompt-android';

function checkFailed(check) {
  if (check.__typename === 'CheckRun') {
    return check.status === 'COMPLETED' && check.conclusion !== 'SUCCESS'
      && !(check.conclusion === 'SKIPPED' && emptyJourneyMatrix.test(check.name ?? ''));
  }
  if (check.__typename === 'StatusContext') return !['SUCCESS', 'PENDING', 'EXPECTED'].includes(check.state);
  return true;
}

export function loadRedOnMainJourneys() {
  const directory = join(tmpdir(), `multiprompt-android-red-on-main-${process.pid}`);
  mkdirSync(directory, { recursive: true });
  return downloadRedOnMain(repo, directory);
}

export function reportToleratedRedOnMain(status, report = console.error) {
  if (!status.toleratedRedOnMain?.length) return;
  const nightly = status.redOnMainUrl ? `nightly ${status.redOnMainUrl}` : 'nightly run link unavailable';
  report(`Red on main tolerated (${nightly}): ${[...status.toleratedRedOnMain].sort().join(', ')}`);
}

export function checkStatus(pr, { redOnMain = { journeys: [], url: '' } } = {}) {
  if (pr.state !== 'OPEN') throw new Error('PR must be open');
  if (!/^[a-f0-9]{40}$/.test(pr.headRefOid) || !/^[a-f0-9]{40}$/.test(pr.baseRefOid)) throw new Error('Missing exact PR revisions');
  if (!Array.isArray(pr.statusCheckRollup) || pr.statusCheckRollup.length >= 100) throw new Error('Missing or truncated check graph');
  if (pr.mergeable === 'CONFLICTING') throw new Error('PR conflicts with base');
  const checks = [];
  const latestRuns = new Map();
  for (const check of pr.statusCheckRollup) {
    if (check.__typename !== 'CheckRun') {
      checks.push(check);
      continue;
    }
    const key = JSON.stringify([check.name, check.workflowName ?? '']);
    const timestamp = Date.parse(check.startedAt || check.completedAt) || 0;
    const previous = latestRuns.get(key);
    if (!previous || timestamp >= previous.timestamp) latestRuns.set(key, { check, timestamp });
  }
  checks.push(...Array.from(latestRuns.values(), ({ check }) => check));
  const verifier = checks.find(check => check.__typename === 'CheckRun' && check.name === 'pstack-verifier');
  const verifierGreen = verifier?.status === 'COMPLETED' && verifier.conclusion === 'SUCCESS';
  const verifierPending = verifier != null && verifier.status !== 'COMPLETED';
  const redOnMainSet = new Set(Array.isArray(redOnMain.journeys) ? redOnMain.journeys : []);
  const toleratedRedOnMain = [];
  const failed = checks.filter(check => {
    if (!checkFailed(check)) return false;
    const match = journeyVerifyCheck.exec(check.name ?? '');
    if (match && redOnMainSet.has(match[1])) {
      if (verifierGreen) {
        toleratedRedOnMain.push(match[1]);
        return false;
      }
      if (verifierPending) return false;
    }
    return true;
  });
  if (failed.length) {
    const error = new Error(`Checks not green: ${failed.map(check => check.name ?? check.context ?? 'unknown check').join(', ')}`);
    error.toleratedRedOnMain = toleratedRedOnMain;
    error.redOnMainUrl = redOnMain.url ?? '';
    throw error;
  }
  const missing = requiredChecks.filter(name => !checks.some(check => check.__typename === 'CheckRun' && check.name === name));
  const pending = checks.filter(check => check.__typename === 'CheckRun' ? check.status !== 'COMPLETED' : check.state !== 'SUCCESS');
  const ready = pr.mergeable === 'MERGEABLE' && missing.length === 0 && pending.length === 0;
  return {
    ready,
    message: ready ? `COMPLETE_CHECKS_GREEN ${pr.headRefOid}` : `Waiting: missing [${missing.join(', ')}], pending ${pending.length}, mergeable ${pr.mergeable}`,
    toleratedRedOnMain,
    redOnMainUrl: redOnMain.url ?? '',
  };
}

export async function waitForChecks(inspect, { wait = false, now = Date.now, pause = setTimeout, report = console.error, loadRedOnMain = null } = {}) {
  const deadline = now() + timeoutMs;
  let head;
  while (true) {
    if (now() >= deadline) throw new Error('Check wait exceeded thirty minutes');
    const pr = await inspect();
    if (head && pr.headRefOid !== head) throw new Error('PR head changed; stop this wait');
    head = pr.headRefOid;
    const redOnMain = loadRedOnMain ? loadRedOnMain() : { journeys: [], url: '' };
    let status;
    try {
      status = checkStatus(pr, { redOnMain });
    } catch (error) {
      reportToleratedRedOnMain(error, report);
      throw error;
    }
    reportToleratedRedOnMain(status, report);
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
  const work = () => waitForChecks(inspect, { wait: option === '--wait', loadRedOnMain: loadRedOnMainJourneys });
  console.log(await (option === '--wait' ? withWaitLock(pr, work) : work()));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    reportToleratedRedOnMain(error);
    console.error(error.message);
    process.exitCode = 1;
  });
}
