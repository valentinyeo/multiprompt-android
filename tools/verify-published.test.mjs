import assert from 'node:assert/strict';
import test from 'node:test';
import { checkManifest, channelFile, manifestUrl } from './verify-published.mjs';

const good = {
  versionName: '0.1.351-m3',
  versionCode: 100351,
  commit: '3a61032dc670920e6bfa6bad89bb6bdd8cf0fa30',
  sizeBytes: 19231472,
  sha256: '01904cc8e633e43f1e3366a4edaae7ba4f0b8543f3d04ccd7798a726bd235535',
  apkUrl: 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3.apk',
};

test('a complete manifest has no problems', () => {
  assert.deepEqual(checkManifest(good), []);
});

test('each missing field is named', () => {
  for (const [field, value] of Object.entries({ versionName: '', versionCode: 0, commit: 'abc', sizeBytes: -1, sha256: 'x', apkUrl: 'https://example.com/other.apk' })) {
    assert.ok(checkManifest({ ...good, [field]: value }).length > 0, `${field} should be rejected`);
  }
});

test('each channel points at its own files', () => {
  assert.equal(channelFile('android-m3-latest'), 'multiprompt-m3.apk');
  assert.equal(channelFile('android-latest'), 'multiprompt-companion.apk');
  assert.ok(manifestUrl('android-m3-latest').includes('multiprompt-m3-update.json'));
  assert.ok(manifestUrl('android-latest').includes('multiprompt-companion-update.json'));
});
