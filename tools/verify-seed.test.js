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
adb() { printf '%s\\n' "$*" >> "$EVIDENCE/actions"; echo reader > "$EVIDENCE/current.xml"; }
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
dump_ui() { cp "$EVIDENCE/page-$page.xml" "$EVIDENCE/current.xml"; if [[ "$opened" == true ]]; then sed -i 's/<hierarchy>/<hierarchy opened="true">/' "$EVIDENCE/current.xml"; fi; }
dismiss_system_anr() { :; }
sleep() { :; }
adb() {
    if [[ "$3" == swipe ]]; then
        if (( $5 > $7 )); then page=$((page < 10 ? page + 1 : 10)); else page=$((page > 0 ? page - 1 : 0)); fi
    else
        opened=true
        printf 'TAPPED_PAGE=%s\\n' "$page"
    fi
}
scripts=${JSON.stringify(scripts)}
EVIDENCE=${JSON.stringify(directory)}
page=0
opened=false
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
opened=false
wait_for_text ${target}-neighbor
[[ "$page" == 0 ]]
page=0
opened=false
tap_text ${target}
`], { encoding: 'utf8', timeout: 60000 });
      assert.equal(result.status, 0, result.stderr);
      assert.deepEqual(result.stdout.trim().split('\n'), ['TAPPED_PAGE=10', 'TAPPED_PAGE=10']);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

test('chosen-target search rejects the retained stalled 42-result scan and query-only matches', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'seed-search-replay-'));
  const assertions = driver.match(/    shot search-match\n([\s\S]*?)    tap_desc 'Close session search'/)[1];
  const stalled = readFileSync(path.join(root, 'tools/fixtures/verify-seed/search-stalled-44166046469.xml'), 'utf8');
  const result = name => `<hierarchy><node package="dev.multiprompt.companion.debug" bounds="[0,0][1080,1920]"><node text="1 result across all splits" bounds="[20,200][800,250]"/><node class="android.widget.EditText" text="cl-long-chat" bounds="[20,100][800,180]"/><node clickable="true" bounds="[20,300][1000,450]"><node content-desc="Robot ${name}" bounds="[42,320][168,430]"/></node></node></hierarchy>`;
  try {
    for (const [xml, passes] of [[stalled, false], [result('different-session'), false], [result('cl-long-chat'), true]]) {
      writeFileSync(path.join(directory, 'current.xml'), xml);
      const attempt = spawnSync('bash', ['-euc', `
scripts=${JSON.stringify(scripts)}
EVIDENCE=${JSON.stringify(directory)}
deep_description='Robot cl-long-chat'
${assertions}
`], { encoding: 'utf8' });
      assert.equal(attempt.status === 0, passes, attempt.stderr);
      assert.equal(existsSync(path.join(directory, 'seed-search.xml')), passes);
      if (passes) assert.equal(readFileSync(path.join(directory, 'seed-search.xml'), 'utf8'), xml);
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('reveal uses the existing scroll planner but never taps the row', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'seed-reveal-'));
  try {
    const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('tap_node')}
dump_ui() { printf '%s\\n' "$state" > "$EVIDENCE/current.xml"; }
python3() { if [[ -f "$EVIDENCE/scrolled" ]]; then echo 'tap 10 20'; else echo 'swipe 10 80 10 20'; fi; }
adb() { printf '%s\\n' "$*"; touch "$EVIDENCE/scrolled"; state=changed; }
scripts=unused
EVIDENCE=${JSON.stringify(directory)}
state=before
tap_node text verify-session reveal
state=before
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

for (const landsOn of [1, 2, 4, 99]) {
  test(`a tap is checked with a fresh dump and retried only until it lands (${landsOn})`, () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'tap-retry-'));
    try {
      const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('tap_node')}
scripts=unused
EVIDENCE=${JSON.stringify(directory)}
landed_taps=0
dump_ui() { if (( landed_taps >= ${landsOn} )); then echo reader; else echo inbox; fi > "$EVIDENCE/current.xml"; }
python3() { printf 'tap %s 20\\n' "$((10 + landed_taps))"; }
adb() { landed_taps=$((landed_taps + 1)); printf '%s\\n' "$*"; }
tap_node text Settings
printf 'DUMP=%s\\n' "$(cat "$EVIDENCE/current.xml")"
`], { encoding: 'utf8', timeout: 10000 });
      assert.equal(result.status, 0, result.stderr);
      const lines = result.stdout.trim().split('\n');
      const taps = Math.min(landsOn, 4);
      assert.deepEqual(lines.slice(0, taps), Array.from({ length: taps }, (_, i) => `shell input tap ${10 + i} 20`));
      assert.equal(lines[taps], `DUMP=${landsOn <= 4 ? 'reader' : 'inbox'}`);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });
}

for (const [landsOn, visible, duplicates, expectedCalls, succeeds] of [
  [1, true, false, 1, true],
  [2, true, false, 2, true],
  [99, true, false, 2, false],
  [99, false, false, 1, false],
  [1, true, true, 1, false]
]) {
  test(`native model selection requires exactly one new receipt (${landsOn}, ${visible}, ${duplicates})`, () => {
    const source = readFileSync(path.join(scripts, 'native-menu-drive.sh'), 'utf8');
    const helper = source.match(/^tap_menu_receipt\(\) \{[\s\S]*?^\}/m)[0];
    const result = spawnSync('bash', ['-euc', `
${helper}
landed_taps=0
sudo() { if (( landed_taps >= ${landsOn} )); then echo ${duplicates ? 2 : 1}; else echo 0; fi; }
tap_text() { landed_taps=$((landed_taps + 1)); printf 'TAP %s\\n' "$1"; }
select_node() { ${visible ? 'return 0' : 'return 1'}; }
sleep() { :; }
if tap_menu_receipt Haiku '/model haiku'; then status=0; else status=$?; fi
printf 'CALLS=%s\\n' "$landed_taps"
exit "$status"
`], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status === 0, succeeds, result.stderr);
    assert.match(result.stdout, new RegExp(`CALLS=${expectedCalls}`));
    if (!succeeds && !duplicates) assert.match(result.stderr, /NATIVE_MENU_RECEIPT_MISSING: VERIFY_RECEIVED:\/model haiku/);
  });
}

test('a late toast cannot confirm that the requested native submenu opened', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'tap-expected-'));
  try {
    const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('tap_node')}
scripts=unused
EVIDENCE=${JSON.stringify(directory)}
landed_taps=0
dump_ui() { printf 'Attach toast-%s' "$landed_taps" > "$EVIDENCE/current.xml"; if (( landed_taps >= 2 )); then echo Low >> "$EVIDENCE/current.xml"; fi; }
python3() { if [[ "$3" == text ]]; then grep -q "$4" "$EVIDENCE/current.xml"; else echo 'tap 10 20'; fi; }
adb() { landed_taps=$((landed_taps + 1)); }
tap_node text Effort tap Low
printf 'CALLS=%s\\n' "$landed_taps"
`], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'CALLS=2');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('terminal key input is single-shot when its canvas leaves the UI dump unchanged', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'tap-key-'));
  try {
    const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('tap_node')}
scripts=unused
EVIDENCE=${JSON.stringify(directory)}
key_presses=0
dump_ui() { echo canvas > "$EVIDENCE/current.xml"; }
python3() { echo 'tap 10 20'; }
adb() { key_presses=$((key_presses + 1)); }
tap_node desc 'Tab key' once
printf 'KEYS=%s\\n' "$key_presses"
`], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'KEYS=1');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('the selected Medium summary is not the exact Medium submenu option', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'tap-exact-option-'));
  try {
    const result = spawnSync('bash', ['-euc', `
${shellFunction('session_name')}
${shellFunction('tap_node')}
scripts=${JSON.stringify(scripts)}
EVIDENCE=${JSON.stringify(directory)}
landed_taps=0
dump_ui() { if (( landed_taps == 0 )); then value='Medium ›'; else value=Medium; fi; printf '<hierarchy><node text="%s" clickable="true" enabled="true" bounds="[0,0][100,100]"/></hierarchy>' "$value" > "$EVIDENCE/current.xml"; }
adb() { landed_taps=$((landed_taps + 1)); }
tap_node text 'Medium ›' tap Medium
printf 'CALLS=%s\\n' "$landed_taps"
`], { encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'CALLS=1');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
