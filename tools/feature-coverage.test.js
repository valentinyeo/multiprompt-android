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

function mockPickerGithub(file) {
  fs.writeFileSync(file, `#!${process.execPath}
const assert = require('node:assert/strict');
const fs = require('node:fs');
const args = process.argv.slice(2);
const pull = 'repos/example/repo/pulls/' + process.env.PR_NUMBER;
const diff = fs.readFileSync(process.env.FIXTURE_DIFF, 'utf8');
const files = [...diff.matchAll(/^diff --git a\\/.+ b\\/(.+)$/gm)].map(([, filename]) => ({ filename }));
if (args.includes('Accept: application/vnd.github.diff')) {
  assert.deepEqual(args, ['api', pull, '-H', 'Accept: application/vnd.github.diff']);
  process.stdout.write(diff);
} else if (args.at(-1).includes('/files?')) {
  assert.deepEqual(args, ['api', '--paginate', '--slurp', pull + '/files?per_page=100']);
  console.log(JSON.stringify([files]));
} else if (args[1].includes('/compare/')) {
  assert.deepEqual(args, ['api', 'repos/example/repo/compare/main...' + process.env.EVENT_HEAD_SHA]);
  console.log(JSON.stringify({ merge_base_commit: { sha: 'b'.repeat(40) } }));
} else {
  assert.deepEqual(args, ['api', pull]);
  console.log(JSON.stringify({ number: Number(process.env.PR_NUMBER), state: 'open', changed_files: files.length,
    head: { sha: process.env.EVENT_HEAD_SHA, repo: { full_name: 'example/repo' } }, base: { ref: 'main' } }));
}
`, { mode: 0o755 });
}

for (const [pr, outcome] of [[380, 'chat-search.open'], [376, 'snippets.save-reuse-delete'], [373, 'ai.switch'], [377, 'ai.usage'], [378, 'skills.pills'], [379, 'chat-table.expand'], [381, 'claude.clear-ranking']]) {
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

test('PR #391 reader lifecycle exposes citable actions, resumed UI and independent host counts', () => {
  const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-391-reader.json'));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-lifecycle-proof-'));
  const claim = fixture.coverage;
  try {
    for (const [name, text] of Object.entries(fixture.files)) fs.writeFileSync(path.join(directory, name), text);
    const priority = gate.registry.journeys[claim.journey].evidence;
    for (const file of ['reader-ui.json', 'hax-hidden-before.json', 'hax-hidden-after.json', 'hax-resumed.json', 'reader-second-close-receipt.txt']) {
      assert.ok(Object.hasOwn(priority, file), `${file} must reach the independent judge`);
    }
    const evidence = verifier.journeyEvidence(directory, claim.journey);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) assert.ok(evidence.includes(ref.quote), ref.file);
    const args = {
      plan: { product: true, outcomes: [claim.outcome] }, coverage: [claim],
      directories: { [claim.journey]: directory }, conclusions: { [claim.journey]: 'success' }, sha: fixture.sha,
    };
    const proof = gate.featureErrors(args);
    assert.deepEqual(proof.errors, []);
    assert.equal(proof.accepted[0].outcome, 'reader.lifecycle');
    fs.writeFileSync(path.join(directory, 'hax-hidden-result.txt'), 'READER_HIDDEN_OK: counts unchanged');
    args.coverage = [{ ...claim, sideEffect: { file: 'hax-hidden-result.txt', quote: 'READER_HIDDEN_OK: counts unchanged' } }];
    assert.equal(gate.featureErrors(args).errors.length, 1);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('PR #391 session-reader citations match multiline UI text and independent receipts verbatim', () => {
  const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-391-session-reader.json'));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'session-reader-proof-'));
  const claim = fixture.coverage;
  try {
    for (const [name, text] of Object.entries(fixture.files)) fs.writeFileSync(path.join(directory, name), text);
    const catalog = gate.catalog();
    const evidence = verifier.journeyEvidence(directory, claim.journey);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) {
      assert.ok(catalog.includes(ref.file), `${ref.file} needs an outcome citation`);
      assert.ok(evidence.includes(ref.quote), ref.file);
    }
    for (const marker of ['VERIFY_RECEIVED:VERIFY_SENT_528', 'VERIFY_ENTER_528', 'VERIFY_INTERRUPTED_528']) {
      assert.ok(catalog.includes(marker), `${marker} needs a literal outcome citation`);
    }
    const args = {
      plan: { product: true, outcomes: [claim.outcome] }, coverage: [claim],
      directories: { [claim.journey]: directory }, conclusions: { [claim.journey]: 'success' }, sha: fixture.sha,
    };
    assert.deepEqual(gate.featureErrors(args).errors, []);
    args.coverage = [{ ...claim, result: { ...claim.result, quote: 'text="VERIFY_INTERRUPTED_528"' } }];
    assert.equal(gate.featureErrors(args).errors.length, 1);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
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

test('chat search changes require their own phone journey even when the model omits it', () => {
  const outcome = 'chat-search.open';
  const journey = 'chat-search.md';
  assert.equal(gate.baseline[outcome], undefined);
  assert.equal(gate.registry.outcomes[outcome].journey, journey);
  assert.ok(gate.debugJourneys.includes(journey));
  const diffs = [read('tools/fixtures/feature-coverage/pr-380.diff'), ...[
    ['ui/ChatFind.kt', 'ChatFind'],
    ['ui/ChatFindMarks.kt', 'marking('],
    ['ui/ChatHistoryList.kt', 'findQuery'],
    ['ui/MultipromptApp.kt', 'ReaderSearchBar'],
    ['ui/MultipromptApp.kt', 'Search in chat'],
    ['ui/MultipromptApp.kt', 'searchIndex'],
  ].map(([file, text]) => productDiff(file, text))];
  for (const diff of diffs) {
    const plan = gate.planChange(diff, []);
    assert.ok(plan.outcomes.includes(outcome));
    for (const features of [[], ['native-skills.md', 'session-reader.md', 'terminal.md']]) {
      const picked = verifier.includeRequiredJourneys({ features, dropped: [], smoke: true }, plan);
      assert.ok(picked.features.includes(journey));
      assert.equal(picked.smoke, false);
      assert.ok(picked.features.length <= verifier.MAX_JOURNEYS);
    }
    const proof = gate.featureErrors({ plan, sha, conclusions: { 'session-reader.md': 'success' } });
    assert.match(proof.errors.join('\n'), /FEATURE_NOT_EXERCISED: chat-search.open/);
  }
});

test('the real picker schedules the changed chat search recipe for a tooling-only PR', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'search-recipe-pick-'));
  try {
    const diff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(diff, `diff --git a/${gate.REGISTRY_PATH}/chat-search.md b/${gate.REGISTRY_PATH}/chat-search.md\n+Find words inside a chat\n`);
    const gh = path.join(directory, 'gh');
    mockPickerGithub(gh);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch=async()=>({status:200,ok:true,json:async()=>({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:'tooling',reason:'Tooling only.'})}}]})});`);
    for (const features of [[], ['native-skills.md', 'session-reader.md', 'terminal.md']]) {
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
          PR_NUMBER: '397',
          EVENT_HEAD_SHA: sha,
          FIXTURE_DIFF: diff,
          MODEL_FEATURES: JSON.stringify(features),
          GITHUB_OUTPUT: output,
        },
      });
      assert.equal(result.status, 0, result.stderr);
      const values = fs.readFileSync(output, 'utf8');
      const selected = JSON.parse(values.match(/^features=(.+)$/m)[1]);
      assert.equal(selected[0], 'chat-search.md', values);
      assert.ok(selected.length <= verifier.MAX_JOURNEYS);
      assert.match(values, /^smoke=no$/m);
      assert.match(values, /^changed_outcomes=\[\]$/m);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
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
    mockPickerGithub(gh);
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

test('snippet citation hints reach the judge and require exact retained head actions, UI and receipts', () => {
  const outcome = 'snippets.save-reuse-delete';
  const journey = 'snippets.md';
  const citations = gate.registry.outcomes[outcome].citations;
  assert.ok(citations, 'Register explicit snippet action, result and side-effect citations');
  assert.ok(gate.catalog().includes(JSON.stringify(citations)), 'The judge must receive the registered citation hints');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'snippets-citations-'));
  try {
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
    fs.writeFileSync(path.join(directory, 'driver-transcript.txt'), '++ tap_text VERIFY_SNIPPET_2537\n');
    fs.writeFileSync(path.join(directory, 'snippets-state.json'), JSON.stringify({ reused: { composer: ['VERIFY_SNIPPET_2537'] } }, null, 2));
    fs.writeFileSync(path.join(directory, 'snippets-receipts.txt'), 'VERIFY_RECEIVED:VERIFY_SNIPPET_2537\n');
    const args = {
      plan: { product: true, outcomes: [outcome] },
      coverage: [{ outcome, journey, ...citations }],
      directories: { [journey]: directory },
      conclusions: { [journey]: 'success' },
      sha,
    };
    assert.equal(gate.featureErrors(args).accepted.length, 1);
    assert.equal(gate.featureErrors({ ...args, coverage: [] }).errors.length, 1);
    assert.equal(gate.featureErrors({ ...args, sha: 'b'.repeat(40) }).errors.length, 1);
    for (const file of ['driver-transcript.txt', 'snippets-state.json', 'snippets-receipts.txt']) {
      const full = path.join(directory, file);
      const original = fs.readFileSync(full, 'utf8');
      fs.writeFileSync(full, 'Missing snippet proof');
      assert.equal(gate.featureErrors(args).errors.length, 1, file);
      fs.writeFileSync(full, original);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the real picker schedules AI switch even when the model omits it or fills all three slots', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-switch-pick-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-373.json'));
    const gh = path.join(directory, 'gh');
    mockPickerGithub(gh);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted AI switch.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/ai-switch.md b/${gate.REGISTRY_PATH}/ai-switch.md\n+Switch Claude to Pi and send a message\n`);
    for (const diff of [path.join(root, 'tools/fixtures/feature-coverage/pr-373.diff'), recipeDiff]) {
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
            PR_NUMBER: '373',
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
        assert.ok(features.includes('ai-switch.md'), values);
        assert.ok(features.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*ai.switch/);
      }
    }
    assert.equal(gate.baseline['ai.switch'], undefined);
    assert.equal(gate.registry.outcomes['ai.switch'].journey, 'ai-switch.md');
    assert.ok(gate.debugJourneys.includes('ai-switch.md'));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the real picker schedules Claude remote control even when the model omits it or fills all three slots', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'remote-control-pick-'));
  try {
    const fixture = { headSha: sha };
    const product = path.join(directory, 'product.diff');
    fs.writeFileSync(product, productDiff('assets/chat_history_reader.py', 'def host_claude_open(): remote_control'));
    const gh = path.join(directory, 'gh');
    mockPickerGithub(gh);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted Claude remote control.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/claude-remote-control.md b/${gate.REGISTRY_PATH}/claude-remote-control.md\n+Open Claude remote control\n`);
    for (const diff of [product, recipeDiff]) {
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
            PR_NUMBER: '366',
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
        assert.ok(features.includes('claude-remote-control.md'), values);
        assert.ok(features.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*claude.remote-control/);
      }
    }
    assert.equal(gate.baseline['claude.remote-control'], undefined);
    assert.equal(gate.registry.outcomes['claude.remote-control'].journey, 'claude-remote-control.md');
    assert.ok(gate.debugJourneys.includes('claude-remote-control.md'));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});


test('Claude remote-control proof requires its action, connected UI, independent host receipt and exact head', () => {
  const outcome = 'claude.remote-control';
  const journey = 'claude-remote-control.md';
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'remote-control-proof-'));
  const claim = { outcome, journey, ...gate.registry.outcomes[outcome].citations };
  try {
    const priority = gate.registry.journeys[journey].evidence;
    assert.ok(priority['remote-control-state.json']);
    assert.ok(priority['remote-control-host.json']);
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    const args = { plan: { product: true, outcomes: [outcome] }, coverage: [claim], directories: { [journey]: directory }, conclusions: { [journey]: 'success' }, sha };
    assert.deepEqual(gate.featureErrors(args).errors, []);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) {
      fs.writeFileSync(path.join(directory, ref.file), 'unrelated proof');
      assert.match(gate.featureErrors(args).errors.join(' '), /FEATURE_NOT_EXERCISED: claude.remote-control/);
      fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    }
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), 'b'.repeat(40));
    assert.match(gate.featureErrors(args).errors.join(' '), /exact PR head SHA/);
    for (const name of ['remote_control', 'host_claude_open', 'remoteControl', 'RemoteControl', 'remote-control']) {
      assert.ok(gate.changedOutcomes(productDiff('assets/chat_history_reader.py', name)).includes(outcome), name);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the real picker schedules AI usage even when the model omits it or fills all three slots', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-usage-pick-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-377.json'));
    const gh = path.join(directory, 'gh');
    mockPickerGithub(gh);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted AI usage.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/ai-usage.md b/${gate.REGISTRY_PATH}/ai-usage.md\n+Open AI usage and compare host numbers\n`);
    for (const diff of [path.join(root, 'tools/fixtures/feature-coverage/pr-377.diff'), recipeDiff]) {
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
            PR_NUMBER: '377',
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
        assert.ok(features.includes('ai-usage.md'), values);
        assert.ok(features.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*ai.usage/);
      }
    }
    assert.equal(gate.baseline['ai.usage'], undefined);
    assert.equal(gate.registry.outcomes['ai.usage'].journey, 'ai-usage.md');
    assert.ok(gate.debugJourneys.includes('ai-usage.md'));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('AI usage proof requires its action, displayed numbers, independent host receipt and exact head', () => {
  const outcome = 'ai.usage';
  const journey = 'ai-usage.md';
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-usage-proof-'));
  const claim = { outcome, journey, ...gate.registry.outcomes[outcome].citations };
  try {
    const priority = gate.registry.journeys[journey].evidence;
    assert.ok(priority['ai-usage-open.json']);
    assert.ok(priority['ai-usage-host.json']);
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    const args = { plan: { product: true, outcomes: [outcome] }, coverage: [claim], directories: { [journey]: directory }, conclusions: { [journey]: 'success' }, sha };
    assert.deepEqual(gate.featureErrors(args).errors, []);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) {
      fs.writeFileSync(path.join(directory, ref.file), 'unrelated proof');
      assert.match(gate.featureErrors(args).errors.join(' '), /FEATURE_NOT_EXERCISED: ai.usage/);
      fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    }
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), 'b'.repeat(40));
    assert.match(gate.featureErrors(args).errors.join(' '), /exact PR head SHA/);
    for (const name of ['AiUsage', 'PlanUsage', 'aiUsage', 'AI usage', 'tokenLadderStatus', 'readTokenLadderStatus', 'token-ladder/status.json']) {
      assert.ok(gate.changedOutcomes(productDiff('ui/AiUsageSheet.kt', name)).includes(outcome), name);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('skill-pill changes and recipe edits schedule their journey even when the picker omits it', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-pills-pick-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-378.json'));
    mockPickerGithub(path.join(directory, 'gh'));
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted skill pills.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/skill-pills.md b/${gate.REGISTRY_PATH}/skill-pills.md\n+Insert and remove a skill pill\n`);
    for (const diff of [path.join(root, 'tools/fixtures/feature-coverage/pr-378.diff'), recipeDiff]) {
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
            PR_NUMBER: '378',
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
        assert.ok(features.includes('skill-pills.md'), values);
        assert.ok(features.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*skills.pills/);
      }
    }
    assert.equal(gate.baseline['skills.pills'], undefined);
    assert.equal(gate.registry.outcomes['skills.pills'].journey, 'skill-pills.md');
    for (const [file, text] of [['ui/SkillMessageText.kt', 'paint'], ['ui/DesignKit.kt', 'KitSkillPill'], ['skills/NativeSkills.kt', 'fun insert'], ['ui/MultipromptApp.kt', 'NativeSkills.insert']]) {
      assert.ok(gate.changedOutcomes(productDiff(file, text)).includes('skills.pills'), file);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('skill-pill coverage requires exact head action, painted UI and independent send citations', () => {
  const outcome = 'skills.pills';
  const journey = 'skill-pills.md';
  const citations = gate.registry.outcomes[outcome].citations;
  assert.ok(gate.catalog().includes(JSON.stringify(citations)));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-pills-citations-'));
  try {
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
    for (const ref of Object.values(citations)) fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    const args = {
      plan: { product: true, outcomes: [outcome] },
      coverage: [{ outcome, journey, ...citations }],
      directories: { [journey]: directory },
      conclusions: { [journey]: 'success' },
      sha,
    };
    assert.equal(gate.featureErrors(args).accepted.length, 1);
    assert.match(gate.featureErrors({ ...args, coverage: [] }).errors.join(' '), /FEATURE_NOT_EXERCISED: skills.pills/);
    for (const ref of Object.values(citations)) {
      fs.writeFileSync(path.join(directory, ref.file), 'Missing skill-pill proof');
      assert.equal(gate.featureErrors(args).errors.length, 1, ref.file);
      fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    }
    assert.match(gate.featureErrors({ ...args, sha: 'b'.repeat(40) }).errors.join(' '), /exact PR head SHA/);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the real picker schedules chat table even when the model omits it or fills all three slots', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'chat-table-pick-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-379.json'));
    const gh = path.join(directory, 'gh');
    mockPickerGithub(gh);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted chat table.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/chat-table.md b/${gate.REGISTRY_PATH}/chat-table.md\n+Expand and scroll a chat table\n`);
    const rendererDiff = path.join(directory, 'renderer.diff');
    fs.writeFileSync(rendererDiff, productDiff('java/dev/multiprompt/companion/ui/MarkdownText.kt', 'val widths = listOf(120)'));
    for (const diff of [path.join(root, 'tools/fixtures/feature-coverage/pr-379.diff'), recipeDiff, rendererDiff]) {
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
            PR_NUMBER: '379',
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
        assert.ok(features.includes('chat-table.md'), values);
        assert.ok(features.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*chat-table.expand/);
      }
    }
    assert.equal(gate.baseline['chat-table.expand'], undefined);
    assert.equal(gate.registry.outcomes['chat-table.expand'].journey, 'chat-table.md');
    assert.ok(gate.debugJourneys.includes('chat-table.md'));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});


test('table proof needs its expand action, far-cell result and exact head', () => {
  const outcome = 'chat-table.expand';
  const journey = 'chat-table.md';
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'chat-table-proof-'));
  const claim = { outcome, journey, ...gate.registry.outcomes[outcome].citations };
  try {
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
    for (const ref of [claim.action, claim.result]) fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    const args = { plan: { product: true, outcomes: [outcome] }, coverage: [claim], directories: { [journey]: directory }, conclusions: { [journey]: 'success' }, sha };
    assert.deepEqual(gate.featureErrors(args).errors, []);
    for (const ref of [claim.action, claim.result]) {
      fs.writeFileSync(path.join(directory, ref.file), 'unrelated proof');
      assert.match(gate.featureErrors(args).errors.join(' '), /FEATURE_NOT_EXERCISED: chat-table.expand/);
      fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    }
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), 'b'.repeat(40));
    assert.match(gate.featureErrors(args).errors.join(' '), /exact PR head SHA/);
    assert.ok(gate.changedOutcomes(productDiff('java/dev/multiprompt/companion/ui/MarkdownText.kt', 'val widths = listOf(120)')).includes(outcome));
    for (const name of ['ExpandedChatTable', 'ChatTable', 'MarkdownTable', 'table-expand', 'table-sheet', 'table-close']) {
      assert.ok(gate.changedOutcomes(productDiff('ui/MarkdownText.kt', name)).includes(outcome), name);
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('the real picker schedules clear-ranking code and recipe edits even when the model omits it', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'clear-ranking-pick-'));
  try {
    const fixture = JSON.parse(read('tools/fixtures/feature-coverage/pr-381.json'));
    const gh = path.join(directory, 'gh');
    mockPickerGithub(gh);
    const stub = path.join(directory, 'model.cjs');
    fs.writeFileSync(stub, `global.fetch = async () => ({status:200,ok:true,json:async () => ({choices:[{finish_reason:'stop',message:{content:JSON.stringify({features:JSON.parse(process.env.MODEL_FEATURES),changed_outcomes:[],change_kind:process.env.CHANGE_KIND,reason:'Model omitted clear ranking.'})}}]})});`);
    const recipeDiff = path.join(directory, 'recipe.diff');
    fs.writeFileSync(recipeDiff, `diff --git a/${gate.REGISTRY_PATH}/clear-ranking.md b/${gate.REGISTRY_PATH}/clear-ranking.md\n+Find and send Claude clear\n`);
    const rankingDiff = path.join(directory, 'ranking.diff');
    fs.writeFileSync(rankingDiff, productDiff('skills/NativeSkills.kt', 'val rank = score(skill.name, skill.description, query)'));
    const historicalPlan = gate.planChange(read('tools/fixtures/feature-coverage/pr-381.diff'), []);
    assert.throws(() => verifier.includeRequiredJourneys({ features: [], dropped: [], smoke: true }, historicalPlan), /Required outcomes need more than three journeys/);
    for (const diff of [rankingDiff, recipeDiff]) {
      for (const features of [[], ['native-skills.md', 'session-reader.md', 'terminal.md']]) {
        const output = path.join(directory, 'outputs');
        fs.writeFileSync(output, '');
        const result = spawnSync(process.execPath, [path.join(root, '.github/scripts/pstack-verifier/verifier.cjs'), 'pick'], {
          cwd: root, encoding: 'utf8',
          env: {
            ...process.env, PATH: `${directory}:${process.env.PATH}`, NODE_OPTIONS: `--require=${stub}`,
            DEEPSEEK_API_KEY: 'test-only', REPO: 'example/repo', PR_NUMBER: '381', EVENT_HEAD_SHA: fixture.headSha,
            FIXTURE_DIFF: diff, MODEL_FEATURES: JSON.stringify(features), CHANGE_KIND: diff === recipeDiff ? 'tooling' : 'product',
            GITHUB_OUTPUT: output,
          },
        });
        assert.equal(result.status, 0, result.stderr);
        const values = fs.readFileSync(output, 'utf8');
        const selected = JSON.parse(values.match(/^features=(.+)$/m)[1]);
        assert.ok(selected.includes('clear-ranking.md'), values);
        assert.ok(selected.length <= verifier.MAX_JOURNEYS);
        assert.match(values, /^smoke=no$/m);
        if (diff !== recipeDiff) assert.match(values, /changed_outcomes=.*claude.clear-ranking/);
      }
    }
    assert.equal(gate.baseline['claude.clear-ranking'], undefined);
    assert.equal(gate.registry.outcomes['claude.clear-ranking'].journey, 'clear-ranking.md');
    assert.ok(gate.debugJourneys.includes('clear-ranking.md'));
    for (const [file, text] of [['skills/NativeSkills.kt', 'score(name, description, query)'],
      ['ui/MultipromptApp.kt', 'NativeSkills.menu(reader.nativeSkills, skillQuery)'],
      ['ui/MultipromptApp.kt', 'NativeSkillPicker']]) {
      const plan = gate.planChange(productDiff(file, text), []);
      assert.ok(plan.outcomes.includes('claude.clear-ranking'));
      assert.ok(verifier.includeRequiredJourneys({ features: [], dropped: [], smoke: true }, plan).features.includes('clear-ranking.md'));
    }
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('clear ranking citations require retained head ranking, action and exact independent receipt', () => {
  const outcome = 'claude.clear-ranking';
  const journey = 'clear-ranking.md';
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'clear-ranking-proof-'));
  const claim = { outcome, journey, ...gate.registry.outcomes[outcome].citations };
  try {
    const priority = gate.registry.journeys[journey].evidence;
    assert.ok(priority['clear-query.json']);
    assert.ok(priority['clear-receipts.txt']);
    assert.ok(gate.catalog().includes(JSON.stringify(gate.registry.outcomes[outcome].citations)));
    fs.writeFileSync(path.join(directory, 'app-source-sha.txt'), sha);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    const args = { plan: { product: true, outcomes: [outcome] }, coverage: [claim], directories: { [journey]: directory }, conclusions: { [journey]: 'success' }, sha };
    assert.deepEqual(gate.featureErrors(args).errors, []);
    for (const ref of [claim.action, claim.result, claim.sideEffect]) {
      fs.writeFileSync(path.join(directory, ref.file), 'unrelated proof');
      assert.match(gate.featureErrors(args).errors.join(' '), /FEATURE_NOT_EXERCISED: claude.clear-ranking/);
      fs.writeFileSync(path.join(directory, ref.file), ref.quote);
    }
    assert.equal(gate.featureErrors({ ...args, coverage: [] }).errors.length, 1);
    assert.equal(gate.featureErrors({ ...args, sha: 'b'.repeat(40) }).errors.length, 1);
    assert.equal(gate.featureErrors({ ...args, conclusions: { 'native-skills.md': 'success' } }).errors.length, 1);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
