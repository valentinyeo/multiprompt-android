import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

const actionPath = new URL('../../.github/actions/gradle-ci/action.yml', import.meta.url);
const workflowDirectory = new URL('../../.github/workflows/', import.meta.url);
const settingsPath = new URL('../../settings.gradle.kts', import.meta.url);

const action = readFileSync(actionPath, 'utf8');
const settings = readFileSync(settingsPath, 'utf8');
const read = file => readFileSync(new URL(file, workflowDirectory), 'utf8');

const ciBlocks = source =>
  [...source.matchAll(/^(\s*)- uses: \.\/\.github\/actions\/gradle-ci\s*$\n([\s\S]*?)(?=^\1- |$(?![\s\S]))/gm)].map(
    match => match[2]
  );

const jobBody = (source, job) => {
  const chunk = source.split(new RegExp(`^  ${job}:\\n`, 'm'))[1];
  assert.ok(chunk, `missing job ${job}`);
  return chunk.split(/^  [a-z][\w-]*:\n/m)[0];
};

test('a read only phone test job restores the newest cache so the Compose plugin is already on disk', () => {
  const strictMatch = action.match(/^\s+gradle-home-cache-strict-match:\s*(.+)$/m);
  assert.ok(strictMatch, 'gradle-ci must set gradle-home-cache-strict-match');
  assert.equal(
    strictMatch[1].trim(),
    "${{ inputs.read-only != 'true' }}",
    'read only jobs must allow the cache prefix fallback that the strict match blocks'
  );
  assert.match(action, /^\s+cache-read-only: \$\{\{ inputs\.read-only == 'true' \}\}$/m);
});

test('every read only Gradle job names the job whose cache it restores', () => {
  const files = readdirSync(workflowDirectory).filter(file => file.endsWith('.yml'));
  const readers = files.filter(file => ciBlocks(read(file)).some(block => /^\s+read-only: true\s*$/m.test(block)));
  assert.ok(readers.length > 0, 'expected at least one read only Gradle job');
  for (const file of readers) {
    assert.match(
      read(file),
      /^\s+GRADLE_BUILD_ACTION_CACHE_KEY_JOB:\s*\S+$/m,
      `${file} restores a read only cache without naming the job that saves it`
    );
  }
});

test('the job that writes the cache warms the Robolectric jars the read only jobs restore', () => {
  const build = jobBody(read('test.yml'), 'build');
  assert.match(
    build,
    /tools\/ci\/gradle-retry\.sh --no-daemon [^\n]*:app:syncRobolectricAndroidAll/,
    'build must download the Robolectric android-all jars so the read only jobs find them in the cache'
  );
  assert.match(
    build,
    /tools\/ci\/gradle-retry\.sh --no-daemon [^\n]*:app:generateDebugUnitTestLintModel/,
    'build must download the lint model dependencies so lint finds them in the cache'
  );
  assert.match(action, /android-all-instrumented-\*\.jar/);
  assert.match(action, /nativeruntime-dist-compat-\*\.jar/);
});

test('a cold build can still fetch the Compose plugin from the plugin repositories', () => {
  const pluginRepositories = settings.match(/pluginManagement\s*\{[\s\S]*?repositories\s*\{([\s\S]*?)\n    \}/);
  assert.ok(pluginRepositories, 'missing pluginManagement.repositories in settings.gradle.kts');
  const lines = pluginRepositories[1]
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0 && !line.startsWith('//'));
  for (const repository of [
    'mavenCentral()',
    'maven("https://maven-central.storage-download.googleapis.com/maven2/")',
    'gradlePluginPortal()',
  ]) {
    assert.ok(lines.includes(repository), `pluginManagement must list ${repository}`);
  }
  const google = pluginRepositories[1].match(/google\s*\{\s*content\s*\{([\s\S]*?)\}\s*\}/);
  assert.ok(google, 'pluginManagement must scope google() with content { }');
  assert.doesNotMatch(
    google[1],
    /org\\?\.jetbrains\\?\.kotlin/,
    'pluginManagement must keep the Kotlin and Compose plugins off Google Maven'
  );
});
