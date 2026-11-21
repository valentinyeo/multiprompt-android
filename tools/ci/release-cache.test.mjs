import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

const directory = new URL('../../.github/workflows/', import.meta.url);
const read = file => readFileSync(new URL(file, directory), 'utf8');
const blocks = source => [...source.matchAll(/^(\s*)- uses: gradle\/actions\/setup-gradle@v4\s*$\n([\s\S]*?)(?=^\1- |$(?![\s\S]))/gm)].map(match => match[2]);
const ciBlocks = source => [...source.matchAll(/^(\s*)- uses: \.\/\.github\/actions\/gradle-ci\s*$\n([\s\S]*?)(?=^\1- |$(?![\s\S]))/gm)].map(match => match[2]);
const jobBody = (source, job) => {
  const chunk = source.split(new RegExp(`^  ${job}:\\n`, 'm'))[1];
  assert.ok(chunk, `missing job ${job}`);
  return chunk.split(/^  [a-z][\w-]*:\n/m)[0];
};
const jobs = source => [...source.matchAll(/^  ([\w-]+):$/gm)].map(match => match[1]);
const cacheJob = source => source.match(/^\s+GRADLE_BUILD_ACTION_CACHE_KEY_JOB:\s*(\S+)\s*$/m)?.[1];
const gradleCi = readFileSync(new URL('../../.github/actions/gradle-ci/action.yml', import.meta.url), 'utf8');

const gradleWorkflows = () =>
  readdirSync(directory)
    .filter(file => file.endsWith('.yml'))
    .filter(file => {
      const source = read(file);
      return source.includes('gradle/actions/setup-gradle@v4') || source.includes('./.github/actions/gradle-ci');
    });

test('shared Gradle setup pins 9.5.1, uses strict home cache, and gates read-only restores', () => {
  assert.match(gradleCi, /gradle-version:\s*"9\.5\.1"/);
  assert.match(gradleCi, /gradle-home-cache-strict-match:\s*true/);
  assert.match(gradleCi, /cache-read-only: \$\{\{ inputs\.read-only == 'true' \}\}/);
});

test('a release never saves a Gradle cache that later releases restore', () => {
  const release = jobBody(read('release.yml'), 'build-and-publish');
  assert.equal(ciBlocks(release).length, 1, 'release.yml gradle-ci step count changed, recheck the cache rule');
  assert.match(ciBlocks(release)[0], /^\s+read-only: true\s*$/m);
});

test('a merge to main starts the release at once instead of waiting for Android tests', () => {
  const release = read('release.yml');
  assert.match(release, /^  push:\n    branches: \[main\]$/m);
  assert.doesNotMatch(release, /workflow_run/);
  assert.match(jobBody(release, 'build-and-publish'), /if: github\.ref == 'refs\/heads\/main'/);
  assert.doesNotMatch(release, /ref: \$\{\{/);
});

test('a release restores the cache entry that the Android tests build job writes', () => {
  const workflow = read('test.yml');
  const target = cacheJob(read('release.yml'));
  assert.ok(target, 'release.yml must name the job whose Gradle cache it restores: its own job has no cache entry because a release never writes');
  assert.ok(jobs(workflow).includes(target), `${target} is not an Android tests job`);
  const step = jobBody(workflow, target);
  assert.match(step, /uses: \.\/\.github\/actions\/gradle-ci/);
  assert.doesNotMatch(step, /read-only: true/, `${target} must write the cache a release restores`);
});

test('Android tests keeps its five jobs in parallel', () => {
  const workflow = read('test.yml');
  assert.deepEqual(jobs(workflow.split('\njobs:\n')[1]), ['build', 'test', 'unit-tests', 'm3-unit-tests', 'lint']);
  assert.doesNotMatch(workflow, /^\s+needs:/m, 'no Android tests job may wait for another: serialized checks make every pull request slower');
});

test('test workflows keep saving the Gradle cache', () => {
  const build = jobBody(read('test.yml'), 'build');
  assert.doesNotMatch(build, /read-only: true/, 'build must keep writing the Gradle cache');
  for (const file of ['android-full.yml']) {
    const steps = blocks(read(file));
    assert.ok(steps.length > 0, `${file} has no setup-gradle step`);
    for (const step of steps) {
      assert.doesNotMatch(step, /cache-read-only:\s*true/, `${file} must keep writing the Gradle cache`);
    }
  }
});

test('Gradle steps retry a busy repository through one wrapper', () => {
  assert.match(jobBody(read('release.yml'), 'build-and-publish'), /tools\/ci\/gradle-retry\.sh --no-daemon assembleRelease assembleM3/);
  const workflow = read('test.yml');
  assert.match(jobBody(workflow, 'build'), /run: tools\/ci\/gradle-retry\.sh --no-daemon .* assembleDebug/);
  for (const job of ['test', 'unit-tests', 'm3-unit-tests', 'lint']) {
    assert.match(jobBody(workflow, job), /GRADLE: tools\/ci\/gradle-retry\.sh/, `${job} must retry its Gradle step`);
  }
  assert.match(readFileSync(new URL('../../tools/ci/gradle-retry.sh', import.meta.url), 'utf8'), /attempt.*-ge 3/);
  assert.match(readFileSync(new URL('../../tools/check.sh', import.meta.url), 'utf8'), /tools\/ci\/gradle-retry\.test\.mjs/);
});

test('every workflow that runs Gradle uses setup-gradle without a second home cache', () => {
  for (const file of gradleWorkflows()) {
    const source = read(file);
    const setupCount = (source.match(/gradle\/actions\/setup-gradle@v4/g) ?? []).length;
    const ciCount = (source.match(/\.\/\.github\/actions\/gradle-ci/g) ?? []).length;
    assert.ok(setupCount + ciCount >= 1, `${file} must call setup-gradle or gradle-ci`);
    const homeCache = (source.match(/actions\/cache@v4[\s\S]*?\.gradle\/caches/g) ?? []).length;
    assert.equal(homeCache, 0, `${file} must not stack actions/cache on ~/.gradle beside setup-gradle`);
  }
});

test('verify journeys avoid stale dependency metadata from older Gradle home caches', () => {
  const steps = ciBlocks(read('verify.yml'));
  assert.equal(steps.length, 1, 'verify.yml gradle-ci step count changed, recheck the cache rule');
  assert.match(steps[0], /^\s+read-only: true\s*$/m);
  assert.doesNotMatch(read('verify.yml'), /gradle-home-cache-excludes:\s*\n\s*caches\/modules-2/);
});

test('verify journey jobs restore the Android tests build cache and retry Gradle downloads', () => {
  const verify = read('verify.yml');
  const buildJob = jobBody(read('test.yml'), 'build');
  assert.doesNotMatch(buildJob, /read-only: true/, 'build must keep writing the cache phone journeys restore');
  assert.match(verify, /^\s+GRADLE_BUILD_ACTION_CACHE_KEY_JOB:\s*build\s*$/m);
  assert.match(verify, /tools\/ci\/gradle-retry\.sh -p "\$app_source"[^\n]*assembleDebug/);
  assert.match(verify, /tools\/ci\/gradle-retry\.sh[^\n]*assembleDebugAndroidTest/);
});

test('checker jobs restore the Android tests build cache instead of cold-downloading dependencies', () => {
  const workflow = read('test.yml');
  for (const job of ['test', 'unit-tests', 'm3-unit-tests', 'lint']) {
    const body = jobBody(workflow, job);
    assert.match(body, /^\s+GRADLE_BUILD_ACTION_CACHE_KEY_JOB: build\s*$/m, `${job} must restore the build cache`);
    assert.match(body, /read-only: true/, `${job} must not overwrite the build cache`);
    assert.match(body, /uses: \.\/\.github\/actions\/gradle-ci/, `${job} must use the shared Gradle setup`);
  }
});