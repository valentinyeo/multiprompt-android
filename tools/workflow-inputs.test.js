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

test('native-skills stability runs retain ten independent proofs without retries', () => {
  const source = readFileSync(path.join(directory, 'verify.yml'), 'utf8');
  assert.match(source, /native_skills_runs:\n\s+description:[^\n]+\n\s+type: choice\n\s+default: '1'\n\s+options:\n\s+- '1'\n\s+- '10'/);
  assert.match(source, /fail-fast: false\n\s+matrix:\n\s+sample: \$\{\{ fromJSON\(inputs\.native_skills_runs == '10' && '\[1,2,3,4,5,6,7,8,9,10\]' \|\| '\[1\]'\) \}\}/);
  for (const prefix of ['          stage: stage-1', '          name: verify-${{ env.VERIFY_FEATURE }}']) {
    const line = source.split('\n').find(line => line.startsWith(prefix));
    assert.ok(line?.includes("inputs.native_skills_runs == '10' && format('-sample-{0}', matrix.sample) || ''"));
  }
});

test('Android SDK setup never requests the removed default tools package', () => {
  const files = readdirSync(directory).filter(file => file.endsWith('.yml'));
  for (const file of files) {
    const source = readFileSync(path.join(directory, file), 'utf8');
    for (const match of source.matchAll(/^(\s*)- uses: android-actions\/setup-android[^\n]*\n([\s\S]*?)(?=^\1- |$(?![\s\S]))/gm)) {
      assert.match(match[2], /^\s+packages:\s*platform-tools\s*$/m, `${file}: choose supported SDK packages explicitly`);
    }
  }
});
