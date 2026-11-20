import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const buildGradle = readFileSync(
  new URL('../../app/build.gradle.kts', import.meta.url),
  'utf8',
);
const versions = readFileSync(
  new URL('../../gradle/libs.versions.toml', import.meta.url),
  'utf8',
);

const sdkArtifacts = ['28', '34', '35'];

test('unit tests resolve Robolectric android-all jars through Gradle instead of Maven at runtime', () => {
  for (const sdk of sdkArtifacts) {
    assert.match(
      buildGradle,
      new RegExp(`create\\("robolectricAndroidAll${sdk}"\\)`),
      `missing robolectricAndroidAll${sdk} configuration`,
    );
    assert.match(
      buildGradle,
      new RegExp(
        `add\\("robolectricAndroidAll${sdk}",\\s*libs\\.robolectric\\.android\\.all\\.\\\`${sdk}\\\`\\)`,
      ),
      `sdk ${sdk} jar must be declared in its own configuration`,
    );
    assert.match(
      buildGradle,
      new RegExp(`from\\(configurations\\.named\\("robolectricAndroidAll${sdk}"\\)\\)`),
      `sync task must include robolectricAndroidAll${sdk}`,
    );
    assert.match(versions, new RegExp(`robolectric-android-all-${sdk}`));
  }
  assert.doesNotMatch(buildGradle, /create\("robolectricAndroidAll"\)/);
  assert.doesNotMatch(buildGradle, /add\("robolectricAndroidAll",/);
  assert.match(buildGradle, /syncRobolectricAndroidAll/);
  assert.match(buildGradle, /systemProperty\("robolectric\.offline",\s*"true"\)/);
  assert.match(buildGradle, /systemProperty\("robolectric\.dependency\.dir"/);
  assert.match(buildGradle, /dependsOn\(syncRobolectricAndroidAll\)/);
});