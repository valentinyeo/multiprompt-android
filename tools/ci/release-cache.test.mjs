import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const directory = new URL('../../.github/workflows/', import.meta.url);
const read = file => readFileSync(new URL(file, directory), 'utf8');
const blocks = source => [...source.matchAll(/^(\s*)- uses: gradle\/actions\/setup-gradle@v4\s*$\n([\s\S]*?)(?=^\1- |$(?![\s\S]))/gm)].map(match => match[2]);

test('a release never saves a Gradle cache that later releases restore', () => {
  const steps = blocks(read('release.yml'));
  assert.equal(steps.length, 1, 'release.yml setup-gradle step count changed, recheck the cache rule');
  assert.match(steps[0], /^\s+cache-read-only: true\s*$/m, 'release.yml must set cache-read-only: true: a failed release saved a Gradle cache that every retry restored');
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

test('verify journeys avoid stale dependency metadata from older Gradle home caches', () => {
  const steps = blocks(read('verify.yml'));
  assert.equal(steps.length, 1, 'verify.yml setup-gradle step count changed, recheck the cache rule');
  assert.match(steps[0], /^\s+gradle-home-cache-strict-match:\s*true\s*$/m);
  assert.doesNotMatch(steps[0], /gradle-home-cache-excludes:\s*\n\s*caches\/modules-2/);
});
