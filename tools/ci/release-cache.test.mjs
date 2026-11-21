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

const gradleWorkflows = () =>
  readdirSync(directory)
    .filter(file => file.endsWith('.yml'))
    .filter(file => read(file).includes('gradle/actions/setup-gradle@v4'));

test('a release never saves a Gradle cache that later releases restore', () => {
  const steps = blocks(read('release.yml'));
  assert.equal(steps.length, 1, 'release.yml setup-gradle step count changed, recheck the cache rule');
  assert.match(steps[0], /^\s+cache-read-only: true\s*$/m, 'release.yml must set cache-read-only: true: a failed release saved a Gradle cache that every retry restored');
});

test('release on main waits for Android tests so merge does not download alongside five Gradle jobs', () => {
  const release = read('release.yml');
  assert.match(release, /workflow_run:\n    workflows: \["Android tests"\]/);
  assert.doesNotMatch(release, /^  push:\n    branches: \[main\]/m);
  assert.match(release, /github\.event\.workflow_run\.conclusion == 'success'/);
  assert.match(release, /ref: \$\{\{ github\.event\.workflow_run\.head_sha \|\| github\.sha \}\}/);
});

test('Android tests writes the dependency cache once, then the Gradle phases only read it', () => {
  const workflow = read('test.yml');
  const buildStep = blocks(jobBody(workflow, 'build')).join('\n');
  assert.doesNotMatch(buildStep, /cache-read-only:\s*true/, 'the build job must keep writing the Gradle cache on main');
  for (const job of ['test', 'unit-tests', 'm3-unit-tests', 'lint']) {
    const jobSource = jobBody(workflow, job);
    assert.match(jobSource, /needs: build/, `${job} must wait for the build job to save dependencies`);
    const step = blocks(jobSource).join('\n');
    assert.match(step, /^\s+cache-read-only: true\s*$/m, `${job} must not write the Gradle cache`);
  }
});

test('test workflows keep saving the Gradle cache', () => {
  for (const file of ['test.yml', 'android-full.yml']) {
    const steps = blocks(read(file));
    assert.ok(steps.length > 0, `${file} has no setup-gradle step`);
    const writers = steps.filter(step => !/cache-read-only:\s*true/.test(step));
    assert.ok(writers.length >= 1, `${file} must keep at least one Gradle cache writer`);
  }
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