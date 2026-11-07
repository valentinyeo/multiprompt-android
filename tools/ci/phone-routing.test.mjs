import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { needsPhoneProof } from './phone-routing.mjs';

test('unrelated changes skip phones, mixed or unknown changes run them', () => {
  assert.equal(needsPhoneProof(['README.md', 'docs/guide.md', 'tools/release-notes/ticket-titles.json']), false);
  for (const path of ['app/src/main/AndroidManifest.xml', 'gradlew', 'gradle/libs.versions.toml', 'build.gradle.kts', '.github/actions/android-proof/run.sh', '.github/workflows/test.yml', 'tools/check-session-rows.sh', '.claude/skills/verify-multiprompt-android/scripts/drive.sh', 'worker/index.js']) {
    assert.equal(needsPhoneProof(['docs/guide.md', path]), true, path);
  }
  assert.equal(needsPhoneProof([]), true);
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
  const route = (base, head, expected) => {
    const output = join(dir, '.git', 'routing-output');
    writeFileSync(output, '');
    const result = spawnSync(process.execPath, [script], {
      cwd: dir,
      env: { ...process.env, BASE_SHA: base, HEAD_SHA: head, GITHUB_OUTPUT: output },
      encoding: 'utf8',
    });
    if (expected === null) {
      assert.notEqual(result.status, 0, result.stderr);
      assert.equal(readFileSync(output, 'utf8'), '');
    } else {
      assert.equal(result.status, 0, result.stderr);
      assert.equal(readFileSync(output, 'utf8'), `run=${expected}\n`);
    }
  };
  try {
    git('init');
    file('app/old.txt', 'app');
    const base = commit('base');
    file('docs/guide.md', 'guide');
    const docs = commit('docs');
    route(base, docs, false);
    file('app/new.txt', 'new');
    const mixed = commit('app');
    route(base, mixed, true);
    git('rm', 'app/old.txt');
    const deleted = commit('delete');
    route(mixed, deleted, true);
    git('mv', 'app/new.txt', 'docs/renamed.txt');
    const renamed = commit('rename');
    route(deleted, renamed, true);
    git('checkout', '--detach', base);
    file('app/base-only.txt', 'base branch');
    const advancedBase = commit('advance base');
    route(advancedBase, docs, false);
    route('', docs, null);
    route(base, '', null);
    route('not-a-revision', docs, null);
    route(base, 'not-a-revision', null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('ancillary workflows retain PR inputs, schedules, manual runs and cancellation', () => {
  for (const name of ['account-journey', 'app-name', 'reply-notifications', 'top-fade', 'verify']) {
    const workflow = readFileSync(`.github/workflows/${name}.yml`, 'utf8');
    const events = workflow.split('\npermissions:')[0];
    const paths = events.match(/  pull_request:\n    paths:\n((?:      - .*\n)+)/)?.[1];
    assert.ok(paths, name);
    for (const path of ['app/**', '.github/actions/android-proof/**', 'gradle.properties', '*.gradle.kts', 'gradlew', 'gradlew.bat', 'gradle/**']) {
      assert.ok(paths.includes(`      - '${path}'\n`), `${name}: ${path}`);
    }
    assert.match(events, /  schedule:\n    - cron: /, name);
    assert.match(events, /  workflow_dispatch:/, name);
    assert.match(workflow, /  cancel-in-progress: true/, name);
  }
});

test('all phone contexts retain routing, failure and cancellation gates', () => {
  const workflow = readFileSync('.github/workflows/test.yml', 'utf8');
  assert.match(workflow, /cancel-in-progress: true/);
  const router = workflow.split('  phone-routing:\n')[1]?.split(/\n  [\w-]+:\n/)[0];
  assert.match(router, /uses: actions\/checkout@v4[\s\S]*?if: github\.event_name == 'pull_request'[\s\S]*?name: Phone routing regression proof\n\s*if: github\.event_name == 'pull_request'\n\s*run: node --test tools\/ci\/phone-routing\.test\.mjs\n\s*- id: route/);
  assert.match(router, /if \[ "\$EVENT_NAME" = pull_request \]; then[\s\S]*?else\n\s*echo 'run=true'/);
  for (const name of ['image-preview-emulator', 'reply-notifications-emulator', 'session-rows-emulator', 'session-pins-emulator', 'message-actions-emulator', 'approved-style-emulator']) {
    const job = workflow.split(`  ${name}:\n`)[1]?.split(/\n  [\w-]+:\n/)[0];
    assert.ok(job, name);
    assert.match(job, new RegExp(`name: ${name}\\n`));
    assert.match(job, /needs: phone-routing/);
    assert.match(job, /if: always\(\) && !cancelled\(\)/);
    assert.match(job, /test "\$ROUTING_RESULT" = success/);
    for (const step of job.split(/\n      - /).slice(2)) {
      assert.match(step, /if: \$\{\{ (?:always\(\) && )?needs\.phone-routing\.outputs\.run == 'true' \}\}/, name);
    }
  }
});
