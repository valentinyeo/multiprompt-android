import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const settingsPath = new URL('../../settings.gradle.kts', import.meta.url);
const settings = readFileSync(settingsPath, 'utf8');

const mirror =
  'https://maven-central.storage-download.googleapis.com/maven2/';

const block = label => {
  const pattern = new RegExp(
    `${label}\\s*\\{[\\s\\S]*?repositories\\s*\\{([\\s\\S]*?)\\n\\s*\\}`,
    'm'
  );
  const match = settings.match(pattern);
  assert.ok(match, `missing ${label}.repositories in settings.gradle.kts`);
  return match[1];
};

const repositoryLines = body =>
  body
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0 && !line.startsWith('//'));

test('settings.gradle.kts lists the Maven Central mirror after mavenCentral()', () => {
  for (const label of ['pluginManagement', 'dependencyResolutionManagement']) {
    const lines = repositoryLines(block(label));
    const central = lines.indexOf('mavenCentral()');
    const mirrorLine = `maven("${mirror}")`;
    const mirrorIndex = lines.indexOf(mirrorLine);
    assert.ok(central >= 0, `${label} must call mavenCentral()`);
    assert.ok(mirrorIndex >= 0, `${label} must add the Google Maven Central mirror`);
    assert.ok(
      mirrorIndex === central + 1,
      `${label} must list the mirror immediately after mavenCentral()`
    );
  }
});

test('every explicit repository URL in settings.gradle.kts uses HTTPS', () => {
  const urls = [...settings.matchAll(/maven\("([^"]+)"\)/g)].map(match => match[1]);
  assert.ok(urls.length > 0, 'settings.gradle.kts must declare at least one maven URL');
  for (const url of urls) {
    assert.match(url, /^https:\/\//, `repository URL must use HTTPS: ${url}`);
  }
});