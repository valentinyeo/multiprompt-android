#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
run_proof() {
  local label=$1 status=0 result
  rm -rf app/build/outputs/connected_android_test_additional_output
  "${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.TopFadeInstrumentedTest || status=$?
  result=$(find app/build/outputs -type d -name top-fade-proof -print -quit)
  test -n "$result" || return 1
  mkdir -p "build/top-fade-shots/$label"
  for name in inbox-top inbox-scrolled chat-top chat-scrolled chat-headline chat-headline-long dark-app-inbox-scrolled light-app-inbox-scrolled dark-chat-reference light-chat-reference; do
    cp "$result/$name.png" "build/top-fade-shots/$label/" || return 1
    test -s "build/top-fade-shots/$label/$name.png" || return 1
  done
  cp app/build/outputs/androidTest-results/connected/debug/TEST-*.xml "build/top-fade-shots/$label/" || return 1
  return "$status"
}
if [ -n "${TOP_FADE_BASE_REF:-}" ]; then
  source_file=app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt
  mkdir -p build/top-fade-shots
  cp "$source_file" build/top-fade-shots/candidate.kt
  trap 'cp build/top-fade-shots/candidate.kt "$source_file"' EXIT
  git show "$TOP_FADE_BASE_REF:$source_file" > "$source_file"
  if "${GRADLE:-gradle}" --no-daemon testDebugUnitTest --tests '*InboxTopFadeTest' > build/top-fade-shots/before-unit.log 2>&1; then
    echo 'Original inbox unexpectedly passed unit regression' >&2
    exit 1
  fi
  python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('app/build/test-results/testDebugUnitTest/TEST-dev.multiprompt.companion.ui.InboxTopFadeTest.xml').getroot()
assert root.attrib['tests'] == '2' and root.attrib['failures'] == '2'
assert all('Inbox fade must extend as far below its controls as chat' in case.find('failure').attrib['message'] for case in root.findall('testcase'))
print('ORIGINAL_INBOX_UNIT_REJECTED')
PY
  if run_proof before; then
    echo 'Original inbox unexpectedly passed' >&2
    exit 1
  fi
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
cases = [case for file in Path('build/top-fade-shots/before').glob('TEST-*.xml') for case in ET.parse(file).iter('testcase')]
failed = [case for case in cases if case.find('failure') is not None]
assert len(cases) == 7
assert {case.attrib['name'] for case in failed} == {'fullAppInboxMatchesChatFadeInLightTheme', 'fullAppInboxMatchesChatFadeInDarkTheme'}
assert all('Full inbox must reach the status bar' in case.find('failure').text for case in failed)
print('ORIGINAL_INBOX_REJECTED')
PY
  cp build/top-fade-shots/candidate.kt "$source_file"
  trap - EXIT
  rm build/top-fade-shots/candidate.kt
fi
run_proof after
if [ "${RELEASE_SMOKE:-false}" = true ]; then
  curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o build/top-fade-shots/update.json
  manifest=build/top-fade-shots/update.json
  commit=$(jq -er '.commit' "$manifest")
  git fetch origin "$commit"
  git merge-base --is-ancestor HEAD "$commit"
  curl -fsSL "$(jq -er '.apkUrl' "$manifest")" -o build/published.apk
  printf '%s  build/published.apk\n' "$(jq -er '.sha256' "$manifest")" | sha256sum --check
  test "$(stat -c%s build/published.apk)" = "$(jq -er '.sizeBytes' "$manifest")"
  adb install -r -g build/published.apk
  adb shell am start -W -n dev.multiprompt.companion/dev.multiprompt.companion.MainActivity
  adb shell uiautomator dump /sdcard/release-window.xml
  adb shell dumpsys package dev.multiprompt.companion > build/top-fade-shots/installed-package.txt
  grep -F "versionName=$(jq -er '.versionName' "$manifest")" build/top-fade-shots/installed-package.txt
  adb exec-out screencap -p > build/top-fade-shots/release-installed.png
  echo PUBLISHED_APK_INSTALLED
fi
