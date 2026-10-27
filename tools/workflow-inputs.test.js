const test = require('node:test');
const assert = require('node:assert/strict');
const { readdirSync, readFileSync } = require('node:fs');
const path = require('node:path');

const directory = process.env.WORKFLOW_DIR || path.join(__dirname, '../.github/workflows');

test('workflow run scripts use environment values for event and dispatch data', () => {
  const files = readdirSync(directory).filter(file => file.endsWith('.yml'));
  assert.ok(files.length > 0, 'No workflow files');
  for (const file of files) {
    const lines = readFileSync(path.join(directory, file), 'utf8').split('\n');
    let runIndent = null;
    for (const [index, line] of lines.entries()) {
      if (runIndent !== null && line.trim() && line.search(/\S/) <= runIndent) runIndent = null;
      const run = line.match(/^(\s*)(-\s+)?run:\s*(.*)$/);
      if (run) runIndent = run[1].length + (run[2]?.length || 0);
      if (runIndent !== null) {
        assert.doesNotMatch(run ? run[3] : line, /\$\{\{\s*(?:inputs\.|github\.event\.)/, `${file}:${index + 1}`);
      }
    }
  }
});

test('emulator action receives complete shell commands per script line', () => {
  const { spawnSync } = require('node:child_process');
  const text = readFileSync(path.join(directory, 'verify.yml'), 'utf8');
  const script = text.match(/^          script: \|\n((?:            .*\n)+)/m);
  assert.ok(script, 'Missing emulator script');
  for (const line of script[1].trim().split('\n')) {
    const result = spawnSync('sh', ['-n', '-c', line.trim()], { encoding: 'utf8' });
    assert.equal(result.status, 0, `${line}\n${result.stderr}`);
  }
});
