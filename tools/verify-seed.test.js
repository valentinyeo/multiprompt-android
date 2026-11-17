const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const scripts = path.join(root, '.claude/skills/verify-multiprompt-android/scripts');
const driver = readFileSync(path.join(scripts, 'drive.sh'), 'utf8');
const shellFunction = name => driver.match(new RegExp(`^${name}\\(\\) \\{[^\\n]*\\}$`, 'm'))?.[0]
  || driver.match(new RegExp(`^${name}\\(\\) \\{[\\s\\S]*?^\\}`, 'm'))[0];

for (const content of [null, '{', '{"sessions":400}']) {
  test(`driver rejects ${content === null ? 'missing' : content === '{' ? 'malformed' : 'fabricated'} seed evidence before any adb action`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'seed-driver-'));
    try {
      const bin = path.join(directory, 'bin');
      mkdirSync(bin);
      const evidence = path.join(directory, 'evidence');
      mkdirSync(evidence);
      if (content !== null) writeFileSync(path.join(evidence, 'seed-counts.json'), content);
      writeFileSync(path.join(bin, 'sudo'), `#!/usr/bin/env bash\nexec python3 -I ${JSON.stringify(path.join(root, 'tools/verify-seed.py'))} check "$EVIDENCE/seed-counts.json"\n`, { mode: 0o755 });
      writeFileSync(path.join(bin, 'adb'), '#!/usr/bin/env bash\ntouch "$FIXTURE/adb-called"\nexit 99\n', { mode: 0o755 });
      const result = spawnSync('bash', [path.join(scripts, 'drive.sh'), 'session-reader.md'], {
        encoding: 'utf8', cwd: root,
        env: { ...process.env, HOME: directory, PATH: `${bin}:${process.env.PATH}`, EVIDENCE: evidence, FIXTURE: directory, EXPECTED_VERSION: 'test' },
      });
      assert.notEqual(result.status, 0, result.stdout + result.stderr);
      assert.equal(existsSync(path.join(directory, 'adb-called')), false);
      assert.equal(existsSync(path.join(evidence, 'failure-class.txt')), false);
      assert.match(readFileSync(path.join(evidence, 'driver-transcript.txt'), 'utf8'), /verify-seed.py check/);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test('recorded description-only inbox row can be selected and opened by its exact session name', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'seed-desc-only-'));
  try {
    const xml = readFileSync(path.join(root, 'tools/fixtures/verify-seed/row-27-desc-only.xml'), 'utf8');
    assert.match(xml, /content-desc="Robot row-27"/);
    assert.doesNotMatch(xml, /text="row-27"/);
    writeFileSync(path.join(directory, 'current.xml'), xml);
    writeFileSync(path.join(directory, 'seed-counts.json'), JSON.stringify({ session_names: ['row-27'] }));
    const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('select_node')}
${shellFunction('tap_node')}
${shellFunction('tap_text')}
dump_ui() { :; }
adb() { printf '%s\\n' "$*" >> "$EVIDENCE/actions"; }
scripts=${JSON.stringify(scripts)}
EVIDENCE=${JSON.stringify(directory)}
select_node text row-27 >/dev/null
tap_text row-27
`], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(readFileSync(path.join(directory, 'actions'), 'utf8').trim(), 'shell input tap 540 532');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

for (const seededName of ['verify-session', 'live-verify-session', 'verify-keep', 'verify-other', 'verify-name', 'codex-native', 'claude-control', 'cl-long-chat']) {
  const target = seededName === 'live-verify-session' ? 'verify-session' : seededName;
  test(`finds exact ${seededName} past 41 inbox rows, including after switching readers`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'seed-target-'));
    try {
      const names = [`${target}-neighbor`, ...Array.from({ length: 40 }, (_, i) => `row-${i}`), target];
      writeFileSync(path.join(directory, 'seed-counts.json'), JSON.stringify({ session_names: names.map(name => name === target ? seededName : name) }));
      for (let page = 0; page < 11; page++) {
        const rows = names.slice(page * 4, page * 4 + 4).map((name, index) => {
          const bounds = `[20,${300 + index * 120}][1000,${400 + index * 120}]`;
          return `<node clickable="true" content-desc="Robot ${name === target ? seededName : name}" bounds="${bounds}"><node text="python3" bounds="${bounds}" /></node>`;
        }).join('');
        writeFileSync(path.join(directory, `page-${page}.xml`), `<hierarchy><node package="dev.multiprompt.companion.debug" bounds="[0,0][1080,1920]"><node text="Inbox" bounds="[0,0][1000,100]"/><node scrollable="true" bounds="[0,200][1080,1800]">${rows}</node></node></hierarchy>`);
      }
      const lastPage = readFileSync(path.join(directory, 'page-10.xml'), 'utf8');
      writeFileSync(path.join(directory, 'page-10-query.xml'), lastPage.replace('<node scrollable=', `<node class="android.widget.EditText" text="${target}" bounds="[0,110][1000,190]"/><node scrollable=`));
      const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('select_node')}
${shellFunction('tap_node')}
${shellFunction('tap_text')}
${shellFunction('wait_for_text')}
dump_ui() { cp "$EVIDENCE/page-$page.xml" "$EVIDENCE/current.xml"; }
dismiss_system_anr() { :; }
sleep() { :; }
adb() {
    if [[ "$3" == swipe ]]; then
        if (( $5 > $7 )); then page=$((page < 10 ? page + 1 : 10)); else page=$((page > 0 ? page - 1 : 0)); fi
    else
        printf 'TAPPED_PAGE=%s\\n' "$page"
    fi
}
scripts=${JSON.stringify(scripts)}
EVIDENCE=${JSON.stringify(directory)}
page=0
wait_for_text ${target}
[[ "$page" == 10 ]]
cp "$EVIDENCE/page-10.xml" "$EVIDENCE/page-10-original.xml"
cp "$EVIDENCE/page-10-query.xml" "$EVIDENCE/page-10.xml"
wait_for_text ${target}
select_node text ${target} >/dev/null
select_node desc 'Robot ${seededName}' >/dev/null
tap_text ${target}
cp "$EVIDENCE/page-10-original.xml" "$EVIDENCE/page-10.xml"
page=10
wait_for_text ${target}-neighbor
[[ "$page" == 0 ]]
page=0
tap_text ${target}
`], { encoding: 'utf8', timeout: 30000 });
      assert.equal(result.status, 0, result.stderr);
      assert.deepEqual(result.stdout.trim().split('\n'), ['TAPPED_PAGE=10', 'TAPPED_PAGE=10']);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

for (const initialPage of [0, 3, 6]) {
  test(`42-result proof scans both directions from page ${initialPage} in reversed inbox order`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'seed-unordered-inbox-'));
    try {
      const names = ['cl-long-chat', 'verify-session', ...Array.from({ length: 40 }, (_, i) => `seed-${i}`)];
      const counts = JSON.stringify({ sessions: 42, session_names: names });
      writeFileSync(path.join(directory, 'seed-counts.json'), counts);
      writeFileSync(path.join(directory, 'seed-check.json'), counts);
      const reversed = [...names].reverse();
      for (let page = 0; page < 7; page++) {
        const rows = reversed.slice(page * 6, page * 6 + 6).map((name, i) => `<node content-desc="Robot ${name}" bounds="[20,${300 + i * 100}][100,${380 + i * 100}]"/>`).join('');
        writeFileSync(path.join(directory, `page-${page}.xml`), `<hierarchy><node bounds="[0,0][1080,1920]"><node text="42 results across all splits" bounds="[0,100][1000,200]"/><node scrollable="true" bounds="[0,250][1080,1800]">${rows}</node></node></hierarchy>`);
      }
      const scan = driver.match(/fill 'Search all sessions' Verify\n([\s\S]*?)    fill Verify verify-session/)[1];
      const result = spawnSync('bash', ['-euc', `
EVIDENCE=${JSON.stringify(directory)}
page=${initialPage}
step=0
wait_for_text() { [[ "$1" == '42 results across all splits' ]]; }
shot() { step=$((step + 1)); cp "$EVIDENCE/page-$page.xml" "$EVIDENCE/$step-$1.xml"; }
select_node() { printf '540 300 540 1700\\n'; }
adb() {
    if (( $5 > $7 )); then page=$((page < 6 ? page + 1 : 6)); else page=$((page > 0 ? page - 1 : 0)); fi
}
${scan}
`], { cwd: root, encoding: 'utf8', timeout: 30000 });
      assert.equal(result.status, 0, result.stderr);
      const proof = JSON.parse(readFileSync(path.join(directory, 'seed-inbox.json'), 'utf8'));
      assert.equal(proof.sessions_visible, 42);
      assert.deepEqual(proof.session_names, names.sort());
      assert.equal(proof.displayed_count, '42 results across all splits');
      assert.ok(proof.pages.length >= 7);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test('reveal uses the existing scroll planner but never taps the row', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'seed-reveal-'));
  try {
    const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('tap_node')}
dump_ui() { :; }
python3() { if [[ -f "$EVIDENCE/scrolled" ]]; then echo 'tap 10 20'; else echo 'swipe 10 80 10 20'; fi; }
adb() { printf '%s\\n' "$*"; touch "$EVIDENCE/scrolled"; }
scripts=unused
EVIDENCE=${JSON.stringify(directory)}
tap_node text verify-session reveal
tap_node text verify-session
`], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.stdout.trim().split('\n'), ['shell input swipe 10 80 10 20 500', 'shell input tap 10 20']);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('default setup seeds once without changing dedicated long-chat and pins fixtures', () => {
  const fixture = readFileSync(path.join(scripts, 'fixture.sh'), 'utf8');
  const setup = fixture.slice(fixture.indexOf('if [[ "${VERIFY_FEATURE:-}" != long-chat.md'));
  for (const [feature, pins, expected] of [['sessions.md', 'false', 1], ['long-chat.md', 'false', 0], ['session-favorites.md', 'true', 0]]) {
    const directory = mkdtempSync(path.join(tmpdir(), 'seed-setup-'));
    try {
      const result = spawnSync('bash', ['-euc', `
sudo() { printf '%s\\n' "$*" >> "$EVIDENCE/commands"; }
fixture_session=verify-session
${setup}
`], { encoding: 'utf8', env: { ...process.env, VERIFY_FEATURE: feature, PIN_SCROLL_PROOF: pins, EVIDENCE: directory } });
      assert.equal(result.status, 0, result.stderr);
      const commands = existsSync(path.join(directory, 'commands')) ? readFileSync(path.join(directory, 'commands'), 'utf8') : '';
      assert.equal((commands.match(/bash \/home\/verifyssh\/seed.sh/g) || []).length, expected);
      assert.equal(existsSync(path.join(directory, 'seed-counts.json')), expected === 1);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});
