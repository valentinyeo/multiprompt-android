import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

const directory = new URL('../../.github/workflows/', import.meta.url);
const read = file => readFileSync(new URL(file, directory), 'utf8');
const blocks = source => [...source.matchAll(/^(\s*)- uses: gradle\/actions\/setup-gradle@v4\s*$\n([\s\S]*?)(?=^\1- |$(?![\s\S]))/gm)].map(match => match[2]);
const jobBody = (source, job) => {
  const chunk = source.split(new RegExp(`^  ${job}:\\n`, 'm'))[1];
  assert.ok(chunk, `missing job ${job}`);
  return chunk.split(/^  [a-z][\w-]*:\n/m)[0];
};
const jobs = source => [...source.matchAll(/^  ([\w-]+):$/gm)].map(match => match[1]);
const cacheJob = source => source.match(/^\s+GRADLE_BUILD_ACTION_CACHE_KEY_JOB:\s*(\S+)\s*$/m)?.[1];

const gradleWorkflows = () =>
  readdirSync(directory)
    .filter(file => file.endsWith('.yml'))
    .filter(file => read(file).includes('gradle/actions/setup-gradle@v4'));

test('a release never saves a Gradle cache that later releases restore', () => {
  const steps = blocks(read('release.yml'));
  assert.equal(steps.length, 1, 'release.yml setup-gradle step count changed, recheck the cache rule');
  assert.match(steps[0], /^\s+cache-read-only: true\s*$/m, 'release.yml must set cache-read-only: true: a failed release saved a Gradle cache that every retry restored');
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
  const step = blocks(jobBody(workflow, target)).join('\n');
  assert.ok(step, `${target} uses no setup-gradle`);
  assert.doesNotMatch(step, /cache-read-only:\s*true/, `${target} must write the cache a release restores`);
});

test('Android tests keeps its five jobs in parallel', () => {
  const workflow = read('test.yml');
  assert.deepEqual(jobs(workflow.split('\njobs:\n')[1]), ['build', 'test', 'unit-tests', 'm3-unit-tests', 'lint']);
  assert.doesNotMatch(workflow, /^\s+needs:/m, 'no Android tests job may wait for another: serialized checks make every pull request slower');
});

test('test workflows keep saving the Gradle cache', () => {
  for (const file of ['test.yml', 'android-full.yml']) {
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
    assert.ok(setupCount >= 1, `${file} must call setup-gradle`);
    const homeCache = (source.match(/actions\/cache@v4[\s\S]*?\.gradle\/caches/g) ?? []).length;
    assert.equal(homeCache, 0, `${file} must not stack actions/cache on ~/.gradle beside setup-gradle`);
  }
});

test('verify journeys avoid stale dependency metadata from older Gradle home caches', () => {
  const steps = blocks(read('verify.yml'));
  assert.equal(steps.length, 1, 'verify.yml setup-gradle step count changed, recheck the cache rule');
  assert.match(steps[0], /^\s+gradle-home-cache-strict-match:\s*true\s*$/m);
  assert.doesNotMatch(steps[0], /gradle-home-cache-excludes:\s*\n\s*caches\/modules-2/);
});
