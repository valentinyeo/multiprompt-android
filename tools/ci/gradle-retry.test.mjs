import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('gradle-retry.sh', import.meta.url));

function run(answers, extraEnv = {}) {
  const directory = mkdtempSync(path.join(tmpdir(), 'gradle-retry-'));
  const counter = path.join(directory, 'calls');
  const gradleHome = path.join(directory, 'gradle-home');
  const modules = path.join(gradleHome, 'caches', 'modules-2');
  mkdirSync(modules, { recursive: true });
  writeFileSync(path.join(modules, 'cached.jar'), 'jar');
  writeFileSync(path.join(directory, 'answers'), `${answers.map(answer => answer.replace(/\n/g, '\\n')).join('\n')}\n`);
  writeFileSync(counter, '');
  const gradle = path.join(directory, 'gradle');
  writeFileSync(
    gradle,
    [
      '#!/usr/bin/env bash',
      `echo x >> "${counter}"`,
      `echo "$*" >> "${directory}/args"`,
      `line="$(sed -n "$(wc -l < "${counter}")p" "${directory}/answers")"`,
      'printf \'%b\\n\' "$line"',
      'case "$line" in ok) exit 0 ;; broken) exit 3 ;; *) exit 1 ;; esac'
    ].join('\n')
  );
  chmodSync(gradle, 0o755);
  const result = spawnSync('bash', [script, '--no-daemon', 'assembleDebug'], {
    encoding: 'utf8',
    env: {
      ...process.env,
      GRADLE_BIN: gradle,
      GRADLE_USER_HOME: gradleHome,
      GRADLE_RETRY_BACKOFF_SECONDS: '0',
      ...extraEnv
    }
  });
  const args = readFileSync(path.join(directory, 'args'), 'utf8').trim().split('\n');
  return { result, calls: (readFileSync(counter, 'utf8').match(/x/g) ?? []).length, args, modules };
}

test('a busy repository is retried and the build then passes', () => {
  const { result, calls } = run([
    "Could not GET 'https://repo.maven.apache.org/maven2/x.jar'. Received status code 429 from server: Too Many Requests",
    'Read timed out',
    'ok'
  ]);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(calls, 3);
  assert.match(result.stdout, /Read timed out/);
});

test('a source error fails at once without a retry', () => {
  const { result, calls } = run(['broken', 'ok', 'ok']);
  assert.equal(result.status, 3);
  assert.equal(calls, 1);
  assert.doesNotMatch(result.stderr, /retrying/);
});

test('a repository that stays busy stops after five retries', () => {
  const { result, calls } = run(Array.from({ length: 7 }, () => '> Could not resolve all artifacts for configuration classpath'));
  assert.equal(result.status, 1);
  assert.equal(calls, 6);
  assert.match(result.stderr, /gave up after 6 attempts/);
});

test('a server error is treated as a transient download failure', () => {
  const { result, calls } = run([
    "Could not GET 'https://repo.maven.apache.org/maven2/x.jar'. Received status code 503 from server: Service Unavailable",
    'ok'
  ]);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(calls, 2);
});

test('a restored cache missing jars is cleared and retried once with refreshed dependencies', () => {
  const { result, calls, args, modules } = run([
    [
      '> Could not resolve all files for configuration \':app:debugUnitTestCompileClasspath\'.',
      '   > Failed to transform robolectric-4.16.1.jar (org.robolectric:robolectric:4.16.1).',
      '      > Could not find robolectric-4.16.1.jar (org.robolectric:robolectric:4.16.1).'
    ].join('\n'),
    'ok'
  ]);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(calls, 2);
  assert.equal(existsSync(modules), false, 'the incomplete modules cache must be cleared');
  assert.doesNotMatch(args[0], /--refresh-dependencies/);
  assert.match(args[1], /--refresh-dependencies/);
  assert.match(result.stderr, /restored download cache is incomplete/);
});

test('a cache that stays broken fails after the single repair instead of burning the retry window', () => {
  const answer = "Could not read '/home/runner/.gradle/caches/modules-2/files-2.1/x.jar'\n> Could not resolve all artifacts for configuration classpath";
  const { result, calls } = run([answer, answer, 'ok']);
  assert.equal(result.status, 1);
  assert.equal(calls, 2);
  assert.doesNotMatch(result.stderr, /network failure/);
});

test('retries stop at the retry window even when attempts remain', () => {
  const { result, calls } = run(['> Could not resolve all artifacts for configuration classpath', 'ok'], {
    GRADLE_RETRY_BACKOFF_SECONDS: '1',
    GRADLE_RETRY_WINDOW_SECONDS: '0'
  });
  assert.equal(result.status, 1);
  assert.equal(calls, 1);
  assert.match(result.stderr, /past the 0s retry window/);
});
