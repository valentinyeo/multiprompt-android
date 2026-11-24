import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout } from 'node:timers/promises';

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
export const speedCheckJob = 'chat list remeasures';
export const journeyVerifyCheck = /^journey (.+) \/ verify$/;
export const nightlyWorkflow = 'android-full.yml';
export const nightlyJobName = /^nightly (.+) \/ verify$/;
export const nightlyJobsPerPage = 100;
export const nightlyJobPages = 10;
export const nightlyFailures = new Set(['failure', 'timed_out', 'startup_failure']);
export const intervalMs = 300_000;
export const timeoutMs = 1_800_000;
export const repo = 'valentinyeo/multiprompt-android';
export const prFields = ['state', 'headRefOid', 'mergeable', 'statusCheckRollup'];

export function inspectPr(pr, run = execFileSync) {
  const options = { encoding: 'utf8', timeout: 30_000, maxBuffer: 2 * 1024 * 1024 };
  const view = JSON.parse(run('gh', ['pr', 'view', pr, '--repo', repo, '--json', prFields.join(',')], options));
  const pull = JSON.parse(run('gh', ['api', `repos/${repo}/pulls/${pr}`], options));
  const base = pull?.base?.sha ?? '';
  const mergeableState = typeof pull?.mergeable_state === 'string' ? pull.mergeable_state : 'unknown';
  return { ...view, baseRefOid: base, mergeableState, number: Number(pr) };
}

export function behindMessage(prNumber = '<PR>') {
  return `BEHIND: base moved forward; run gh pr update-branch ${prNumber} --repo ${repo}`;
}

function checkFailed(check) {
  if (check.__typename === 'CheckRun') {
    return check.status === 'COMPLETED' && check.conclusion !== 'SUCCESS'
      && !(check.conclusion === 'SKIPPED'
        && (emptyJourneyMatrix.test(check.name ?? '') || check.name === speedCheckJob));
  }
  if (check.__typename === 'StatusContext') return !['SUCCESS', 'PENDING', 'EXPECTED'].includes(check.state);
  return true;
}

export function ghApiRequest(path, run = execFileSync) {
  const options = { encoding: 'utf8', timeout: 60_000, maxBuffer: 16 * 1024 * 1024 };
  return JSON.parse(run('gh', ['api', path], options));
}

export function newestNightlyRun(fetch = ghApiRequest) {
  const body = fetch(`repos/${repo}/actions/workflows/${nightlyWorkflow}/runs?branch=main&per_page=30`);
  const listed = Array.isArray(body?.workflow_runs) ? body.workflow_runs : [];
  const runs = listed.filter(run => run.event === 'schedule' && run.head_branch === 'main' && run.status !== 'queued' && run.status !== 'requested' && run.status !== 'waiting');
  runs.sort((a, b) => (Date.parse(b.created_at ?? '') || 0) - (Date.parse(a.created_at ?? '') || 0) || (b.id ?? 0) - (a.id ?? 0));
  const latest = runs[0];
  if (!latest?.id) return { id: '', url: '', reason: 'no android-full nightly run on main has started yet' };
  return { id: String(latest.id), url: String(latest.html_url ?? ''), reason: '' };
}

export function nightlyRedJourneys(runId, fetch = ghApiRequest) {
  const red = new Set();
  for (let page = 1; page <= nightlyJobPages; page++) {
    const body = fetch(`repos/${repo}/actions/runs/${runId}/jobs?filter=latest&per_page=${nightlyJobsPerPage}&page=${page}`);
    const jobs = Array.isArray(body?.jobs) ? body.jobs : [];
    for (const job of jobs) {
      const match = nightlyJobName.exec(job?.name ?? '');
      if (match && job.status === 'completed' && nightlyFailures.has(job.conclusion)) red.add(match[1]);
    }
    if (jobs.length < nightlyJobsPerPage) return [...red].sort();
  }
  throw new Error(`Nightly run ${runId} has more than ${nightlyJobsPerPage * nightlyJobPages} jobs to read`);
}

export function loadRedOnMainJourneys({ fetch = ghApiRequest, report = console.error } = {}) {
  try {
    const nightly = newestNightlyRun(fetch);
    if (!nightly.id) {
      report(`No journey counts as red on main: ${nightly.reason}.`);
      return { journeys: [], url: '', note: nightly.reason };
    }
    return { journeys: nightlyRedJourneys(nightly.id, fetch), url: nightly.url, note: '' };
  } catch (error) {
    const note = `No journey counts as red on main: the newest nightly run could not be read (${String(error?.message ?? error).slice(0, 200)})`;
    report(note);
    return { journeys: [], url: '', note };
  }
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
  const checksReady = missing.length === 0 && pending.length === 0;
  if (pr.mergeableState === 'behind' && checksReady) {
    return {
      ready: false,
      message: behindMessage(pr.number),
      toleratedRedOnMain,
      redOnMainUrl: redOnMain.url ?? '',
    };
  }
  const ready = pr.mergeable === 'MERGEABLE' && checksReady;
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
  const inspect = () => inspectPr(pr);
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
