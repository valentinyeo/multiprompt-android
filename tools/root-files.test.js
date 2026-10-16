const test = require('node:test');
const assert = require('node:assert/strict');
const api = import('./root-files.mjs');

test('known top-level entries pass', async () => {
  const { unexpected } = await api;
  assert.deepEqual(unexpected(['app/src/main/A.kt', 'CLAUDE.md', 'gradle/libs.versions.toml', '']), []);
});

test('scratch output at the root fails', async () => {
  const { unexpected } = await api;
  assert.deepEqual(unexpected(['out2.txt', 'out1.txt', 'app/x.kt', 'debug.png']), ['debug.png', 'out1.txt', 'out2.txt']);
});

test('scratch output inside a known folder is not this check', async () => {
  const { unexpected } = await api;
  assert.deepEqual(unexpected(['app/out1.txt']), []);
});
