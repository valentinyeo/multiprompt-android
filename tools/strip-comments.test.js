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
  for (const file of ['vendor/x.js', 'third_party/x.kt', 'node_modules/x.js', 'skillsrepo/x.py', 'docs/x.html', 'docs-site/x.ts', 'openwiki/x.sh', 'CLAUDE.md', 'AGENTS.md', 'README', 'data.json', 'config.toml', 'gradle/wrapper/gradle-wrapper.properties', 'tools/fixtures/x.html']) assert.equal(languageFor(file), null, file);
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
