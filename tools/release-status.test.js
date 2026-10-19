const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');
const api = import('./release-status.mjs');
const commit = 'ea33a2a0d5fa97743ea5a9def4e35ee6a81834a9';
const manifest = { versionName: '0.1.198', versionCode: 100198, commit };
const script = path.join(__dirname, 'release-status.mjs');

function cli(args, fetchBody) {
  const preload = `globalThis.fetch = async (url, options) => {
    if (url !== 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json' || options.redirect !== 'follow') throw new Error('Unexpected fetch request');
    ${fetchBody}
  };`;
  return spawnSync(process.execPath, ['--import', `data:text/javascript,${encodeURIComponent(preload)}`, script, ...args], { encoding: 'utf8' });
}

const success = `return { ok: true, json: async () => (${JSON.stringify(manifest)}) };`;

test('matching release passes and formats the version, code and commit', async () => {
  const { releaseStatus } = await api;
  assert.deepEqual(releaseStatus(manifest, commit), {
    matches: true,
    message: `android-latest 0.1.198 (100198) serves ${commit}`,
  });
});

test('mismatching release fails and describes served and wanted commits', async () => {
  const { releaseStatus } = await api;
  assert.deepEqual(releaseStatus(manifest, '0000000'), {
    matches: false,
    message: `android-latest serves ${commit}, wanted 0000000`,
  });
});

test('short SHA prefixes of at least seven characters pass', async () => {
  const { releaseStatus } = await api;
  for (const length of [7, 8, 39, 40]) assert.equal(releaseStatus(manifest, commit.slice(0, length)).matches, true);
});

test('too-short, empty, non-SHA and longer-than-served wanted commits fail', async () => {
  const { releaseStatus } = await api;
  for (const wanted of [commit.slice(0, 6), '', 'not-a-sha', commit + '0', undefined]) {
    assert.equal(releaseStatus(manifest, wanted).matches, false);
  }
  assert.equal(releaseStatus({ ...manifest, commit: 'ea33a2' }, 'ea33a2').matches, false);
});

test('missing or invalid manifest commit fails', async () => {
  const { releaseStatus } = await api;
  for (const value of [{}, null, undefined, { commit: null }, { commit: '' }, { commit: 1234567 }]) {
    assert.equal(releaseStatus(value, commit).matches, false);
  }
  assert.equal(releaseStatus({}, commit).message, `android-latest serves missing, wanted ${commit}`);
});

test('CLI succeeds only on a match and writes mismatches to stderr', () => {
  const passed = cli(['--commit', commit.slice(0, 7)], success);
  assert.equal(passed.status, 0, passed.stderr);
  assert.equal(passed.stdout, `android-latest 0.1.198 (100198) serves ${commit}\n`);
  assert.equal(passed.stderr, '');
  for (const wanted of ['0000000', commit.slice(0, 6)]) {
    const failed = cli(['--commit', wanted], success);
    assert.equal(failed.status, 1);
    assert.equal(failed.stdout, '');
    assert.equal(failed.stderr, `android-latest serves ${commit}, wanted ${wanted}\n`);
  }
  const missing = cli(['--commit', commit], 'return { ok: true, json: async () => ({}) };');
  assert.equal(missing.status, 1);
  assert.equal(missing.stderr, `android-latest serves missing, wanted ${commit}\n`);
});

test('CLI reports network, HTTP and JSON parse failures with exit 1', () => {
  for (const [body, message] of [
    ["throw new Error('Network unavailable');", /Network unavailable/],
    ['return { ok: false, status: 503 };', /HTTP 503/],
    ["return { ok: true, json: async () => JSON.parse('not JSON') };", /JSON/],
  ]) {
    const result = cli(['--commit', commit], body);
    assert.equal(result.status, 1);
    assert.equal(result.stdout, '');
    assert.match(result.stderr, /Release status failed:/);
    assert.match(result.stderr, message);
  }
});

test('CLI rejects invalid usage before fetching', () => {
  for (const args of [['--commit'], ['--unknown', commit], ['--commit', commit, 'extra']]) {
    const result = cli(args, "throw new Error('Must not fetch');");
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Usage: node tools\/release-status\.mjs/);
  }
});

test('importing the module does not run the CLI or access the network', () => {
  const source = `globalThis.fetch = () => { throw new Error('Must not fetch'); }; await import(${JSON.stringify(pathToFileURL(script).href)});`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', source], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '');
});
