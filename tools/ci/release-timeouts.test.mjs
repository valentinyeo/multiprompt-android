import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../../.github/workflows/release.yml', import.meta.url), 'utf8');

const observed = {
  'Build signed APKs': [5.9, 6.0, 6.0, 6.0, 6.0, 6.2, 6.2, 8.0, 8.2, 8.5],
  'Launch signed M3 APK': [0.9, 0.9, 0.9, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
};

const jobMinutes = Number(workflow.match(/^  build-and-publish:\n(?:.*\n)*?    timeout-minutes: (\d+)$/m)?.[1]);
const blocks = workflow.split(/(?=^      - )/m).slice(1);
const label = block => block.match(/^      - name: (.+)$/m)?.[1] ?? block.match(/^      - uses: (\S+)$/m)?.[1];
const limit = block => Number(block.match(/^        timeout-minutes: (\d+)$/m)?.[1]);
const slowestJob = 17.5;

test('every release step carries a hard time limit', () => {
  assert.equal(blocks.length, 22, 'release job step count changed, recheck the limits');
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
  }
  assert.ok(jobMinutes >= slowestJob, `job limit ${jobMinutes} is tighter than the slowest healthy job ${slowestJob}`);
  assert.ok(jobMinutes <= slowestJob + 40, `job limit ${jobMinutes} lets one release hold the channel for too long`);
});

test('the signed launch gate stays bounded', () => {
  const launch = blocks.find(block => label(block) === 'Launch signed M3 APK');
  assert.match(launch, /timeout-minutes: 5\n/);
  assert.match(launch, /emulator-boot-timeout: 180/);
});