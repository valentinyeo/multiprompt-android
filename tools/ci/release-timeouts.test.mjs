import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../../.github/workflows/release.yml', import.meta.url), 'utf8');

const observed = {
  'Build signed APKs': [5.9, 6.0, 6.0, 6.0, 6.0, 6.2, 6.2, 8.0, 8.2, 8.5],
  'Launch signed M3 APK': [0.9, 0.9, 0.9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
  'Prepare signed release journey': [0.9, 1.2, 1.4, 1.4, 1.4, 13.3, 20.1, 20.1, 21.7, 29.1, 30.3],
  'Record signed release journey': [3.5, 3.5, 3.7, 3.8, 3.8, 5.8, 6.8, 9.2, 10.8, 11.6],
};

const jobMinutes = Number(workflow.match(/^  build-and-publish:\n(?:.*\n)*?    timeout-minutes: (\d+)$/m)?.[1]);
const blocks = workflow.split(/(?=^      - )/m).slice(1);
const label = block => block.match(/^      - name: (.+)$/m)?.[1] ?? block.match(/^      - uses: (\S+)$/m)?.[1];
const limit = block => Number(block.match(/^        timeout-minutes: (\d+)$/m)?.[1]);
const slowestJob = 49.1;

test('every release step carries a hard time limit', () => {
  assert.equal(blocks.length, 24, 'release job step count changed, recheck the limits');
  for (const block of blocks) {
    const name = label(block);
    assert.ok(name, `Unnamed release step:\n${block.slice(0, 120)}`);
    const minutes = limit(block);
    assert.ok(Number.isInteger(minutes) && minutes > 0, `${name} has no timeout-minutes`);
  }
  assert.ok(Number.isInteger(jobMinutes), 'the release job has no timeout-minutes');
});

test('each limit is sized from the last ten successful release runs', () => {
  for (const block of blocks) {
    const name = label(block);
    const minutes = limit(block);
    if (observed[name]) {
      const max = Math.max(...observed[name]);
      assert.ok(minutes >= Math.ceil(max), `${name} limit ${minutes} is tighter than the slowest healthy run ${max}`);
      assert.ok(minutes <= max + 30, `${name} limit ${minutes} leaves more than 30 minutes for a hang`);
    }
    if (name === 'Record signed release journey') {
      const max = Math.max(...observed[name]);
      assert.ok(minutes <= max * 2, `recording may burn ${minutes} minutes against a ${max} minute healthy run`);
    }
  }
  assert.ok(jobMinutes >= slowestJob, `job limit ${jobMinutes} is tighter than the slowest healthy job ${slowestJob}`);
  assert.ok(jobMinutes <= slowestJob + 40, `job limit ${jobMinutes} lets one release hold the channel for too long`);
});

test('the filming step stays bounded end to end', () => {
  const record = blocks.find(block => label(block) === 'Record signed release journey');
  const prepare = blocks.find(block => label(block) === 'Prepare signed release journey');
  assert.match(record, /timeout-minutes: 15\n/);
  assert.match(prepare, /timeout-minutes: 40\n/);
});
