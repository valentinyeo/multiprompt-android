#!/usr/bin/env node
import { execFileSync } from 'node:child_process';

export const allowed = new Set(['.claude', '.gitattributes', '.github', '.gitignore', '.gitleaks.toml', 'CLAUDE.md', 'GATES.md', 'README.md', 'app', 'build.gradle.kts', 'docs', 'gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'keystore.properties.example', 'scripts', 'settings.gradle.kts', 'skillsrepo', 'third_party', 'tools', 'worker']);

export function unexpected(paths) {
  return [...new Set(paths.map(p => p.split('/')[0]))].filter(name => name && !allowed.has(name)).sort();
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const rev = process.argv[2];
  const paths = rev
    ? execFileSync('git', ['ls-tree', '--name-only', rev], { encoding: 'utf8' }).split('\n')
    : execFileSync('git', ['ls-files'], { encoding: 'utf8' }).split('\n');
  const extra = unexpected(paths);
  if (extra.length) {
    console.error(`Unexpected top-level files: ${extra.join(', ')}`);
    console.error('Scratch output (logs, OCR dumps, screenshots, out*.txt) belongs in your session scratchpad or /tmp, not the repo.');
    console.error('A new real top-level file must be added to `allowed` in tools/root-files.mjs.');
    process.exit(1);
  }
}
