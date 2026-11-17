import { execFileSync } from 'node:child_process';
import { requiredChecks } from './pr-checks.mjs';

try {
  if (process.argv.length !== 3 || process.argv[2] !== '--check') {
    throw new Error('Usage: node tools/branch-protection.mjs --check');
  }
  const protection = JSON.parse(execFileSync('gh', [
    'api', 'repos/valentinyeo/multiprompt-android/branches/main/protection',
  ], { encoding: 'utf8', timeout: 30_000, maxBuffer: 2 * 1024 * 1024 }));
  const contexts = protection.required_status_checks?.contexts;
  if (!Array.isArray(contexts) || contexts.some(context => typeof context !== 'string')) {
    throw new Error('GitHub returned no valid required check contexts');
  }
  const missing = requiredChecks.filter(name => !contexts.includes(name));
  const unexpected = contexts.filter(name => !requiredChecks.includes(name));
  if (missing.length || unexpected.length) {
    throw new Error(`Branch protection differs from tools/pr-checks.mjs: missing [${missing.join(', ')}], unexpected [${unexpected.join(', ')}]`);
  }
  console.log(`${requiredChecks.length} required checks match tools/pr-checks.mjs`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
