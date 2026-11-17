import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { phoneProofJobs, phoneProofPaths } from './phone-routing.mjs';

const jobs = ['image-preview-emulator', 'reply-notifications-emulator', 'session-rows-emulator', 'session-pins-emulator', 'message-actions-emulator', 'approved-style-emulator'];
const all = run => Object.fromEntries(jobs.map(job => [job, run]));
const selected = (...names) => ({ ...all(false), ...Object.fromEntries(names.map(name => [name, true])) });

test('unrelated PR files do not allocate phone runners', () => {
  for (const path of ['README.md', 'docs/guide.md', 'tools/release-notes/ticket-titles.json', 'tools/pr-checks.mjs', '.claude/skills/verify-multiprompt-android/features/hosts.md', '.github/workflows/release.yml', 'worker/index.js', 'app/src/test/java/ExampleTest.kt']) {
    assert.deepEqual(phoneProofJobs([path]), all(false), path);
  }
});

test('app source, build inputs, shared emulator and routing changes run all phones', () => {
  for (const path of ['app/src/main/AndroidManifest.xml', 'app/src/main/assets/chat_history_reader.py', 'app/src/debug/java/Fixture.kt', 'app/src/m3/res/values/strings.xml', 'app/src/release/AndroidManifest.xml', 'app/src/androidTest/AndroidManifest.xml', 'app/proguard-rules.pro', 'gradlew', 'gradlew.bat', 'gradle.properties', 'gradle/libs.versions.toml', 'settings.gradle.kts', 'app/build.gradle.kts', '.github/actions/android-proof/run.sh', '.github/workflows/test.yml', 'tools/ci/phone-routing.mjs', 'tools/ci/phone-routing.test.mjs']) {
    assert.deepEqual(phoneProofJobs(['docs/guide.md', path]), all(true), path);
  }
  assert.deepEqual(phoneProofJobs([]), all(true));
});

test('shared journey fixtures and drivers run all required phones', () => {
  for (const path of [
    '.claude/skills/verify-multiprompt-android/scripts/fixture.sh',
    '.claude/skills/verify-multiprompt-android/scripts/seed.sh',
    '.claude/skills/verify-multiprompt-android/scripts/drive.sh',
    '.claude/skills/verify-multiprompt-android/scripts/console.py',
    '.claude/skills/verify-multiprompt-android/scripts/chat-search-drive.sh',
    'tools/verify-seed.py',
    'tools/long-chat/fixture.py',
  ]) assert.deepEqual(phoneProofJobs([path]), all(true), path);
});

test('a changed proof runs only its owning jobs', () => {
  assert.deepEqual(phoneProofJobs(['tools/check-session-rows.sh']), selected('session-rows-emulator'));
  assert.deepEqual(phoneProofJobs(['tools/session-pins/HistoricalPinsOracle.kt']), selected('session-pins-emulator'));
  assert.deepEqual(phoneProofJobs(['app/src/androidTest/java/dev/multiprompt/companion/notify/ReplyNotificationsTest.kt']), selected('reply-notifications-emulator'));
  assert.deepEqual(phoneProofJobs(['app/src/androidTest/java/dev/multiprompt/companion/reader/MessageProtocolTest.kt']), selected('message-actions-emulator'));
  assert.deepEqual(phoneProofJobs(['app/src/androidTest/java/dev/multiprompt/companion/ui/TopFadeFixture.kt']), selected('image-preview-emulator', 'approved-style-emulator'));
  assert.deepEqual(phoneProofJobs(['tools/check-day-theme-images.py']), selected('image-preview-emulator', 'approved-style-emulator'));
  assert.deepEqual(phoneProofJobs(['tools/check-message-actions.sh', 'tools/check-session-pins.sh']), selected('message-actions-emulator', 'session-pins-emulator'));
});

test('pushes, schedules and manual runs never filter phones', () => {
  for (const event of ['push', 'schedule', 'workflow_dispatch']) {
    assert.deepEqual(phoneProofJobs(['docs/guide.md'], event), all(true));
  }
});

test('every instrumentation class and shared fixture has an owner', () => {
  const scripts = {
    'image-preview-emulator': ['image-preview', 'day-theme'],
    'reply-notifications-emulator': ['reply-notifications'],
    'session-rows-emulator': ['session-rows'],
    'session-pins-emulator': ['session-pins'],
    'message-actions-emulator': ['message-actions'],
    'approved-style-emulator': ['approved-style'],
  };
  for (const [job, names] of Object.entries(scripts)) {
    for (const name of names) {
      const source = readFileSync(`tools/check-${name}.sh`, 'utf8');
      for (const match of source.matchAll(/dev\.multiprompt\.companion\.(?:\w+\.)*\w+(?:Test|Oracle)\b/g)) {
        const path = `app/src/androidTest/java/${match[0].replaceAll('.', '/')}.kt`;
        if (path.endsWith('/HistoricalPinsOracle.kt')) continue;
        assert.equal(phoneProofJobs([path])[job], true, `${job}: ${path}`);
      }
    }
  }
  assert.deepEqual(Object.keys(phoneProofPaths), jobs);
});

test('CLI routes real PR diffs and rejects missing or invalid revisions', () => {
  const dir = mkdtempSync(join(tmpdir(), 'phone-routing-'));
  const script = fileURLToPath(new URL('./phone-routing.mjs', import.meta.url));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' }).trim();
  const file = (path, content) => {
    const target = join(dir, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content);
  };
  const commit = message => {
    git('add', '-A');
    git('-c', 'user.name=CI', '-c', 'user.email=ci@example.com', 'commit', '-m', message);
    return git('rev-parse', 'HEAD');
  };
  const route = (base, head, expected, event = 'pull_request') => {
    const output = join(dir, '.git', 'routing-output');
    writeFileSync(output, '');
    const result = spawnSync(process.execPath, [script], {
      cwd: dir,
      env: { ...process.env, BASE_SHA: base, HEAD_SHA: head, EVENT_NAME: event, GITHUB_OUTPUT: output },
      encoding: 'utf8',
    });
    if (expected === null) {
      assert.notEqual(result.status, 0, result.stderr);
      assert.equal(readFileSync(output, 'utf8'), '');
    } else {
      assert.equal(result.status, 0, result.stderr);
      assert.deepEqual(Object.fromEntries(readFileSync(output, 'utf8').trim().split('\n').map(line => {
        const [name, run] = line.split('=');
        assert.ok(run === 'true' || run === 'false');
        return [name, run === 'true'];
      })), expected);
    }
  };
  try {
    git('init');
    file('app/src/main/old.txt', 'app');
    const base = commit('base');
    file('docs/guide.md', 'guide');
    const docs = commit('docs');
    route(base, docs, all(false));
    file('tools/check-session-rows.sh', 'proof');
    const proof = commit('proof');
    route(docs, proof, selected('session-rows-emulator'));
    file('app/src/main/new.txt', 'new');
    const mixed = commit('app');
    route(base, mixed, all(true));
    git('rm', 'app/src/main/old.txt');
    const deleted = commit('delete');
    route(mixed, deleted, all(true));
    git('mv', 'app/src/main/new.txt', 'docs/renamed.txt');
    const renamed = commit('rename');
    route(deleted, renamed, all(true));
    git('checkout', '--detach', base);
    file('app/src/main/base-only.txt', 'base branch');
    const advancedBase = commit('advance base');
    route(advancedBase, docs, all(false));
    route('', docs, null);
    route(base, '', null);
    route('not-a-revision', docs, null);
    route(base, 'not-a-revision', null);
    for (const event of ['push', 'schedule', 'workflow_dispatch']) route('', '', all(true), event);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('ancillary emulator workflows filter PRs but retain nightly and manual runs', () => {
  for (const name of ['account-journey', 'app-name', 'reply-notifications', 'top-fade']) {
    const workflow = readFileSync(`.github/workflows/${name}.yml`, 'utf8');
    const events = workflow.split('\npermissions:')[0];
    const paths = events.match(/  pull_request:\n    paths:\n((?:      - .*\n)+)/)?.[1];
    assert.ok(paths, name);
    for (const path of ['app/src/main/**', 'app/src/debug/**', 'app/src/m3/**', 'app/src/release/**', 'app/*.gradle.kts', '.github/actions/android-proof/**', 'gradle.properties', '*.gradle.kts', 'gradlew', 'gradlew.bat', 'gradle/**']) {
      assert.ok(paths.includes(`      - '${path}'\n`), `${name}: ${path}`);
    }
    assert.ok(!paths.includes("      - 'app/**'\n"), name);
    assert.ok(!paths.includes('.claude/'), name);
    assert.match(events, /  schedule:\n    - cron: /, name);
    assert.match(events, /  workflow_dispatch:/, name);
  }
});

test('all PR workflows cancel older runs with a workflow-specific group', () => {
  for (const name of readdirSync('.github/workflows').filter(name => name.endsWith('.yml'))) {
    const workflow = readFileSync(`.github/workflows/${name}`, 'utf8');
    if (!/^  pull_request:/m.test(workflow)) continue;
    assert.match(workflow, /\nconcurrency:\n  group: .+\$\{\{ .+ \}\}\n  cancel-in-progress: true/, name);
  }
});

test('required phone jobs skip before runner allocation and routing failures stay visible', () => {
  const workflow = readFileSync('.github/workflows/test.yml', 'utf8');
  const events = workflow.split('\npermissions:')[0];
  assert.doesNotMatch(events, /\n    paths:/);
  assert.match(events, /  pull_request:/);
  assert.match(events, /  push:\n    branches: \[main\]/);
  assert.match(events, /  schedule:/);
  const router = workflow.split('  phone-routing:\n')[1]?.split(/\n  [\w-]+:\n/)[0];
  assert.match(router, /run: node --test tools\/ci\/phone-routing\.test\.mjs/);
  assert.match(router, /run: node tools\/ci\/phone-routing\.mjs/);
  assert.doesNotMatch(router, /continue-on-error|\n    if:/);
  for (const name of jobs) {
    assert.ok(router.includes(`${name}: \${{ steps.route.outputs.${name} }}`));
    const job = workflow.split(`  ${name}:\n`)[1]?.split(/\n  [\w-]+:\n/)[0];
    assert.ok(job, name);
    assert.match(job, new RegExp(`name: ${name}\\n`));
    assert.match(job, /needs: phone-routing/);
    assert.ok(job.includes(`    if: needs.phone-routing.outputs.${name} == 'true'\n`), name);
    assert.doesNotMatch(job, /always\(\) && !cancelled\(\)|outputs\.run/);
  }
});
