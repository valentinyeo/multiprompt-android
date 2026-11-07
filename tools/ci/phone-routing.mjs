import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function needsPhoneProof(paths) {
  return paths.length === 0 || paths.some(path => !(
    path.startsWith('docs/') ||
    path.startsWith('tools/release-notes/') ||
    (!path.includes('/') && path.endsWith('.md'))
  ));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { BASE_SHA, HEAD_SHA } = process.env;
  if (!BASE_SHA || !HEAD_SHA) throw new Error('BASE_SHA and HEAD_SHA are required');
  const paths = execFileSync('git', ['diff', '--name-only', '-z', '--no-renames', `${BASE_SHA}...${HEAD_SHA}`, '--'], { encoding: 'utf8' }).split('\0').filter(Boolean);
  const run = needsPhoneProof(paths);
  appendFileSync(process.env.GITHUB_OUTPUT, `run=${run}\n`);
  console.log(`Phone proof: ${run ? 'required' : 'not required'} (${paths.length} changed files)`);
}
