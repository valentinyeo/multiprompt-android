import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = file => readFileSync(path.join(root, file), 'utf8');

function oldCheckScript() {
  return execFileSync('git', ['show', 'origin/main:tools/check.sh'], { cwd: root, encoding: 'utf8' });
}

const GRADLE_TASKS = new Set(['lintDebug', 'test', 'testM3UnitTest', 'testDebugUnitTest']);

function gradleTask(token) {
  return GRADLE_TASKS.has(token) && !token.startsWith('-');
}

function checks() {
  const current = read('tools/check.sh');
  const missing = [];
  for (const line of oldCheckScript().split('\n')) {
    const text = line.trim();
    if (!text || text.startsWith('#') || text.startsWith('set ' ) || text.startsWith('cd ')) continue;
    if (/^(?:"?\$\{GRADLE)/.test(text)) {
      const tasks = text.split(/\s+/).filter(gradleTask);
      for (const task of tasks) {
        const alias = task === 'test' ? [' test ', 'testDebugUnitTest'] : [task];
        if (!alias.some(name => current.includes(name))) missing.push(task);
      }
      continue;
    }
    for (const word of text.split(/\s+/).filter(word => /^[\w./-]+\.(mjs|js|py|sh)$/.test(word))) {
      if (!current.includes(word)) missing.push(word);
    }
  }
  if (missing.length) {
    console.log(`DROPPED ${[...new Set(missing)].join(' ')}`);
    process.exit(1);
  }
  console.log('NO_CHECK_DROPPED');
}

function jobs(text) {
  const blocks = text.split(/^jobs:\s*$/m)[1].split(/^  ([\w-]+):\s*$/m);
  const out = new Map();
  for (let index = 1; index < blocks.length; index += 2) out.set(blocks[index], blocks[index + 1]);
  return out;
}

function workflow() {
  const text = read('.github/workflows/test.yml');
  const all = jobs(text);
  const problems = [];
  const need = (name, ...needles) => {
    const body = all.get(name);
    if (!body) return problems.push(`missing job ${name}`);
    for (const needle of needles) if (!body.includes(needle)) problems.push(`${name} misses ${needle}`);
  };
  need('build', 'assembleDebug assembleDebugAndroidTest', 'actions/cache/save@v4', 'build-cache-1');
  for (const name of ['image-preview-emulator', 'reply-notifications-emulator', 'session-rows-emulator', 'message-actions-emulator', 'approved-style-emulator']) {
    need(name, 'needs: build', 'actions/cache/restore@v4', 'build-cache-1');
  }
  need('unit-tests', 'tools/check.sh unit');
  need('m3-unit-tests', 'tools/check.sh m3');
  need('lint', 'tools/check.sh lint');
  need('test', 'tools/check.sh source');
  if (all.get('test')?.includes('lintDebug')) problems.push('test still runs lint');
  if (problems.length) {
    console.log(problems.join(' | '));
    process.exit(1);
  }
  console.log('SPLIT_OK');
}

function required() {
  const gate = read('tools/pr-checks.mjs');
  const declared = [...gate.split('export const requiredChecks = [')[1].split('];')[0].matchAll(/'([\w-]+)'/g)].map(match => match[1]);
  const directory = path.join(root, '.github/workflows');
  const found = [];
  for (const file of readdirSync(directory).filter(name => name.endsWith('.yml'))) {
    const text = readFileSync(path.join(directory, file), 'utf8');
    if (!/^  pull_request:[ \t]*$/m.test(text)) continue;
    const event = text.split(/^  pull_request:[ \t]*$/m)[1].split(/^[^ \n]|^  \w/m)[0];
    if (/^    paths:/m.test(event)) continue;
    for (const [name, body] of jobs(text)) found.push(body.match(/^    name: (.+)$/m)?.[1] ?? name);
  }
  const same = [...declared].sort().join(',') === [...found].sort().join(',');
  console.log(same ? 'REQUIRED_MATCH' : `MISMATCH declared=${declared.sort().join(',')} jobs=${found.sort().join(',')}`);
  if (!same) process.exit(1);
}

function ticket() {
  const out = execFileSync('hypertask', ['task', 'get', '88', '--project', '11514', '--json'], { cwd: root, encoding: 'utf8' });
  const section = (JSON.parse(out).tasks ?? []).find(task => task.ticketNumber === 'HYCI-88')?.section;
  console.log(`SECTION ${section}`);
  if (section !== 'In Progress' && section !== 'AI Review' && section !== 'Done') process.exit(1);
}

function pr() {
  const out = execFileSync('gh', ['pr', 'view', 'ci-runner-4/hyci-88', '--json', 'number,baseRefName,url,state'], { cwd: root, encoding: 'utf8' });
  const data = JSON.parse(out);
  const body = execFileSync('gh', ['pr', 'view', 'ci-runner-4/hyci-88', '--json', 'body'], { cwd: root, encoding: 'utf8' });
  const text = JSON.parse(body).body;
  const ok = data.baseRefName === 'main' && data.state === 'OPEN' && text.includes('HYCI-88') && text.includes('14m32s') && text.includes('18m29s');
  console.log(ok ? `PR_OPEN ${data.url}` : `PR_INCOMPLETE base=${data.baseRefName} state=${data.state}`);
  if (!ok) process.exit(1);
}

function after() {
  const out = execFileSync('gh', ['run', 'list', '--workflow=test.yml', '--branch', 'ci-runner-4/hyci-88', '--limit', '5', '--json', 'databaseId,conclusion,headSha'], { cwd: root, encoding: 'utf8' });
  const done = JSON.parse(out).filter(run => run.conclusion);
  if (!done.length) {
    console.log('AFTER_PENDING no completed run yet');
    process.exit(1);
  }
  const view = JSON.parse(execFileSync('gh', ['run', 'view', String(done[0].databaseId), '--json', 'jobs,headSha'], { cwd: root, encoding: 'utf8' }));
  const seconds = job => (new Date(job.completedAt) - new Date(job.startedAt)) / 1000;
  const slowest = view.jobs.map(job => ({ name: job.name, seconds: job.conclusion === 'skipped' ? 0 : seconds(job) })).sort((a, b) => b.seconds - a.seconds)[0];
  const total = Math.max(...view.jobs.map(seconds));
  console.log(`AFTER_MEASURED run=${done[0].databaseId} slowest=${slowest.name} ${Math.round(slowest.seconds / 60)}m${Math.round(slowest.seconds % 60)}s total=${Math.round(total / 60)}m${Math.round(total % 60)}s`);
}


const gateModule = () => import(path.join(root, 'tools/pr-checks.mjs'));

function testSource() {
  return read('tools/pr-checks.test.js');
}

function testfix() {
  const source = testSource();
  const problems = [];
  if (!/requiredChecks\.filter\(name => !present\.has\(name\)\)/.test(source)) problems.push('expectation is not derived from requiredChecks and the fixture');
  if (/missing \\\[test, image-preview-emulator/.test(source)) problems.push('hardcoded old check order is still there');
  if (!/assert\.ok\(missing\.includes\('test'\)/.test(source)) problems.push('fixture is no longer required to miss the long check');
  if (problems.length) {
    console.log(problems.join(' | '));
    process.exit(1);
  }
  console.log('TEST_EXPECTATION_DERIVED');
}

async function negative() {
  const { checkStatus, requiredChecks } = await gateModule();
  const fixture = JSON.parse(read('tools/fixtures/pr-checks/88904b33.json'));
  const status = checkStatus(fixture);
  const present = new Set(fixture.statusCheckRollup.filter(check => check.__typename === 'CheckRun').map(check => check.name));
  const missing = requiredChecks.filter(name => !present.has(name));
  const expected = `Waiting: missing [${missing.join(', ')}],`;
  const ok = status.ready === false && status.message.startsWith(expected) && missing.includes('test');
  console.log(ok ? 'NEGATIVE_CONTROL_KEPT' : `NEGATIVE_BROKEN ready=${status.ready} message=${status.message.slice(0, 120)}`);
  if (!ok) process.exit(1);
}

async function green() {
  const { requiredChecks } = await gateModule();
  const out = execFileSync('gh', ['pr', 'view', '204', '--repo', 'valentinyeo/multiprompt-android', '--json', 'headRefOid,state,statusCheckRollup'], { cwd: root, encoding: 'utf8' });
  const pr = JSON.parse(out);
  const local = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const checks = pr.statusCheckRollup ?? [];
  const bad = requiredChecks.filter(name => !checks.some(check => check.__typename === 'CheckRun' && check.name === name && check.status === 'COMPLETED' && check.conclusion === 'SUCCESS'));
  const ok = ['OPEN', 'MERGED'].includes(pr.state) && pr.headRefOid === local && bad.length === 0;
  console.log(ok ? `CHECKS_GREEN ${requiredChecks.length}/${requiredChecks.length} head=${pr.headRefOid}` : `CHECKS_NOT_GREEN head=${pr.headRefOid} local=${local} state=${pr.state} bad=${bad.join(',')}`);
  if (!ok) process.exit(1);
}

async function afterRun() {
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const list = JSON.parse(execFileSync('gh', ['run', 'list', '--workflow=test.yml', '--branch', 'ci-runner-4/hyci-88', '--limit', '10', '--json', 'databaseId,conclusion,headSha'], { cwd: root, encoding: 'utf8' }));
  const run = list.find(entry => entry.headSha === head && entry.conclusion);
  if (!run) {
    console.log('AFTER_PENDING no completed run for this head yet');
    process.exit(1);
  }
  const view = JSON.parse(execFileSync('gh', ['run', 'view', String(run.databaseId), '--json', 'jobs,conclusion'], { cwd: root, encoding: 'utf8' }));
  const seconds = job => (new Date(job.completedAt) - new Date(job.startedAt)) / 1000;
  const ran = view.jobs.filter(job => job.conclusion !== 'skipped');
  const total = Math.max(...ran.map(seconds));
  const slowest = ran.map(job => ({ name: job.name, seconds: seconds(job) })).sort((a, b) => b.seconds - a.seconds)[0];
  const stamp = value => `${Math.floor(value / 60)}m${Math.round(value % 60)}s`;
  const ok = view.conclusion === 'success' && total < 600;
  console.log(`AFTER_MEASURED run=${run.databaseId} slowest=${slowest.name} ${stamp(slowest.seconds)} total=${stamp(total)} under_ten=${ok ? 'yes' : 'no'}`);
  if (!ok) process.exit(1);
}

function ticketNumbers() {
  const out = execFileSync('hypertask', ['comments', 'list', 'HYCI-88', '--json'], { cwd: root, encoding: 'utf8' });
  const comments = (JSON.parse(out).comments ?? []).filter(item => item.kind === 'done' || /After run/.test(item.text ?? ''));
  const text = comments.map(item => item.text ?? '').join('\n');
  const ok = /18 minutes 40 seconds/.test(text) && /9 minutes 24 seconds/.test(text)
    && /18 minutes 29 seconds/.test(text) && /7 minutes 53 seconds/.test(text)
    && /https:\/\/github\.com\/valentinyeo\/multiprompt-android\/actions\/runs\/\d+/.test(text)
    && /https:\/\/app\.hypertask\.ai\/detail\/project-11514\/88/.test(text);
  console.log(ok ? 'TICKET_NUMBERS' : `TICKET_MISSING_NUMBERS ${text.slice(0, 200)}`);
  if (!ok) process.exit(1);
}

const commands = { ticket, checks, workflow, required, pr, testfix, negative, green, ticketnumbers: ticketNumbers };
commands.after = afterRun;
commands[process.argv[2]]();
