import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { apkProblems, profileFile, profileProblems, sourceRoot } from './baseline-profile.mjs';

const shipped = readFileSync(profileFile, 'utf8');

test('the shipped profile follows the rule grammar and names real code', () => {
  assert.deepEqual(profileProblems(shipped), []);
});

test('the shipped profile covers the startup classes and the ui, reader and ssh packages', () => {
  const lines = shipped.split('\n');
  for (const name of ['MultipromptApplication', 'MainActivity', 'MainViewModel']) {
    assert.ok(lines.includes(`HSPLdev/multiprompt/companion/${name};->**(**)**`), `${name} method rule`);
    assert.ok(lines.includes(`Ldev/multiprompt/companion/${name};`), `${name} class rule`);
  }
  for (const pkg of ['ui', 'reader', 'ssh']) {
    assert.ok(lines.includes(`HSPLdev/multiprompt/companion/${pkg}/**->**(**)**`), `${pkg} package rule`);
  }
});

test('rules that cannot work are rejected', () => {
  const bad = {
    'HSPLdev/multiprompt/companion/nowhere/**->**(**)**': 'no source directory',
    'Ldev/multiprompt/companion/MainActivityGone;': 'not declared',
    'HSPLdev/multiprompt/companion/ui/Bar*->**(**)**': 'trailing /**',
    'HSPLdev/multiprompt/companion/ui/**->**': 'not a baseline profile rule',
    'dev/multiprompt/companion/ui/**': 'not a baseline profile rule',
    'HSPLandroidx/compose/ui/**->**(**)**': 'outside dev/multiprompt/companion',
    'XLdev/multiprompt/companion/MainActivity;': 'not a baseline profile rule',
  };
  for (const [line, reason] of Object.entries(bad)) {
    const problems = profileProblems(`${line}\n`, sourceRoot);
    assert.ok(problems.some(problem => problem.includes(reason)), `${line} should fail with ${reason}, got ${problems}`);
  }
});

test('an empty or repeated profile is rejected', () => {
  assert.ok(profileProblems('').length > 0);
  const rule = 'Ldev/multiprompt/companion/MainActivity;';
  assert.ok(profileProblems(`${rule}\n${rule}\n`).some(problem => problem.includes('duplicate')));
});

function apkWith(files) {
  const folder = mkdtempSync(path.join(tmpdir(), 'baseline-profile-'));
  const content = path.join(folder, 'content');
  for (const [name, bytes] of Object.entries(files)) {
    mkdirSync(path.dirname(path.join(content, name)), { recursive: true });
    writeFileSync(path.join(content, name), bytes);
  }
  const apk = path.join(folder, 'app.apk');
  execFileSync('zip', ['-qr', apk, '.'], { cwd: content });
  return { apk, folder };
}

test('an apk with a compiled profile passes', () => {
  const { apk, folder } = apkWith({
    'classes.dex': 'dex',
    'assets/dexopt/baseline.prof': Buffer.concat([Buffer.from([0x70, 0x72, 0x6f, 0x00]), Buffer.from('015\0rest')]),
    'assets/dexopt/baseline.profm': Buffer.concat([Buffer.from([0x70, 0x72, 0x6d, 0x00]), Buffer.from('002\0rest')]),
  });
  try {
    assert.deepEqual(apkProblems(apk), []);
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});

test('an apk without, with an empty, or with a wrong profile fails', () => {
  const cases = {
    'no profile': { files: { 'classes.dex': 'dex' }, reason: 'has no assets/dexopt/baseline.prof' },
    'empty profile': {
      files: { 'assets/dexopt/baseline.prof': '', 'assets/dexopt/baseline.profm': 'prm\0' },
      reason: 'empty assets/dexopt/baseline.prof',
    },
    'wrong profile': {
      files: { 'assets/dexopt/baseline.prof': 'HSPLnot a binary profile', 'assets/dexopt/baseline.profm': 'prm\0' },
      reason: 'not a compiled ART profile',
    },
    'no metadata': {
      files: { 'assets/dexopt/baseline.prof': Buffer.concat([Buffer.from([0x70, 0x72, 0x6f, 0x00]), Buffer.from('015\0')]) },
      reason: 'has no assets/dexopt/baseline.profm',
    },
  };
  for (const [name, { files, reason }] of Object.entries(cases)) {
    const { apk, folder } = apkWith(files);
    try {
      assert.ok(apkProblems(apk).some(problem => problem.includes(reason)), `${name} should fail with ${reason}`);
    } finally {
      rmSync(folder, { recursive: true, force: true });
    }
  }
});
