#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
"${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.TopFadeInstrumentedTest || status=$?
mkdir -p build/top-fade-shots
for name in inbox-top inbox-scrolled chat-top chat-scrolled; do
  adb pull "/data/local/tmp/top-fade-proof/$name.png" build/top-fade-shots/
  test -f "build/top-fade-shots/$name.png"
  test -s "build/top-fade-shots/$name.png"
done
if [ "$status" -ne 0 ]; then exit "$status"; fi
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
