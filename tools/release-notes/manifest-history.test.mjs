import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  HISTORY_MAX_ENTRIES,
  MANIFEST_GUARD_BYTES,
  MANIFEST_MAX_BYTES,
  assertManifestSize,
  buildManifest,
  manifestBytes,
  serializeManifest,
} from './manifest-history.mjs';

const tool = fileURLToPath(new URL('./manifest-history.mjs', import.meta.url));
const release = readFileSync(new URL('../../.github/workflows/release.yml', import.meta.url), 'utf8');

const notes = index => [
  `- MUBO-${100 + index} Phone app cannot update: the update file is too large`,
  `- MUBO-${200 + index} One open chat keeps one reader and one SSH login`,
  `- IGSH-${300 + index} Android: an open chat stops reconnecting over and over`,
].join('\n');

const manifest = {
  schemaVersion: 1,
  versionCode: 100200,
  versionName: '0.1.736',
  apkUrl: 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion.apk',
  sha256: 'ab'.repeat(32),
  sizeBytes: 18432112,
  notes: notes(0),
  commit: '0123456789abcdef0123456789abcdef01234567',
};

const entry = index => ({
  versionName: `0.1.${735 - index}`,
  versionCode: 100199 - index,
  date: '2026-02-20',
  notes: notes(index),
});

const history = count => Array.from({ length: count }, (_, index) => entry(index + 1));

const heavy = index => ({
  versionName: `0.1.${900 - index}`,
  versionCode: 100000 - index,
  date: '2026-02-20',
  notes: 'x'.repeat(4000),
});

test('a long history keeps the newest entries under the 40 KB budget', () => {
  const built = buildManifest({ manifest, entry: entry(0), previous: { history: history(200) } });
  assert.ok(manifestBytes(built) <= MANIFEST_MAX_BYTES, `${manifestBytes(built)} bytes`);
  assert.equal(built.history.length, HISTORY_MAX_ENTRIES);
  assert.equal(built.history[0].versionCode, 100199);
  assert.equal(built.history[0].versionName, '0.1.735');
  assert.equal(built.history[0].notes, notes(0));
  for (let index = 1; index < built.history.length; index += 1) {
    assert.ok(built.history[index].versionCode < built.history[index - 1].versionCode);
  }
});

test('the byte budget drops the oldest entries before the entry cap binds', () => {
  const built = buildManifest({ manifest, entry: heavy(0), previous: { history: Array.from({ length: 30 }, (_, index) => heavy(index + 1)) } });
  assert.ok(manifestBytes(built) <= MANIFEST_MAX_BYTES, `${manifestBytes(built)} bytes`);
  assert.ok(built.history.length > 0);
  assert.ok(built.history.length < HISTORY_MAX_ENTRIES);
  assert.equal(built.history[0].versionCode, 100000);
  assert.equal(built.history[built.history.length - 1].versionCode, 100000 - built.history.length + 1);
});

test('a version code seen twice keeps only its newest entry', () => {
  const built = buildManifest({ manifest, entry: entry(0), previous: { history: [entry(0), entry(1)] } });
  assert.deepEqual(built.history.map(item => item.versionCode), [100199, 100198]);
});

test('the release guard refuses a manifest over 48 KB', () => {
  assert.throws(() => assertManifestSize('x'.repeat(60 * 1024)), /MANIFEST_TOO_LARGE: 61440 bytes over the 49152 byte release guard/);
  assert.equal(MANIFEST_GUARD_BYTES, 48 * 1024);
  const built = serializeManifest(buildManifest({ manifest, entry: entry(0), previous: { history: history(200) } }));
  assert.equal(assertManifestSize(built), Buffer.byteLength(built));
  assert.throws(
    () => assertManifestSize(serializeManifest({ ...manifest, history: [{ notes: 'y'.repeat(60 * 1024) }] })),
    /MANIFEST_TOO_LARGE/,
  );
});

test('a manifest that cannot fit even without history is refused', () => {
  assert.throws(
    () => buildManifest({ manifest: { ...manifest, notes: 'n'.repeat(41 * 1024) }, entry: entry(0), previous: {} }),
    /MANIFEST_TOO_LARGE/,
  );
});

test('the command line builds the manifest and checks the guard', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'manifest-'));
  const file = name => path.join(directory, name);
  writeFileSync(file('manifest.json'), JSON.stringify(manifest));
  writeFileSync(file('entry.json'), JSON.stringify(entry(0)));
  writeFileSync(file('previous.json'), JSON.stringify({ history: history(200) }));
  const build = spawnSync('node', [
    tool,
    '--manifest', file('manifest.json'),
    '--entry', file('entry.json'),
    '--previous', file('previous.json'),
    '--out', file('update.json'),
  ], { encoding: 'utf8' });
  assert.equal(build.status, 0, build.stderr);
  assert.match(build.stdout, /MANIFEST_OK: \d+ bytes with 30 history entries/);
  const raw = readFileSync(file('update.json'), 'utf8');
  assert.ok(Buffer.byteLength(raw) <= MANIFEST_MAX_BYTES);
  const written = JSON.parse(raw);
  assert.equal(written.versionName, '0.1.736');
  assert.equal(written.history[0].versionCode, 100199);
  const ok = spawnSync('node', [tool, '--check', file('update.json')], { encoding: 'utf8' });
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /MANIFEST_SIZE_OK: \d+ bytes/);
  writeFileSync(file('big.json'), JSON.stringify({ ...manifest, history: [{ notes: 'z'.repeat(60 * 1024) }] }));
  const big = spawnSync('node', [tool, '--check', file('big.json')], { encoding: 'utf8' });
  assert.equal(big.status, 1);
  assert.match(big.stderr, /MANIFEST_TOO_LARGE/);
  const missing = spawnSync('node', [tool, '--out', file('other.json')], { encoding: 'utf8' });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /Missing --manifest/);
});

test('both release channels build history with the shared script and guard the size before publishing', () => {
  const steps = release.split(/(?=^      - )/m);
  const step = name => {
    const found = steps.find(value => value.startsWith(`      - name: ${name}\n`));
    assert.ok(found, `Missing release step: ${name}`);
    return found;
  };
  for (const [build, guard, publish] of [
    ['Build verified update manifest and carry forward its changelog history', 'Reject an update manifest over 48 KB', 'Publish fixed android-latest channel'],
    ['Build M3 update manifest and carry forward its changelog history', 'Reject an M3 update manifest over 48 KB', 'Publish fixed android-m3-latest channel'],
  ]) {
    assert.match(step(build), /node tools\/release-notes\/manifest-history\.mjs \\\n\s+--manifest /);
    assert.match(step(guard), /manifest-history\.mjs --check build\/android-/);
    assert.ok(release.indexOf(step(build)) < release.indexOf(step(guard)));
    assert.ok(release.indexOf(step(guard)) < release.indexOf(step(publish)));
  }
  assert.doesNotMatch(release, /\.\[0:1000\]/);
  assert.doesNotMatch(release, /365 days ago/);
});
