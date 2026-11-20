import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { allowlistFile, decide, loadAllowlist } from './check.mjs';

const tool = fileURLToPath(new URL('./check.mjs', import.meta.url));
const frozen = { frozen: true, allowed: ['IGSH-2570', 'IGSH-2681'] };
const open = { frozen: false, allowed: ['IGSH-2570'] };

test('an allowed ticket passes while the freeze is on', () => {
  assert.equal(decide('IGSH-2570: Android verification meets the checklist', frozen).pass, true);
  assert.match(decide('IGSH-2681: something shipped', frozen).message, /FEATURE_FREEZE_ALLOWED: IGSH-2681/);
});

test('any other ticket fails while the freeze is on', () => {
  const result = decide('IGSH-2740: A new folder bar', frozen);
  assert.equal(result.pass, false);
  assert.match(result.message, /FEATURE_FREEZE_BLOCKED: IGSH-2740/);
  assert.match(result.message, /IGSH-2570/);
  for (const title of ['IGSH-25700: longer number', 'HYCI-12: another board', 'IGSH-999999: far away']) {
    assert.equal(decide(title, frozen).pass, false, title);
  }
});

test('the check passes every ticket once the freeze is off', () => {
  for (const title of ['IGSH-2740: A new folder bar', 'IGSH-2570: Android verification meets the checklist', 'HYCI-12: another board']) {
    const result = decide(title, open);
    assert.equal(result.pass, true, title);
    assert.match(result.message, /FEATURE_FREEZE_OFF/);
  }
});

test('a title without a ticket id is left to the PR title check', () => {
  for (const title of ['Tidy the tooling', '', null, undefined, 'fix IGSH-2740 later']) {
    const result = decide(title, frozen);
    assert.equal(result.pass, true, String(title));
    assert.doesNotMatch(result.message, /FEATURE_FREEZE_BLOCKED/);
  }
});

test('the shipped allowlist is off, names whole ticket ids and reads back', () => {
  const shipped = loadAllowlist(allowlistFile);
  assert.equal(shipped.frozen, false);
  for (const id of ['IGSH-2681', 'IGSH-2682', 'IGSH-2547', 'IGSH-2532', 'IGSH-2474', 'IGSH-2709', 'IGSH-2714', 'IGSH-2751', 'IGSH-2734', 'IGSH-2748', 'IGSH-2533', 'IGSH-2752', 'IGSH-2570', 'IGSH-2708', 'IGSH-2715', 'IGSH-2646', 'IGSH-2531', 'IGSH-2535', 'IGSH-2673']) {
    assert.ok(shipped.allowed.includes(id), id);
  }
  assert.equal(new Set(shipped.allowed).size, shipped.allowed.length);
  assert.equal(decide('IGSH-2752: A required check blocks feature PRs until phone testing is right', shipped).pass, true);
  assert.equal(decide('IGSH-2706: Fix the inbox folder bar', shipped).pass, true);
});

test('a broken allowlist fails loudly instead of allowing everything', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'freeze-'));
  const write = content => {
    const file = path.join(directory, 'allowed-tickets.json');
    writeFileSync(file, content);
    return file;
  };
  assert.equal(loadAllowlist(write('{"frozen": true, "allowed": []}')).frozen, true);
  assert.throws(() => loadAllowlist(write('{"allowed": []}')), /needs "frozen"/);
  assert.throws(() => loadAllowlist(write('{"frozen": true}')), /needs an "allowed" list/);
  assert.throws(() => loadAllowlist(write('{"frozen": true, "allowed": ["IGSH-2752 ", "nope"]}')), /not a whole id/);
});

test('the command line lets every real title through while the freeze is off', () => {
  const pass = spawnSync(process.execPath, [tool, '--title', 'IGSH-2570: Android verification meets the checklist'], { encoding: 'utf8' });
  assert.equal(pass.status, 0, pass.stderr);
  assert.match(pass.stdout, /FEATURE_FREEZE_OFF: IGSH-2570/);
  const feature = spawnSync(process.execPath, [tool], { encoding: 'utf8', env: { ...process.env, PR_TITLE: 'IGSH-2740: A new folder bar' } });
  assert.equal(feature.status, 0, feature.stderr);
  assert.match(feature.stdout, /FEATURE_FREEZE_OFF: IGSH-2740/);
  const missing = spawnSync(process.execPath, [tool], { encoding: 'utf8', env: { ...process.env, PR_TITLE: '' } });
  assert.equal(missing.status, 0);
  assert.match(missing.stdout, /has nothing to look up/);
});
