#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
"${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.TopFadeInstrumentedTest,dev.multiprompt.companion.ui.SettingsNavigationTest || status=$?
mkdir -p build/top-fade-shots
for name in inbox-top inbox-scrolled chat-top chat-scrolled chat-headline chat-headline-long; do
  adb pull "/data/local/tmp/top-fade-proof/$name.png" build/top-fade-shots/
  test -f "build/top-fade-shots/$name.png"
  test -s "build/top-fade-shots/$name.png"
done
mkdir -p build/top-fade-shots/settings
adb pull /data/local/tmp/settings-proof/. build/top-fade-shots/settings/
for theme in dark light; do
  for page in home settings mapping renew-browser signed-out sign-in-browser; do
    test -s "build/top-fade-shots/settings/$theme-$page.png"
  done
done
if [ "$status" -ne 0 ]; then exit "$status"; fi
echo SETTINGS_NAVIGATION_EMULATOR_OK
if [ "${RELEASE_SMOKE:-false}" = true ]; then
  if [ "${GITHUB_EVENT_NAME:-}" = push ]; then
    release_run=$(gh run list --repo "$GITHUB_REPOSITORY" --workflow release.yml --commit "$GITHUB_SHA" --limit 1 --json databaseId --jq '.[0].databaseId')
    test -n "$release_run"
    timeout 1800s gh run watch "$release_run" --repo "$GITHUB_REPOSITORY" --interval 300 --exit-status
  fi
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
