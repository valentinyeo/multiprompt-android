'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { readSideFiles, parentResult } = require('./verifier.cjs');
const { registry, prJourneys } = require('./coverage.cjs');

const gh = args => execFileSync('gh', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });

function cacheKey(sha, feature, root = process.cwd()) {
  if (!/^[a-f0-9]{40}$/.test(sha) || !prJourneys.includes(feature)) throw new Error('Invalid base cache identity');
  const files = execFileSync('git', ['ls-files', '-z', '.claude/skills/verify-multiprompt-android/scripts', '.github/actions/android-proof', '.github/workflows/verify.yml', '.github/scripts/pstack-verifier', 'tools'], { cwd: root, encoding: 'utf8' })
    .split('\0').filter(file => file && !/(?:\.test\.|\/release-notes\/)/.test(file)).sort();
  const hash = createHash('sha256').update(JSON.stringify(registry.journeys[feature].runner)).update('\0');
  for (const file of files) hash.update(file).update('\0').update(fs.readFileSync(path.join(root, file))).update('\0');
  return `verify-base-${sha}-${feature}-${hash.digest('hex')}`;
}

const VIDEO_FILE = 'journey-video.mp4';

function hasVideo(directory) {
  try {
    return fs.statSync(path.join(directory, VIDEO_FILE)).size > 0;
  } catch {
    return false;
  }
}

function reusable(directory, sha, { requireVideo = false } = {}) {
  const files = readSideFiles(directory);
  if (files.sourceSha !== sha) return false;
  if (requireVideo && !hasVideo(directory)) return false;
  const result = parentResult({ conclusion: 'success', result: files.result, sourceSha: files.sourceSha, mergeBase: sha });
  return result === 'passed' || result === 'product assertion';
}

function restore({ repo, sha, feature, directory, runId, run = gh, key = cacheKey(sha, feature), requireVideo = false }) {
  let pages;
  try {
    pages = JSON.parse(run(['api', '--paginate', '--slurp', `repos/${repo}/actions/artifacts?name=${encodeURIComponent(key)}&per_page=100`]));
  } catch (error) {
    console.error(`Base cache lookup unavailable; driving the base: ${error.message}`);
    return false;
  }
  const candidates = pages.flatMap(page => page.artifacts ?? []).filter(artifact => !artifact.expired
    && artifact.workflow_run?.id !== Number(runId)
    && Number.isInteger(artifact.workflow_run?.repository_id)
    && artifact.workflow_run.repository_id === artifact.workflow_run.head_repository_id);
  for (const artifact of candidates) {
    try {
      const workflow = JSON.parse(run(['api', `repos/${repo}/actions/runs/${artifact.workflow_run.id}`]));
      if (workflow.status !== 'completed' || !['.github/workflows/pstack-verifier.yml', '.github/workflows/verify.yml'].includes(workflow.path)) continue;
      run(['run', 'download', String(artifact.workflow_run.id), '-R', repo, '-n', key, '-D', directory]);
      if (reusable(directory, sha, { requireVideo })) {
        fs.writeFileSync(path.join(directory, 'base-cache-origin.json'), JSON.stringify({ run: workflow.html_url, artifact: artifact.id, key }));
        return true;
      }
    } catch (error) {
      console.error(`Base cache unavailable: ${error.message}`);
    }
    fs.rmSync(directory, { recursive: true, force: true });
  }
  return false;
}

module.exports = { VIDEO_FILE, cacheKey, hasVideo, reusable, restore };

if (require.main === module) {
  try {
    const { APP_REF: sha, VERIFY_JOURNEY: journey, VERIFY_FEATURE: driver, EVIDENCE: directory, GITHUB_REPOSITORY: repo, GITHUB_RUN_ID: runId, RECORD_VIDEO: recordVideo } = process.env;
    const feature = journey || driver;
    const key = cacheKey(sha, feature);
    const command = process.argv[2];
    const requireVideo = recordVideo === 'true';
    if (!['restore', 'save'].includes(command)) throw new Error('Usage: parent-cache.cjs restore|save');
    const hit = command === 'restore' && restore({ repo, sha, feature, directory, runId, key, requireVideo });
    const ready = command === 'save' && reusable(directory, sha, { requireVideo });
    fs.appendFileSync(process.env.GITHUB_OUTPUT, `key=${key}\nhit=${hit}\nready=${ready}\n`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
