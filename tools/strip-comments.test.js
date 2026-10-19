const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const api = import('./strip-comments.mjs');

async function fixture(language, before, expected) {
  const { strip, verifyTokens } = await api;
  assert.equal(strip(before, language).output, expected);
  assert.equal(strip(expected, language).output, expected);
  verifyTokens(before, expected, language);
  assert.throws(() => verifyTokens(before, expected + '\nchanged_token', language));
}

test('Zig: doc comments, URLs, characters and multiline literal lines', async () => {
  await fixture('zig', String.raw`//! remove
/// remove
const a = "https://x/#//"; // remove
const c = '/';
const s =
    \\// literal # text
    \\//! still literal
;
`, String.raw`const a = "https://x/#//";
const c = '/';
const s =
    \\// literal # text
    \\//! still literal
;
`);
});

test('JavaScript/TypeScript: regex lexical goals and nested templates', async () => {
  const lines = [
    "const url = 'https://x/#';",
    String.raw`const r = /\/\//g;`,
    'const cls = /[/*]/;',
    'const divided = x / y / z;',
    String.raw`if (ok) /\/\//.test(url);`,
    'const value = { a: 1 } / 2;',
    'const t = `// # ${ { a: `nested ${"// #"}` }.a }`;',
  ];
  for (const language of ['javascript', 'typescript']) {
    await fixture(language, '// remove\n' + lines.map(l => l + ' // remove').join('\n') + '\n/* remove */\n', lines.join('\n') + '\n');
  }
});

test('Python: tokenizer/AST distinguish f-strings, docstrings and live strings', async () => {
  await fixture('python', `#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\n"""Remove this module docstring."""\n# remove\nurl = "https://x/#" # remove\nvalue = f"# {url} //"\ndef f():\n    """Remove function documentation."""\n    return '#' # type: ignore\ndef empty():\n    """The sole body must remain."""\n`, `#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\nurl = "https://x/#"\nvalue = f"# {url} //"\ndef f():\n    return '#' # type: ignore\ndef empty():\n    """The sole body must remain."""\n`);
});

test('Kotlin/Java/Gradle: nested Kotlin comments, characters and raw strings', async () => {
  await fixture('kotlin', `// remove\nval url = "https://x/#" // remove\nval c = '/'\nval raw = """// literal #\n/* literal */"""\n/* outer /* inner */ outer */\n//noinspection Unused\nval next = 1\n`, `val url = "https://x/#"\nval c = '/'\nval raw = """// literal #\n/* literal */"""\n//noinspection Unused\nval next = 1\n`);
  await fixture('java', `/* remove */\nString s = "https://x/#"; // remove\nchar c = '/';\nString block = """\n// literal\n""";\n`, `String s = "https://x/#";\nchar c = '/';\nString block = """\n// literal\n""";\n`);
});

test('PowerShell: here-strings, quote doubling, escapes and directives', async () => {
  const kept = ['#Requires -Version 7', "$a = 'it''s # literal //';", '$b = "escaped `" # literal"', "$c = @'", '# literal', '<# literal #>', "'@", '$d = @"', '// literal #', '"@', 'Write-Output $a'];
  await fixture('powershell', kept.join('\n') + '\n# remove\n<# outer <# inner #> outer #>\n', kept.join('\n') + '\n');
});

test('Shell: shebangs, directives, word hashes, quoted substitutions and heredocs', async () => {
  await fixture('shell', `#!/bin/sh\n# shellcheck disable=SC2086\n# remove\necho 'https://x/#' word#literal "$#" "$(printf '#')" # remove\ncat <<'EOF'\n# literal heredoc\nhttps://x/#\nEOF\ncat <<< "$value" # remove\n`, `#!/bin/sh\n# shellcheck disable=SC2086\necho 'https://x/#' word#literal "$#" "$(printf '#')"\ncat <<'EOF'\n# literal heredoc\nhttps://x/#\nEOF\ncat <<< "$value"\n`);
});

test('YAML: comments, quoted hashes, expressions, plain values and block scalars', async () => {
  const kept = ["single: '# literal it''s text'", 'double: "# literal \\" text"', "expression: ${{ contains('a#b', '#') }}", 'plain: a#b', "prose: can't stop", 'run: |', '  # shell text', '  echo "# script text"', 'folded: >-', '  # scalar text', 'explicit: |2-', '  # explicit scalar text', 'next: value'];
  await fixture('yaml', '# remove\n' + kept.map((line, n) => [0, 1, 2, 3, 12].includes(n) ? line + ' # remove' : line).join('\n') + '\n# remove\n', kept.join('\n') + '\n');
  await fixture('yaml', 'jobs:\r\n  run: |+ # remove\r\n    # shell text\r\n    echo done\r\n  next: [a#b, \"a # b\"] # remove\r\n# remove\r\n', 'jobs:\r\n  run: |+\r\n    # shell text\r\n    echo done\r\n  next: [a#b, \"a # b\"]\r\n');
  const { tokenize, languageFor } = await api;
  const source = '# full-line\nkey: value # trailing\n';
  assert.deepEqual(tokenize(source, 'yaml').comments.map(c => source.slice(c.start, c.end)), ['# full-line', '# trailing']);
  for (const file of ['workflow.yml', 'workflow.yaml']) assert.equal(languageFor(file), 'yaml');
});

test('TOML: comments and hashes inside basic, literal and multiline strings', async () => {
  const kept = ['basic = "# literal \\" text"', String.raw`literal = '# literal \'`, 'multibasic = """', '# multiline basic text', '"""', "multiliteral = '''", '# multiline literal text', "'''", 'value = 1'];
  await fixture('toml', '# remove\n' + kept.map((line, n) => [0, 1, 4, 7, 8].includes(n) ? line + ' # remove' : line).join('\n') + '\n# remove\n', kept.join('\n') + '\n');
  await fixture('toml', '# remove\r\nvalue = 1# remove\r\nbasic = """# literal """"" # remove\r\nliteral = \'\'\'# literal \'\'\'\'\' # remove\r\n', 'value = 1\r\nbasic = """# literal """""\r\nliteral = \'\'\'# literal \'\'\'\'\'\r\n');
  const { tokenize, languageFor } = await api;
  const source = '# full-line\nkey = 1 # trailing\n';
  assert.deepEqual(tokenize(source, 'toml').comments.map(c => source.slice(c.start, c.end)), ['# full-line', '# trailing']);
  assert.equal(languageFor('config.toml'), 'toml');
});

test('YAML: only pinned-action version tags are kept as tool directives', async () => {
  const pin = '11d5960a326750d5838078e36cf38b85af677262';
  const kept = `uses: actions/checkout@${pin} # v4\n`;
  await fixture('yaml', kept + `uses: actions/checkout@${pin} # explanation\nuses: actions/checkout@v4 # v4\nuses: actions/checkout@${pin.slice(1)} # v4\nuses: actions/checkout@${pin} # v4 explanation\nvalue: other@${pin} # v4\n`, kept + `uses: actions/checkout@${pin}\nuses: actions/checkout@v4\nuses: actions/checkout@${pin.slice(1)}\nuses: actions/checkout@${pin}\nvalue: other@${pin}\n`);
  const { tokenize } = await api;
  assert.equal(tokenize(kept, 'yaml').comments[0].reason, 'tool directive');
});

test('C/C++ headers: raw literals, escaped characters and token separators', async () => {
  await fixture('c', `/* remove */\nconst/* remove */char *s = "https://x/#"; // remove\nchar c = '\\'';\nauto raw = R"tag(// # /* literal */)tag";\n#define VALUE 1 /* remove */\n`, `const char *s = "https://x/#";\nchar c = '\\'';\nauto raw = R"tag(// # /* literal */)tag";\n#define VALUE 1\n`);
});

test('CSS: only block comments, not string contents or URL fragments', async () => {
  await fixture('css', `/* remove */\na { content: "/* literal */ // #"; background: url(https://x/#id); } /* remove */\n`, `a { content: "/* literal */ // #"; background: url(https://x/#id); }\n`);
});

test('HTML: quoted attributes, script regex/template strings and style blocks', async () => {
  const kept = ['<a href="https://x/#" title="<!-- literal -->">x</a>', '<script>', String.raw`const r = /\/\//;`, 'const t = `<!-- literal --> // #`;', '</script>', '<style>', 'a { content: "/* literal */"; }', '</style>'];
  const before = [...kept];
  before.splice(6, 0, '/* remove */'); before.splice(2, 0, '// remove'); before.unshift('<!-- remove -->');
  await fixture('html', before.join('\n') + '\n', kept.join('\n') + '\n');
});

test('license headers and directives retained in full, never join code lines', async () => {
  await fixture('javascript', `// Copyright 2026 Example\n// Licensed under MIT\n// Permission is hereby granted.\n// @ts-check\nconst a = 1; /* remove\n remove\n */ const b = 2; // remove\n`, `// Copyright 2026 Example\n// Licensed under MIT\n// Permission is hereby granted.\n// @ts-check\nconst a = 1;\n const b = 2;\n`);
});

test('protected directories/data and generated sources are excluded', async () => {
  const { languageFor } = await api;
  for (const file of ['vendor/x.js', 'third_party/x.kt', 'node_modules/x.js', 'skillsrepo/x.py', 'docs/x.html', 'docs-site/x.ts', 'openwiki/x.sh', 'CLAUDE.md', 'AGENTS.md', 'README', 'data.json', 'gradle/wrapper/gradle-wrapper.properties', 'tools/fixtures/x.html']) assert.equal(languageFor(file), null, file);
  assert.equal(languageFor('src/generated.js', '// automatically generated'), null);
  assert.equal(languageFor('assets/page.html'), 'html');
});

test('CLI --verify rejects token changes and protected modifications', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'strip-comments-test-'));
  const script = path.join(__dirname, 'strip-comments.mjs');
  const git = args => execFileSync('git', ['-C', root, ...args], { stdio: 'pipe' });
  try {
    git(['init', '-q']);
    fs.writeFileSync(path.join(root, 'source.js'), `const url = 'https://x/#'; // remove\n`);
    fs.writeFileSync(path.join(root, 'README.md'), '# documentation\n');
    fs.writeFileSync(path.join(root, 'source.zig'), '// Copyright 2026 Fixture\ntest {\n    // remove\n}\n');
    fs.writeFileSync(path.join(root, 'asset.png'), Buffer.from([0, 1, 2]));
    git(['add', '.']);
    git(['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture']);
    execFileSync(process.execPath, [script, root]);
    fs.writeFileSync(path.join(root, 'source.zig'), 'test {}\n');
    assert.notEqual(spawnSync(process.execPath, [script, '--verify', root]).status, 0);
    fs.writeFileSync(path.join(root, 'source.zig'), '// Copyright 2026 Fixture\ntest {}\n');
    execFileSync(process.execPath, [script, '--verify', root]);
    fs.writeFileSync(path.join(root, 'asset.png'), Buffer.from([0, 3, 2]));
    assert.notEqual(spawnSync(process.execPath, [script, '--verify', root]).status, 0);
    fs.writeFileSync(path.join(root, 'asset.png'), Buffer.from([0, 1, 2]));
    fs.appendFileSync(path.join(root, 'source.js'), 'changed_token;\n');
    assert.notEqual(spawnSync(process.execPath, [script, '--verify', root]).status, 0);
    fs.writeFileSync(path.join(root, 'source.js'), `const url = 'https://x/#';\n`);
    fs.writeFileSync(path.join(root, 'README.md'), '# modified documentation\n');
    assert.notEqual(spawnSync(process.execPath, [script, '--verify', root]).status, 0);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('CLI --check fails on a new code comment and names where it is', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'strip-comments-check-'));
  const script = path.join(__dirname, 'strip-comments.mjs');
  const git = args => execFileSync('git', ['-C', root, ...args], { stdio: 'pipe' });
  try {
    git(['init', '-q']);
    fs.writeFileSync(path.join(root, 'source.zig'), '// Copyright 2026 Fixture\nconst a = "https://x/#//";\n');
    fs.writeFileSync(path.join(root, 'tool.js'), '// eslint-disable-next-line\nconst b = 1;\n');
    fs.writeFileSync(path.join(root, 'README.md'), '# documentation\n');
    git(['add', '.']);
    assert.equal(spawnSync(process.execPath, [script, '--check', root]).status, 0);
    fs.appendFileSync(path.join(root, 'tool.js'), 'const c = 2; // explains c\n');
    const failed = spawnSync(process.execPath, [script, '--check', root], { encoding: 'utf8' });
    assert.equal(failed.status, 1);
    assert.match(failed.stderr, /tool\.js:3: \/\/ explains c/);
    assert.equal(fs.readFileSync(path.join(root, 'tool.js'), 'utf8'), '// eslint-disable-next-line\nconst b = 1;\nconst c = 2; // explains c\n');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('CLI --check rejects new YAML/TOML comments and strip mode removes them', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'strip-comments-config-'));
  const script = path.join(__dirname, 'strip-comments.mjs');
  const git = args => execFileSync('git', ['-C', root, ...args], { stdio: 'pipe' });
  try {
    git(['init', '-q']);
    for (const [file, source] of [['workflow.yml', 'run: |\n  # shell text\n'], ['workflow.yaml', 'value: a#b\n'], ['config.toml', 'value = "# literal"\n']]) fs.writeFileSync(path.join(root, file), source);
    git(['add', '.']);
    assert.equal(spawnSync(process.execPath, [script, '--check', root]).status, 0);
    for (const file of ['workflow.yml', 'workflow.yaml', 'config.toml']) fs.appendFileSync(path.join(root, file), '# new comment\n');
    const failed = spawnSync(process.execPath, [script, '--check', root], { encoding: 'utf8' });
    assert.equal(failed.status, 1);
    for (const [file, line] of [['workflow.yml', 3], ['workflow.yaml', 2], ['config.toml', 2]]) {
      assert.ok(failed.stderr.includes(`${file}:${line}: # new comment`));
      assert.ok(fs.readFileSync(path.join(root, file), 'utf8').endsWith('# new comment\n'));
    }
    execFileSync(process.execPath, [script, root]);
    assert.equal(spawnSync(process.execPath, [script, '--check', root]).status, 0);
    assert.equal(fs.readFileSync(path.join(root, 'workflow.yml'), 'utf8'), 'run: |\n  # shell text\n');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('only the byte-identical bundled desktop reader is exempt', async () => {
  const { languageFor, tokenize } = await api;
  const reader = 'app/src/main/assets/chat_history_reader.py';
  assert.equal(languageFor(reader, '# upstream comment\n'), null);
  for (const file of ['app/src/main/assets/other.py', 'app/src/main/assets/nested/chat_history_reader.py', 'src/chat_history_reader.py']) {
    assert.equal(languageFor(file, '# comment\n'), 'python');
    assert.equal(tokenize('# comment\n', languageFor(file)).comments.filter(c => !c.reason).length, 1);
  }
});
