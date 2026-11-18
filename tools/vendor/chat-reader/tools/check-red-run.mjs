import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { autoRevertBranch, classifyTrees } from './version-stamp.mjs';
import { nativeTestParts as splitNativeTests } from './native-test-parts.mjs';

const [baseRef, headRef] = process.argv.slice(2);
if (!baseRef || !headRef || process.argv.length !== 4) {
  console.error('usage: node tools/check-red-run.mjs <base> <head>');
  process.exit(2);
}

const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).trim();
const root = git('rev-parse', '--show-toplevel');
const base = git('merge-base', baseRef, headRef);
const head = git('rev-parse', '--verify', `${headRef}^{commit}`);
const changed = git('diff', '--name-only', '-z', base, head).split('\0').filter(Boolean);
const present = git('diff', '--name-only', '-z', '--diff-filter=ACMRT', base, head).split('\0').filter(Boolean);
const testFile = file => /(?:\.test\.[cm]?js|(?:\.test|-test)\.py)$/.test(file);
const tests = present.filter(testFile);
const docs = /^(?:docs\/|openwiki\/|docs-site\/|changelog\.d\/)|[^/]+\.md$/;
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'check-red-run-'));
const green = path.join(scratch, 'head');
const red = path.join(scratch, 'base');
const worktrees = [];

function snapshot(dir) {
  const result = {};
  const files = ['src/main.zig', 'src/changelog.zig'];
  const fragments = path.join(dir, 'changelog.d');
  if (fs.existsSync(fragments)) files.push(...fs.readdirSync(fragments).map(file => `changelog.d/${file}`));
  for (const file of files) if (fs.existsSync(path.join(dir, file))) result[file] = fs.readFileSync(path.join(dir, file), 'utf8');
  return result;
}

function nativeTestParts(source) {
  const parsed = spawnSync(process.env.ZIG || 'zig', ['fmt', '--stdin'], {
    input: source, encoding: 'utf8', timeout: 300000, maxBuffer: 32 * 1024 * 1024,
  });
  if (parsed.error || parsed.status !== 0) throw new Error('Invalid Zig source');
  return splitNativeTests(source);
}

function nativeZigTestsOnly() {
  const zig = /^src\/[^/]+\.zig$/;
  if (!changed.length) return false;
  if (!changed.every(name => docs.test(name) || (zig.test(name) && present.includes(name)))) return false;
  const files = changed.filter(name => zig.test(name));
  for (const file of files) {
    if (!fs.existsSync(path.join(red, file)) || !fs.existsSync(path.join(green, file))) return false;
    try {
      const before = nativeTestParts(fs.readFileSync(path.join(red, file), 'utf8'));
      const after = nativeTestParts(fs.readFileSync(path.join(green, file), 'utf8'));
      if (!(before.names.length > 0 && JSON.stringify(before.names) === JSON.stringify(after.names) && before.outside === after.outside)) return false;
    } catch {
      return false;
    }
  }
  return true;
}

function prepareService(dir) {
  if (!tests.some(file => file.startsWith('service/test/'))) return;
  for (const args of [['ci', '--no-audit', '--no-fund', '--prefer-offline'], ['run', 'pretest']]) {
    const result = spawnSync('npm', args, {
      cwd: path.join(dir, 'service'), encoding: 'utf8', timeout: 300000, maxBuffer: 16 * 1024 * 1024,
    });
    if (result.error || result.status !== 0) throw new Error(`Cannot prepare service snapshot: npm ${args.join(' ')}\n${result.stdout || ''}${result.stderr || ''}`);
  }
}

function run(file, dir) {
  const python = file.endsWith('.py');
  const temp = fs.mkdtempSync(path.join(scratch, 'test-'));
  const env = { ...process.env, TMPDIR: temp, TMP: temp, TEMP: temp };
  delete env.NODE_TEST_CONTEXT;
  const result = spawnSync(python ? 'python3' : process.execPath, python ? [file] : ['--test', '--test-reporter=tap', file], {
    cwd: dir, env, encoding: 'utf8', timeout: 300000, maxBuffer: 16 * 1024 * 1024,
  });
  return { ...result, output: (result.stdout || '') + (result.stderr || '') };
}

function assertionFailure(file, output) {
  return file.endsWith('.py')
    ? /^FAIL: /m.test(output) && /AssertionError/.test(output) && !/^ERROR: /m.test(output)
    : /^# fail [1-9]\d*$/m.test(output) && /code: ['"]ERR_ASSERTION['"]/.test(output) && !/(?:code: ['"](?:MODULE_NOT_FOUND|ERR_MODULE_NOT_FOUND|ENOENT)['"]|name: ['"](?:Syntax|Reference|Type)Error['"])/.test(output);
}

try {
  console.log(`merge-base=${base} head=${head}`);
  if (changed.length && changed.every(file => docs.test(file))) {
    console.log('RED_RUN_NOT_REQUIRED documentation only');
  } else if (autoRevertBranch()) {
    console.log('RED_RUN_NOT_REQUIRED auto-revert branch undoes a master change');
  } else {
    for (const [dir, ref] of [[green, head], [red, base]]) {
      git('worktree', 'add', '--quiet', '--detach', dir, ref);
      worktrees.push(dir);
    }
    const baseDir = path.join(scratch, 'plain');
    const ensureBase = () => {
      if (fs.existsSync(baseDir)) return;
      git('worktree', 'add', '--quiet', '--detach', baseDir, base);
      worktrees.push(baseDir);
      if (tests.some(file => file.startsWith('service/test/'))) prepareService(baseDir);
    };
    const stampPaths = /^(?:src\/(?:main|changelog)\.zig|changelog\.d\/[^/]+)$/;
    const workflow = /^\.github\/workflows\/[^/]+\.ya?ml$/;
    if (changed.length && changed.every(file => stampPaths.test(file)) && classifyTrees(snapshot(red), snapshot(green), changed) === 'STAMP') {
      console.log('RED_RUN_NOT_REQUIRED verified mechanical release stamp');
    } else if (nativeZigTestsOnly()) {
      console.log('RED_RUN_NOT_REQUIRED native Zig test edits only; Windows tests still required');
    } else {
      if (!tests.length) throw new Error('No changed regression tests. Add a portable .test.js, .test.mjs, .test.cjs or Python regression that fails on merge-base source.');
      const inputs = present.filter(file => /^(?:tools\/fixtures\/|tools\/[^/]*fixture[^/]*$|tests\/fixtures\/|service\/test\/fixtures\/)/.test(file));
      execFileSync('git', ['-C', red, 'restore', `--source=${head}`, '--worktree', '--', ...tests, ...inputs], { stdio: 'pipe' });
      const testOnly = changed.every(file => docs.test(file) || workflow.test(file) || (file.startsWith('tools/') && testFile(file)) || (present.includes(file) && inputs.includes(file)));
      prepareService(green);
      if (!testOnly) prepareService(red);
      for (const file of tests) {
        const fixed = run(file, green);
        console.log(`HEAD ${file}\n${fixed.output}`);
        if (fixed.error || fixed.status !== 0 || (!file.endsWith('.py') && !/^# tests [1-9]\d*$/m.test(fixed.output))) {
          throw new Error(`${file} does not pass on PR source; cannot prove red.`);
        }
        if (testOnly) continue;
        const before = run(file, red);
        console.log(`MERGE_BASE ${file}\n${before.output}`);
        if (before.status === 0) {
          ensureBase();
          const repaired = run(file, baseDir);
          console.log(`MERGE_BASE_SUITE ${file}\n${repaired.output}`);
          if (repaired.error || repaired.status === 0 || !assertionFailure(file, repaired.output)) {
            throw new Error(`${file} stayed green on merge-base source; the test does not prove this fix.`);
          }
          console.log(`REPAIR PROVEN ${file} exit=${repaired.status} merge-base=${base}`);
          continue;
        }
        if (before.error || before.status !== 1 || !assertionFailure(file, before.output)) throw new Error(`${file} needs an assertion failure on merge-base source, not an infrastructure or loading error.`);
        console.log(`RED PROVEN ${file} exit=${before.status} merge-base=${base}`);
      }
      console.log(`${testOnly ? 'TEST_ONLY_PROVEN' : 'RED_RUN_PROVEN'} ${tests.length} changed suites`);
    }
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  for (const dir of worktrees.reverse()) execFileSync('git', ['-C', root, 'worktree', 'remove', '--force', dir], { stdio: 'pipe' });
  fs.rmSync(scratch, { recursive: true, force: true });
}
