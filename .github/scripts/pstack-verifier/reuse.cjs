'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const shaPattern = /^[a-f0-9]{40}$/;
const gh = (args, input) => execFileSync('gh', args, { encoding: 'utf8', input, maxBuffer: 32 * 1024 * 1024 });
const api = endpoint => JSON.parse(gh(['api', endpoint]));
const git = args => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }).trim();

function patchId(base, head) {
  if (![base, head].every(sha => shaPattern.test(sha))) throw new Error('Expected exact patch revisions');
  const diff = git(['diff', '--no-ext-diff', '--no-textconv', '--no-renames', '--binary', '--full-index', base, head]);
  return execFileSync('git', ['patch-id', '--stable'], { encoding: 'utf8', input: diff + '\n' }).trim().split(' ')[0];
}

function reusable(record, current, run) {
  return record?.version === 1 && record.repo === current.repo && record.pr === current.pr
    && [record.sha, record.base, record.mergeBase, record.patchId].every(value => shaPattern.test(value ?? ''))
    && ['PASS', 'PASS+NOTES'].includes(record.verdict)
    && record.sha !== current.sha && record.mergeBase !== current.mergeBase
    && record.patchId === current.patchId
    && run.id === record.runId && run.id !== current.runId
    && run.conclusion === 'success' && run.status === 'completed'
    && run.path === '.github/workflows/pstack-verifier.yml'
    && ['pull_request', 'workflow_dispatch'].includes(run.event)
    && run.head_sha === record.sha && run.head_repository?.full_name === current.repo;
}

function verdictComment(comment, sha, verdict, runUrl) {
  return comment?.user?.login === 'github-actions[bot]'
    && comment.body?.startsWith(`pstack-verifier: ${verdict} @ ${sha}\n`)
    && comment.body.includes(`<!-- pstack-verifier:v1 sha=${sha} -->`)
    && comment.body.includes(`\nRun: ${runUrl}\n`);
}

function reusedComment(record, current, original) {
  const runUrl = `https://github.com/${current.repo}/actions/runs/${current.runId}`;
  return [
    `pstack-verifier: PASS @ ${current.sha}`, '',
    `Reused ${record.verdict} verdict sha ${record.sha}; git patch-id --stable ${record.patchId} is unchanged after rebasing onto main.`,
    `Original verdict: ${original.html_url}`, '',
    original.body.split('\n').slice(1).join('\n')
      .replace(`<!-- pstack-verifier:v1 sha=${record.sha} -->`, '')
      .replace(/\nRun: https:\/\/github\.com\/[^\s]+\n/, '\nOriginal verification run: ' + `https://github.com/${current.repo}/actions/runs/${record.runId}\n`),
    `Run: ${runUrl}`, '', `<!-- pstack-verifier:v1 sha=${current.sha} -->`,
  ].join('\n');
}

function context(env) {
  const repo = env.REPO;
  const number = env.PR_NUMBER;
  if (!/^[\w.-]+\/[\w.-]+$/.test(repo ?? '') || !/^[1-9]\d*$/.test(number ?? '') || !/^\d+$/.test(env.RUN_ID ?? '')) throw new Error('Missing verifier run identity');
  const pr = api(`repos/${repo}/pulls/${number}`);
  const expected = env.EVENT_NAME === 'workflow_dispatch' ? env.GITHUB_SHA : env.EVENT_HEAD_SHA;
  if (pr.state !== 'open' || pr.base.ref !== 'main'
      || pr.head.sha !== (env.HEAD_SHA || expected)) throw new Error('PR identity or head changed');
  const sha = pr.head.sha;
  const base = pr.base.sha;
  for (const revision of [base, sha]) {
    if (!shaPattern.test(revision)) throw new Error('Missing PR revisions');
    git(['fetch', '--no-tags', 'origin', revision]);
  }
  const mergeBase = git(['merge-base', base, sha]);
  return { repo, pr: Number(number), sha, base, mergeBase, patchId: patchId(mergeBase, sha), runId: Number(env.RUN_ID) };
}

function comments(current) {
  return JSON.parse(gh(['api', '--paginate', '--slurp', `repos/${current.repo}/issues/${current.pr}/comments?per_page=100`])).flat();
}

function output(values, env) {
  for (const [key, value] of Object.entries(values)) fs.appendFileSync(env.GITHUB_OUTPUT, `${key}=${value}\n`);
}

function probe(current) {
  const candidates = comments(current).reverse();
  for (const comment of candidates) {
    const match = comment.body?.match(/^pstack-verifier: (PASS(?:\+NOTES)?) @ ([a-f0-9]{40})\n/);
    const runId = comment.body?.match(new RegExp(`\\nRun: https://github\\.com/${current.repo}/actions/runs/(\\d+)\\n`))?.[1];
    if (!match || !runId || !verdictComment(comment, match[2], match[1], `https://github.com/${current.repo}/actions/runs/${runId}`)) continue;
    const run = api(`repos/${current.repo}/actions/runs/${runId}`);
    if (run.conclusion !== 'success') continue;
    const artifacts = JSON.parse(gh(['api', '--paginate', '--slurp', `repos/${current.repo}/actions/runs/${runId}/artifacts?per_page=100`])).flatMap(page => page.artifacts);
    if (!artifacts.some(artifact => artifact.name === 'verifier-verdict' && !artifact.expired)) continue;
    const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'verifier-reuse-'));
    try {
      gh(['run', 'download', runId, '-R', current.repo, '-n', 'verifier-verdict', '-D', scratch]);
      const record = JSON.parse(fs.readFileSync(path.join(scratch, 'verdict.json'), 'utf8'));
      if (!reusable(record, current, run) || record.sha !== match[2] || record.verdict !== match[1]) continue;
      for (const revision of [record.base, record.sha]) git(['fetch', '--no-tags', 'origin', revision]);
      if (git(['merge-base', record.base, record.sha]) !== record.mergeBase || patchId(record.mergeBase, record.sha) !== record.patchId) continue;
      git(['merge-base', '--is-ancestor', record.base, current.base]);
      git(['merge-base', '--is-ancestor', record.mergeBase, current.mergeBase]);
      return { record, comment };
    } catch (error) {
      console.log(`Verdict ${runId} cannot be reused: ${error.message}`);
    } finally {
      fs.rmSync(scratch, { recursive: true, force: true });
    }
  }
  return null;
}

function main(env, command) {
  const current = context(env);
  if (command === 'save') {
    const verdict = env.VERDICT;
    if (!['PASS', 'PASS+NOTES'].includes(verdict)) throw new Error('Only successful independent verdicts can be retained');
    if (current.mergeBase !== env.MERGE_BASE) throw new Error('Verified merge base changed');
    const runUrl = `https://github.com/${current.repo}/actions/runs/${current.runId}`;
    if (!comments(current).some(comment => verdictComment(comment, current.sha, verdict, runUrl))) throw new Error('No matching independent verdict comment');
    fs.writeFileSync(env.RECORD_PATH, JSON.stringify({ version: 1, ...current, verdict }, null, 2) + '\n');
    return;
  }
  if (!['probe', 'publish'].includes(command)) throw new Error('Usage: reuse.cjs probe|publish|save');
  let hit;
  try { hit = probe(current); } catch (error) {
    if (command === 'publish') throw error;
    console.log(`No reusable proof available: ${error.message}`);
  }
  if (command === 'probe') {
    output({ reused: Boolean(hit), head_sha: current.sha, features: hit ? '[]' : '' }, env);
    if (hit) console.log(`Reusing verdict ${hit.record.sha}, patch-id ${current.patchId}`);
    return;
  }
  if (!hit) throw new Error('Reusable verdict no longer available; rerun verification');
  const body = reusedComment(hit.record, current, hit.comment);
  if (api(`repos/${current.repo}/pulls/${current.pr}`).head.sha !== current.sha) throw new Error('PR head moved before publishing');
  const existing = comments(current).find(comment => comment.user?.login === 'github-actions[bot]' && comment.body?.includes(`<!-- pstack-verifier:v1 sha=${current.sha} -->`));
  gh(['api', '-X', existing ? 'PATCH' : 'POST', existing ? `repos/${current.repo}/issues/comments/${existing.id}` : `repos/${current.repo}/issues/${current.pr}/comments`, '-F', 'body=@-'], body);
  if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY, body + '\n');
  console.log(body);
}

module.exports = { patchId, reusable, verdictComment, reusedComment };
if (require.main === module) {
  try { main(process.env, process.argv[2]); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
