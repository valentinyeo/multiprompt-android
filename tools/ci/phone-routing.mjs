import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const phoneProofPaths = {
  'image-preview-emulator': [
    'app/src/androidTest/java/dev/multiprompt/companion/ui/ChatImagePreviewTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/DayThemeTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/TopFadeFixture.kt',
    'tools/check-image-preview.sh',
    'tools/check-day-theme.sh',
    'tools/check-day-theme-images.py',
  ],
  'reply-notifications-emulator': [
    'app/src/androidTest/java/dev/multiprompt/companion/notify/ReplyNotificationsTest.kt',
    'tools/check-reply-notifications.sh',
  ],
  'session-rows-emulator': [
    'app/src/androidTest/java/dev/multiprompt/companion/ui/SessionRowsTest.kt',
    'tools/check-session-rows.sh',
  ],
  'session-pins-emulator': [
    'app/src/androidTest/java/dev/multiprompt/companion/ui/SessionPinsTest.kt',
    'tools/check-session-pins.sh',
    'tools/session-pins/',
    'tools/historical_control.py',
  ],
  'message-actions-emulator': [
    'app/src/androidTest/java/dev/multiprompt/companion/reader/',
    'app/src/androidTest/java/dev/multiprompt/companion/ChatReaderBundleTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/MessageActionsTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/PendingMessagesTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/ReplyReferenceTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/SessionNoteChipTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/security/SecretStoreConcurrencyTest.kt',
    'tools/check-message-actions.sh',
    'tools/check-message-action-images.py',
  ],
  'approved-style-emulator': [
    'app/src/androidTest/java/dev/multiprompt/companion/ui/ApprovedStyleTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/KitMenuInteractionTest.kt',
    'app/src/androidTest/java/dev/multiprompt/companion/ui/TopFadeFixture.kt',
    'tools/check-approved-style.sh',
    'tools/check-day-theme-images.py',
  ],
};

const sharedPaths = [
  'app/src/main/',
  'app/src/debug/',
  'app/src/m3/',
  'app/src/release/',
  'app/src/androidTest/AndroidManifest.xml',
  'app/proguard-rules.pro',
  'gradle/',
  'gradle.properties',
  'gradlew',
  'gradlew.bat',
  '.github/actions/android-proof/',
  '.github/workflows/test.yml',
  'tools/ci/phone-routing.mjs',
  'tools/ci/phone-routing.test.mjs',
];

export function phoneProofJobs(paths, event = 'pull_request') {
  const matches = pattern => paths.some(path => pattern.endsWith('/') ? path.startsWith(pattern) : path === pattern);
  const all = event !== 'pull_request' || paths.length === 0 || sharedPaths.some(matches) || paths.some(path => path.endsWith('.gradle.kts'));
  return Object.fromEntries(Object.entries(phoneProofPaths).map(([job, patterns]) => [job, all || patterns.some(matches)]));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { BASE_SHA, HEAD_SHA, EVENT_NAME = 'pull_request' } = process.env;
  let paths = [];
  if (EVENT_NAME === 'pull_request') {
    if (!BASE_SHA || !HEAD_SHA) throw new Error('BASE_SHA and HEAD_SHA are required');
    paths = execFileSync('git', ['diff', '--name-only', '-z', '--no-renames', `${BASE_SHA}...${HEAD_SHA}`, '--'], { encoding: 'utf8' }).split('\0').filter(Boolean);
  }
  const jobs = phoneProofJobs(paths, EVENT_NAME);
  appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(jobs).map(([job, run]) => `${job}=${run}\n`).join(''));
  console.log(`Phone proof (${paths.length} changed files): ${JSON.stringify(jobs)}`);
}
