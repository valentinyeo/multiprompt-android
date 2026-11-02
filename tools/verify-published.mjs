import { createHash } from 'node:crypto';
import { writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHANNEL = process.argv[2] ?? 'android-m3-latest';

export function checkManifest(manifest) {
  const problems = [];
  const file = channelFile(CHANNEL);
  if (typeof manifest?.versionName !== 'string' || !/^\d+\.\d+\.\d+(-m3)?$/.test(manifest.versionName)) problems.push('versionName missing or malformed');
  if (!Number.isSafeInteger(manifest?.versionCode) || manifest.versionCode <= 0) problems.push('versionCode missing or not positive');
  if (typeof manifest?.commit !== 'string' || !/^[a-f0-9]{40}$/.test(manifest.commit)) problems.push('commit missing or not a full sha');
  if (!Number.isSafeInteger(manifest?.sizeBytes) || manifest.sizeBytes <= 0) problems.push('sizeBytes missing');
  if (typeof manifest?.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(manifest.sha256)) problems.push('sha256 missing');
  if (typeof manifest?.apkUrl !== 'string' || !manifest.apkUrl.endsWith(file)) problems.push(`apkUrl does not end with ${file}`);
  return problems;
}

export function channelFile(channel) {
  return channel === 'android-m3-latest' ? 'multiprompt-m3.apk' : 'multiprompt-companion.apk';
}

export function manifestUrl(channel) {
  return channel === 'android-m3-latest'
    ? 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3-update.json'
    : 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json';
}

async function main() {
  const response = await fetch(manifestUrl(CHANNEL), { redirect: 'follow' });
  if (!response.ok) throw new Error(`Manifest request failed: HTTP ${response.status}`);
  const manifest = await response.json();
  const problems = checkManifest(manifest);
  if (problems.length) {
    console.error(`manifest ${CHANNEL} is not publishable: ${problems.join(', ')}`);
    process.exitCode = 1;
    return;
  }
  const apk = await fetch(manifest.apkUrl, { redirect: 'follow' });
  if (!apk.ok) throw new Error(`APK request failed: HTTP ${apk.status}`);
  const bytes = Buffer.from(await apk.arrayBuffer());
  if (bytes.length !== manifest.sizeBytes) problems.push(`size ${bytes.length} differs from manifest ${manifest.sizeBytes}`);
  const digest = createHash('sha256').update(bytes).digest('hex');
  if (digest !== manifest.sha256) problems.push(`sha256 ${digest} differs from manifest ${manifest.sha256}`);
  const folder = mkdtempSync(join(tmpdir(), 'multiprompt-published-'));
  try {
    const file = join(folder, channelFile(CHANNEL));
    writeFileSync(file, bytes);
    const manifestBytes = execFileSync('unzip', ['-p', file, 'AndroidManifest.xml'], { maxBuffer: 32 * 1024 * 1024 });
    const find = (value) => manifestBytes.indexOf(Buffer.from(value, 'latin1')) >= 0 || manifestBytes.indexOf(Buffer.from(value, 'utf16le')) >= 0;
    const packageId = CHANNEL === 'android-m3-latest' ? 'dev.multiprompt.companion.m3' : 'dev.multiprompt.companion';
    if (!find(packageId)) problems.push(`package ${packageId} not found inside the APK`);
    if (!find(manifest.versionName)) problems.push(`version ${manifest.versionName} not found inside the APK`);
    const resources = execFileSync('unzip', ['-p', file, 'resources.arsc'], { maxBuffer: 64 * 1024 * 1024 });
    if (resources.indexOf(Buffer.from('Multi Bot', 'latin1')) < 0) problems.push('app label Multi Bot not found inside the APK');
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
  if (problems.length) {
    console.error(`${CHANNEL} ${manifest.versionName} is not trustworthy: ${problems.join('; ')}`);
    process.exitCode = 1;
    return;
  }
  console.log(`${CHANNEL} ${manifest.versionName} (${manifest.versionCode}) verified: ${bytes.length} bytes, sha256 matches, package and version present, commit ${manifest.commit}`);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();
