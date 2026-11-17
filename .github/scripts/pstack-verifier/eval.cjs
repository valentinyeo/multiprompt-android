'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const verifier = require('./verifier.cjs');
const coverage = require('./coverage.cjs');

const CASES = path.join(__dirname, 'cases');
const FAMILIES = ['deepseek', 'claude'];

function prepare(sample, directory) {
  const directories = {};
  const parentDirectories = {};
  const conclusions = {};
  const parentConclusions = {};
  const parents = {};
  for (const feature of sample.features) {
    for (const side of ['parent', 'head']) {
      const target = path.join(directory, side, feature);
      fs.mkdirSync(target, { recursive: true });
      for (const [file, text] of Object.entries(sample.evidence[side])) {
        if (!/^[\w.-]+$/.test(file)) throw new Error('Evidence filenames must be flat');
        fs.writeFileSync(path.join(target, file), text);
      }
      (side === 'head' ? directories : parentDirectories)[feature] = target;
    }
    conclusions[feature] = 'success';
    parentConclusions[feature] = 'success';
    const files = verifier.readSideFiles(parentDirectories[feature]);
    parents[feature] = verifier.parentResult({ conclusion: 'success', ...files, mergeBase: 'b'.repeat(40) });
  }
  const plan = coverage.planChange(sample.diff, sample.changedOutcomes);
  const user = verifier.judgeContext({
    pr: sample.pr, diff: sample.diff, plan, features: sample.features,
    toolingProof: 'Hosted source checks completed successfully.\n',
    parents, parentConclusions, conclusions, directories, parentDirectories,
  });
  return { user, plan, directories, conclusions, parents };
}

async function runCase(sample, { ask, output }) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'multiprompt-'));
  try {
    const prepared = prepare(sample, directory);
    fs.writeFileSync(`${output}.request.json`, JSON.stringify({ system: verifier.JUDGE_INSTRUCTIONS, user: prepared.user }, null, 2));
    const raw = await ask({ system: verifier.JUDGE_INSTRUCTIONS, user: prepared.user, cwd: directory });
    fs.writeFileSync(`${output}.response.txt`, raw);
    const model = verifier.parseVerdict(raw);
    const plan = coverage.planChange(sample.diff, [...prepared.plan.outcomes, ...model.changedOutcomes]);
    const featureProof = coverage.featureErrors({ plan, coverage: model.coverage, directories: prepared.directories, conclusions: prepared.conclusions, sha: sample.pr.head.sha });
    const ruled = verifier.applyRules({ modelVerdict: model.verdict, features: sample.features, conclusions: prepared.conclusions, parents: prepared.parents, featureProof });
    return { rawVerdict: model.verdict, verdict: ruled.verdict, summary: model.summary, notes: ruled.notes, featureProof };
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

function grade(results, expected) {
  if (Object.keys(expected).length < 6 || Object.values(expected).filter(value => value === 'FAIL').length < 4 || Object.values(expected).filter(value => value === 'PASS').length < 2) {
    throw new Error('Require at least four planted defects and two clean cases');
  }
  if (Object.keys(results).sort().join() !== Object.keys(expected).sort().join()) throw new Error('Missing or unexpected case results');
  return Object.entries(expected).map(([label, verdict]) => ({
    label, expected: verdict, ...results[label],
    matched: !results[label].error && results[label].rawVerdict === verdict && results[label].verdict === verdict,
  }));
}

function claude({ system, user, cwd }, env = process.env, run = execFileSync) {
  if (!env.CLAUDE_CODE_OAUTH_TOKEN?.trim()) throw new Error('CLAUDE_CODE_OAUTH_TOKEN is missing');
  try {
    return run('claude', [
      '-p', '--model', 'haiku', '--system-prompt', system, '--output-format', 'text',
      '--tools', '', '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',
      '--setting-sources', '', '--disable-slash-commands', '--no-session-persistence',
      '--permission-mode', 'dontAsk', '--max-turns', '1',
    ], {
      input: user, cwd, encoding: 'utf8', timeout: 240_000, maxBuffer: 2 * 1024 * 1024,
      env: { PATH: env.PATH, HOME: env.HOME, CLAUDE_CODE_OAUTH_TOKEN: env.CLAUDE_CODE_OAUTH_TOKEN, DISABLE_AUTOUPDATER: '1' },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (error) {
    throw new Error(`Claude CLI did not complete (status ${error.status ?? error.code ?? 'unknown'})`);
  }
}

async function main(env = process.env) {
  const family = process.argv[2];
  if (!FAMILIES.includes(family)) throw new Error('Choose deepseek or claude');
  const ask = family === 'deepseek'
    ? request => verifier.callDeepSeek({ key: verifier.requireKey(env), system: request.system, user: request.user })
    : request => claude(request, env);
  const output = path.resolve(env.REPORT_DIR || 'build/pstack-verifier');
  fs.mkdirSync(output, { recursive: true });
  const results = {};
  for (const file of fs.readdirSync(CASES).filter(file => file.endsWith('.json')).sort()) {
    const label = path.basename(file, '.json');
    const sample = JSON.parse(fs.readFileSync(path.join(CASES, file), 'utf8'));
    try {
      results[label] = await runCase(sample, { ask, output: path.join(output, label) });
    } catch (error) {
      results[label] = { error: error.message };
    }
    console.log(`${label}: ${results[label].rawVerdict ?? 'ERROR'} / ${results[label].verdict ?? 'ERROR'}`);
  }
  const expected = JSON.parse(fs.readFileSync(path.join(__dirname, 'expected.json'), 'utf8'));
  const rows = grade(results, expected);
  fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({ family, sha: env.GITHUB_SHA, rows }, null, 2));
  const summary = [
    `## ${family}: ${rows.filter(row => row.matched).length}/${rows.length}`,
    '| Case | Expected | Model | Gate | Match |', '| --- | --- | --- | --- | --- |',
    ...rows.map(row => `| ${row.label} | ${row.expected} | ${row.rawVerdict ?? 'ERROR'} | ${row.verdict ?? 'ERROR'} | ${row.matched ? 'yes' : 'NO'} |`),
  ].join('\n') + '\n';
  console.log(summary);
  if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY, summary);
  if (rows.some(row => !row.matched)) process.exitCode = 1;
}

module.exports = { prepare, runCase, grade, claude };

if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
