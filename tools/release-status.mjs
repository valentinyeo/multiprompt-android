import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function releaseStatus(manifest, wanted) {
  const commit = manifest?.commit;
  const matches = typeof commit === 'string' && typeof wanted === 'string'
    && /^[a-f0-9]{7,40}$/i.test(wanted) && commit.startsWith(wanted);
  return {
    matches,
    message: matches
      ? `android-latest ${manifest.versionName} (${manifest.versionCode}) serves ${commit}`
      : `android-latest serves ${commit ?? 'missing'}, wanted ${wanted}`,
  };
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length && (args.length !== 2 || args[0] !== '--commit' || !args[1])) {
    throw new Error('Usage: node tools/release-status.mjs [--commit <sha>]');
  }
  const wanted = args[1] ?? execFileSync('git', ['rev-parse', 'origin/main'], { encoding: 'utf8' }).trim();
  const response = await fetch('https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json', { redirect: 'follow' });
  if (!response.ok) throw new Error(`Manifest request failed: HTTP ${response.status}`);
  const status = releaseStatus(await response.json(), wanted);
  if (status.matches) console.log(status.message);
  else {
    console.error(status.message);
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(`Release status failed: ${error.message}`);
    process.exitCode = 1;
  });
}
