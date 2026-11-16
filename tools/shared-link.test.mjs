import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const root = new URL('../', import.meta.url);
const read = file => readFileSync(new URL(file, root), 'utf8');

test('shared chat recovery proof pins the reader and connectors it actually installs', () => {
  const scripts = '.claude/skills/verify-multiprompt-android/scripts';
  const source = read('app/src/main/assets/chat_history_reader.py');
  const generation = Number(source.match(/^RUNTIME_GENERATION = (\d+)$/m)[1]);
  const modules = ['claude', 'codex'].map(name => Buffer.from(read(`app/src/main/assets/chat_connectors/${name}.py`)).toString('base64'));
  const bundle = Buffer.from(`CONNECTOR_SOURCES = {'claude':'${modules[0]}','codex':'${modules[1]}'}\n${source}`);
  const hash = createHash('sha256').update(bundle).digest('hex');
  for (const script of ['fixture.sh', 'drive.sh']) {
    const code = read(`${scripts}/${script}`);
    assert.equal(Number(code.match(/\.bundle_bytes == (\d+)/)[1]), bundle.length, `${script} has stale fixture bytes`);
    assert.equal(code.match(/\.bundle_sha256 == "([a-f0-9]{64})"/)[1], hash, `${script} has a stale fixture hash`);
  }
  assert.equal(Number(read(`${scripts}/shared-evidence.py`).match(/assert info\['generation'\] == (\d+)/)[1]), generation);
  assert.equal(Number(read(`${scripts}/fixture.sh`).match(/\.engine_info.generation == (\d+)/)[1]), generation);
});
