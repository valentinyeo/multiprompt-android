const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
const verifier = require('../.github/scripts/pstack-verifier/verifier.cjs');

const root = path.join(__dirname, '..');
const sha = 'a'.repeat(40);
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const tool = path.join(root, '.github/scripts/pstack-verifier/coverage.cjs');
const productDiff = (file, text) => `diff --git a/app/src/main/${file} b/app/src/main/${file}\n--- a/app/src/main/${file}\n+++ b/app/src/main/${file}\n@@ -1 +1 @@\n-old\n+${text}\n`;

for (const [pr, outcome] of [[380, 'chat-search.open'], [376, 'snippets.save-reuse-delete']]) {
  test(`real PR #${pr} is rejected despite its successful unrelated head journeys`, () => {
    const fixture = JSON.parse(read(`tools/fixtures/feature-coverage/pr-${pr}.json`));
    const diff = read(`tools/fixtures/feature-coverage/pr-${pr}.diff`);
    const features = [...fixture.verifierBody.matchAll(/^- `([^`]+)` parent: .+, head: passed$/gm)].map(match => match[1]);
    if (pr === 380) assert.ok(features.includes('session-reader.md'));
    if (pr === 376) assert.ok(features.includes('native-skills.md'));
    assert.match(fixture.verifierBody, /^pstack-verifier: PASS\+NOTES/);
    assert.ok(fixture.verifierBody.includes(fixture.headSha));
    const plan = gate.planChange(diff, []);
    assert.ok(plan.outcomes.includes(outcome));
    const conclusions = Object.fromEntries(features.map(feature => [feature, 'success']));
    const args = { modelVerdict: 'PASS+NOTES', features, conclusions };
    assert.equal(verifier.applyRules(args).verdict, 'PASS+NOTES');
    const featureProof = gate.featureErrors({ plan, conclusions, sha: fixture.headSha });
    const ruled = verifier.applyRules({ ...args, featureProof });
    assert.equal(ruled.verdict, 'FAIL');
    assert.match(ruled.notes.join('\n'), new RegExp(`FEATURE_NOT_EXERCISED: ${outcome}`));
    console.log(`HISTORICAL_FEATURE_PROOF_REJECTED: PR #${pr} @ ${fixture.headSha}, previously PASS+NOTES, now FAIL for ${outcome}`);
  });
}

test('PR #376 unreachable Snippets row fails even when both unrelated journeys pass', () => {
  const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-376.json'));
  assert.match(fixture.unreachableBody, /^pstack-verifier: FAIL/);
  const features = [...fixture.unreachableBody.matchAll(/^- `([^`]+)` parent: .+, head: passed$/gm)].map(match => match[1]);
  assert.deepEqual(new Set(features), new Set(['native-skills.md', 'session-reader.md']));
  const conclusions = Object.fromEntries(features.map(feature => [feature, 'success']));
  const plan = gate.planChange(read('tools/fixtures/feature-coverage/pr-376.diff'), []);
  const featureProof = gate.featureErrors({ plan, conclusions, sha });
  assert.equal(verifier.applyRules({ modelVerdict: 'PASS', features, conclusions, featureProof }).verdict, 'FAIL');
  assert.match(featureProof.errors.join(' '), /snippets.save-reuse-delete/);
});

test('unknown product changes fail closed and cannot be called tooling', () => {
  const diff = productDiff('new-feature.kt', 'a new feature');
  const plan = gate.planChange(diff, []);
  assert.equal(plan.product, true);
  assert.match(gate.featureErrors({ plan, sha }).errors.join('\n'), /FEATURE_UNMAPPED/);
  assert.throws(() => gate.planChange(diff, ['unmapped']), /FEATURE_UNMAPPED/);
  assert.throws(() => gate.planChange(diff, null), /FEATURE_UNMAPPED/);
});

test('only product additions and removals identify outcomes, not test code or patch context', () => {
  const diff = 'diff --git a/app/src/test/ChatFindTest.kt b/app/src/test/ChatFindTest.kt\n+ChatFind\n';
  assert.deepEqual(gate.changedOutcomes(diff), []);
  assert.deepEqual(gate.changedOutcomes(productDiff('ui/Example.kt', 'nothing').replace('-old', ' ChatFind\n-old')), []);
  assert.ok(gate.changedOutcomes(productDiff('ui/Example.kt', 'nothing').replace('-old', '-ChatFind')).includes('chat-search.open'));
});

test('native-skills smoke does not cover /clear and name-first ranking', () => {
  const plan = gate.planChange(productDiff('skills/NativeSkills.kt', '"/clear"'), []);
  assert.ok(plan.outcomes.includes('claude.clear-ranking'));
  const featureProof = gate.featureErrors({ plan, conclusions: { 'native-skills.md': 'success' }, sha });
  assert.match(featureProof.errors.join('\n'), /FEATURE_NOT_EXERCISED: claude.clear-ranking/);
  assert.equal(verifier.applyRules({ modelVerdict: 'PASS+NOTES', features: ['native-skills.md'], conclusions: { 'native-skills.md': 'success' }, smoke: true, featureProof }).verdict, 'FAIL');
});

function withProof(run) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'feature-proof-'));
  const outcome = 'reader.send-enter-interrupt';
  const journey = 'session-reader.md';
  const claim = {
    outcome, journey,
    action: { file: 'driver-transcript.txt', quote: '+ tap_desc Send prompt' },
    result: { file: 'send-result.xml', quote: 'VERIFY_RECEIVED:VERIFY_SENT_528' },
    sideEffect: { file: 'receipts.txt', quote: 'VERIFY_RECEIVED:VERIFY_SENT_528' },
  };
  fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
  fs.writeFileSync(path.join(directory, claim.action.file), `${claim.action.quote}\n`);
  fs.writeFileSync(path.join(directory, claim.result.file), `<node text="${claim.result.quote}"/>`);
  fs.writeFileSync(path.join(directory, claim.sideEffect.file), `${claim.sideEffect.quote}\n`);
  const args = { plan: { product: true, outcomes: [outcome] }, coverage: [claim], directories: { [journey]: directory }, conclusions: { [journey]: 'success' }, sha };
  try { run(args, directory); } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}

test('matching successful proof requires action, resulting UI, independent receipt and exact head SHA', () => {
  withProof(args => {
    const proof = gate.featureErrors(args);
    assert.deepEqual(proof.errors, []);
    assert.equal(proof.accepted[0].sha, sha);
    for (const parent of ['passed', 'not passed', 'no result']) {
      assert.equal(verifier.applyRules({ modelVerdict: 'PASS', features: ['session-reader.md'], conclusions: args.conclusions, parents: { 'session-reader.md': parent }, featureProof: proof }).verdict, 'PASS');
    }
  });
});

test('missing, fabricated, stale, cross-journey and unretained proof fails even with a model PASS', () => {
  const mutations = [
    args => { args.coverage = []; },
    args => { args.coverage[0].journey = 'native-skills.md'; },
    args => { args.coverage[0].action.quote = 'an invented action'; },
    args => { args.coverage[0].result.quote = 'an invented result'; },
    args => { delete args.coverage[0].sideEffect; },
    args => { args.coverage[0].sideEffect = args.coverage[0].action; },
    args => { args.coverage[0].result = args.coverage[0].action; },
    args => { args.sha = 'b'.repeat(40); },
    args => { args.conclusions['session-reader.md'] = 'failure'; },
    args => { args.directories = {}; },
    (args, directory) => { fs.unlinkSync(path.join(directory, 'send-result.xml')); },
    args => { args.coverage[0].action.file = '../outside.txt'; },
    args => { args.coverage[0].action.quote = 'tap'; },
  ];
  for (const mutate of mutations) withProof((args, directory) => {
    mutate(args, directory);
    const featureProof = gate.featureErrors(args);
    assert.equal(featureProof.errors.length, 1, mutate.toString());
    assert.equal(verifier.applyRules({ modelVerdict: 'PASS', features: ['session-reader.md'], conclusions: args.conclusions, featureProof }).verdict, 'FAIL');
  });
});

test('tooling-only work needs no Android feature proof or artificial failing parent', () => {
  const diff = 'diff --git a/tools/check.js b/tools/check.js\n+new check\n';
  const plan = gate.planChange(diff, []);
  assert.equal(plan.product, false);
  assert.deepEqual(gate.featureErrors({ plan, sha }).errors, []);
});

test('the ratchet tracks exact IDs, not the number of missing journeys', () => {
  assert.deepEqual(gate.validateRegistry(), []);
  const changed = structuredClone(gate.registry);
  const gaps = structuredClone(gate.baseline);
  delete changed.outcomes['chat-search.open'];
  delete gaps['chat-search.open'];
  changed.outcomes['brand-new.feature'] = { description: 'New behavior', journey: null, sideEffect: false, match: [] };
  assert.match(gate.validateRegistry(changed, gaps).join('\n'), /FEATURE_COVERAGE_RATCHET: new uncovered outcome brand-new.feature/);
  gaps['brand-new.feature'] = { cleanupTicket: 'SOFT-2531' };
  assert.match(gate.ratchetErrors(gate.baseline, gaps).join('\n'), /brand-new.feature/);
  assert.deepEqual(gate.ratchetErrors(gate.baseline, {}), []);
});

test('priority evidence paths and per-file limits fit the journey budget', () => {
  for (const evidence of ['receipts.txt', ['proof.txt'], { '../secret.txt': 1500 }, { '/secret.txt': 1500 }, { 'proof.png': 1500 },
    { 'proof.json': 0 }, { 'proof.json': 6001 }, { 'proof.json': 1.5 }, { 'proof.json': '1500' },
    { 'one.json': 6000, 'two.json': 6000, 'three.json': 1 }, Object.fromEntries(Array.from({ length: 9 }, (_, n) => [`proof-${n}.json`, 1]))]) {
    const changed = structuredClone(gate.registry);
    changed.journeys['chat-queued.md'].evidence = evidence;
    assert.match(gate.validateRegistry(changed).join('\n'), /evidence must map at most eight artifact text filenames to character limits/);
  }
});

test('every recipe is registered and generated index and dispatch choices cannot drift', () => {
  const recipes = fs.readdirSync(path.join(root, gate.REGISTRY_PATH)).filter(name => name.endsWith('.md') && name !== 'README.md');
  assert.deepEqual(recipes.sort(), Object.keys(gate.registry.journeys).sort());
  assert.equal(read(`${gate.REGISTRY_PATH}/README.md`), gate.renderIndex());
  const workflow = read('.github/workflows/verify.yml');
  assert.equal(workflow, gate.renderWorkflow(workflow));
  assert.notEqual(workflow.replace('          - hosts.md\n', ''), gate.renderWorkflow(workflow.replace('          - hosts.md\n', '')));
  const run = spawnSync(process.execPath, [tool, 'check'], { cwd: root, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
});

test('signed, dedicated and manual recipes stay visible without entering the debug picker', () => {
  for (const name of ['robot-faces.md', 'session-settings.md', 'chat-working.md', 'app-name.md']) {
    assert.ok(gate.catalog().includes(name));
    assert.ok(!gate.debugJourneys.includes(name));
    assert.notEqual(gate.registry.journeys[name].runner.kind, 'debug');
  }
  assert.ok(gate.catalog().includes('chat-codex.md'));
  assert.ok(gate.catalog().includes('session-summaries.md'));
  for (const name of ['session-reader.md', 'robot-faces.md']) {
    assert.equal(spawnSync(process.execPath, [tool, 'journey', name]).status, 0);
  }
  for (const name of ['../secret.md', 'session-settings.md', 'bot-photo-refresh.md']) {
    const result = spawnSync(process.execPath, [tool, 'journey', name], { encoding: 'utf8' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /FEATURE_NOT_DISPATCHABLE/);
  }
});

test('the real judge command fails a past search change despite model PASS and a green reader', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'feature-judge-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-380.json'));
    const gh = path.join(directory, 'gh');
    fs.writeFileSync(gh, `#!${process.execPath}\nconst fs = require('node:fs');\nconst path = require('node:path');\nconst args = process.argv.slice(2);\nif (args[0] === 'run') {\n  const dir = args[args.indexOf('-D') + 1];\n  fs.mkdirSync(dir, {recursive:true});\n  fs.writeFileSync(path.join(dir, 'app-source-sha.txt'), process.env.HEAD_SHA);\n  fs.writeFileSync(path.join(dir, 'tooling-proof.txt'), process.env.MISSING_TOOLING ? '' : 'Node tests passed');\n} else if (args.some(arg => arg.includes('/jobs?'))) {\n  console.log(JSON.stringify([{jobs:[{name:'journey session-reader.md / verify',status:'completed',conclusion:'success'}]}]));\n} else if (args.includes('Accept: application/vnd.github.diff')) {\n  process.stdout.write(fs.readFileSync(process.env.FIXTURE_DIFF, 'utf8'));\n} else {\n  console.log(JSON.stringify({number:380,title:'Find inside a chat',body:'',head:{sha:process.env.HEAD_SHA}}));\n}\n`);
    fs.chmodSync(gh, 0o755);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({verdict:'PASS',summary:'The reader passed.',coverage:[],changed_outcomes:[]})}}]})});`);
    const report = path.join(directory, 'coverage.json');
    const options = {
      cwd: directory,
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${directory}:${process.env.PATH}`,
        NODE_OPTIONS: `--require=${stub}`,
        DEEPSEEK_API_KEY: 'test-only',
        PICK_RESULT: 'success',
        REPO: 'example/repo',
        PR_NUMBER: '380',
        HEAD_SHA: fixture.headSha,
        MERGE_BASE: 'b'.repeat(40),
        RUN_ID: '1',
        RUN_URL: 'https://github.com/example/repo/actions/runs/1',
        FEATURES: '["session-reader.md"]',
        CHANGED_OUTCOMES: '[]',
        FIXTURE_DIFF: path.join(root, 'tools/fixtures/feature-coverage/pr-380.diff'),
        COVERAGE_REPORT: report,
        VERIFIER_DRY: '1',
      },
    };
    const args = [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'judge'];
    const result = spawnSync(process.execPath, args, options);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stdout, /pstack-verifier: FAIL @/);
    assert.match(result.stdout, /FEATURE_NOT_EXERCISED: chat-search.open/);
    const recorded = JSON.parse(fs.readFileSync(report, 'utf8'));
    assert.equal(recorded.sha, fixture.headSha);
    assert.deepEqual(recorded.accepted, []);
    assert.ok(recorded.plan.outcomes.includes('chat-search.open'));
    options.env.MISSING_TOOLING = '1';
    const missing = spawnSync(process.execPath, args, options);
    assert.equal(missing.status, 1);
    assert.match(missing.stderr, /NON_UI_PROOF_MISSING/);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('pick and judge exchange required IDs and retain both tooling and outcome evidence', () => {
  const workflow = read('.github/workflows/pstack-verifier.yml');
  assert.match(workflow, /changed_outcomes: \$\{\{ steps.pick.outputs.changed_outcomes \}\}/);
  assert.match(workflow, /CHANGED_OUTCOMES: \$\{\{ needs.pick.outputs.changed_outcomes \}\}/);
  assert.match(workflow, /name: verifier-feature-coverage/);
  assert.match(workflow, /name: verifier-tooling-proof/);
  assert.match(workflow, /node --test tools\/feature-coverage.test.js tools\/pstack-verifier.test.js/);
});

test('the real picker schedules snippets even when the model omits it or fills all three slots', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'snippets-pick-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-376.json'));
    const gh = path.join(directory, 'gh');
    fs.writeFileSync(gh, `#!${process.execPath}\nconst fs = require('node:fs');\nconst args = process.argv.slice(2);\nif (args.includes('Accept: application/vnd.github.diff')) process.stdout.write(fs.readFileSync(process.env.FIXTURE_DIFF));\nelse if (args[1].includes('/compare/')) console.log(JSON.stringify({merge_base_commit:{sha:'b'.repeat(40)}}));\nelse console.log(JSON.stringify({number:376,state:'open',head:{sha:process.env.EVENT_HEAD_SHA,repo:{full_name:'example/repo'}},base:{ref:'main'}}));\n`);
    fs.chmodSync(gh, 0o755);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted snippets.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/snippets.md b/${gate.REGISTRY_PATH}/snippets.md\n+Save and reuse snippets\n`);
    for (const diff of [path.join(root, 'tools/fixtures/feature-coverage/pr-376.diff'), recipeDiff]) {
      for (const modelFeatures of [[], ['native-skills.md', 'session-reader.md', 'terminal.md']]) {
        const output = path.join(directory, 'outputs');
        fs.writeFileSync(output, '');
        const result = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'pick'], {
          cwd: root,
          encoding: 'utf8',
          env: {
            ...process.env,
            PATH: `${directory}:${process.env.PATH}`,
            NODE_OPTIONS: `--require=${stub}`,
            DEEPSEEK_API_KEY: 'test-only',
            REPO: 'example/repo',
            PR_NUMBER: '376',
            EVENT_HEAD_SHA: fixture.headSha,
            FIXTURE_DIFF: diff,
            MODEL_FEATURES: JSON.stringify(modelFeatures),
            CHANGE_KIND: diff === recipeDiff ? 'tooling' : 'product',
            GITHUB_OUTPUT: output,
          },
        });
        assert.equal(result.status, 0, result.stderr);
        const values = fs.readFileSync(output, 'utf8');
        const features = JSON.parse(values.match(/^features=(.+)$/m)[1]);
        assert.equal(features[0], 'snippets.md', values);
        assert.ok(features.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*snippets.save-reuse-delete/);
      }
    }
    assert.equal(gate.baseline['snippets.save-reuse-delete'], undefined);
    assert.equal(gate.registry.outcomes['snippets.save-reuse-delete'].journey, 'snippets.md');
    assert.ok(gate.debugJourneys.includes('snippets.md'));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
