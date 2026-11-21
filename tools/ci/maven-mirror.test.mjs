import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const settingsPath = new URL('../../settings.gradle.kts', import.meta.url);
const settings = readFileSync(settingsPath, 'utf8');

const mirror =
  'https://maven-central.storage-download.googleapis.com/maven2/';

const block = label => {
  const pattern = new RegExp(
    `${label}\\s*\\{[\\s\\S]*?repositories\\s*\\{([\\s\\S]*?)\\n    \\}`,
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

test('Google Maven serves only Google groups so one Google timeout cannot fail other libraries', () => {
  for (const label of ['pluginManagement', 'dependencyResolutionManagement']) {
    const body = block(label);
    assert.ok(!/\bgoogle\(\)/.test(body), `${label} must not use an unscoped google()`);
    const scoped = body.match(/google\s*\{\s*content\s*\{([\s\S]*?)\}\s*\}/);
    assert.ok(scoped, `${label} must scope google() with content { }`);
    const groups = [...scoped[1].matchAll(/includeGroupByRegex\("([^"]+)"\)/g)].map(m => new RegExp(`^${m[1].replaceAll('\\\\', '\\')}$`));
    const google = name => groups.some(g => g.test(name));
    for (const name of ['androidx.browser', 'com.android.tools.build', 'com.google.android.material']) {
      assert.ok(google(name), `${label} must send ${name} to Google Maven`);
    }
    for (const name of ['org.jetbrains.kotlin', 'org.connectbot', 'com.squareup.okhttp3', 'org.jetbrains.kotlin.plugin.compose']) {
      assert.ok(!google(name), `${label} must keep ${name} off Google Maven`);
    }
  }
});

test('every explicit repository URL in settings.gradle.kts uses HTTPS', () => {
  const urls = [...settings.matchAll(/maven\("([^"]+)"\)/g)].map(match => match[1]);
  assert.ok(urls.length > 0, 'settings.gradle.kts must declare at least one maven URL');
  for (const url of urls) {
    assert.match(url, /^https:\/\//, `repository URL must use HTTPS: ${url}`);
  }
});