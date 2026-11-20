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

test('unit tests resolve Robolectric android-all jars through Gradle instead of Maven at runtime', () => {
  assert.match(buildGradle, /configuration[s]?\s*\{[\s\S]*create\("robolectricAndroidAll"\)/);
  assert.match(buildGradle, /syncRobolectricAndroidAll/);
  assert.match(buildGradle, /systemProperty\("robolectric\.offline",\s*"true"\)/);
  assert.match(buildGradle, /systemProperty\("robolectric\.dependency\.dir"/);
  assert.match(buildGradle, /dependsOn\(syncRobolectricAndroidAll\)/);
  assert.match(versions, /robolectric-android-all-28/);
  assert.match(versions, /robolectric-android-all-34/);
  assert.match(versions, /robolectric-android-all-35/);
});