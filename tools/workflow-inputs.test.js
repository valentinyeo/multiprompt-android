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
