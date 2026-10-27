#!/usr/bin/env bash
set -euo pipefail
curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o "$EVIDENCE/published-update.json"
curl -fsSL "$(jq -er '.apkUrl' "$EVIDENCE/published-update.json")" -o "$RUNNER_TEMP/published.apk"
printf '%s  %s\n' "$(jq -er '.sha256' "$EVIDENCE/published-update.json")" "$RUNNER_TEMP/published.apk" | sha256sum --check
test "$(stat -c%s "$RUNNER_TEMP/published.apk")" = "$(jq -er '.sizeBytes' "$EVIDENCE/published-update.json")"
adb install -r -g "$RUNNER_TEMP/published.apk"
adb shell am start -W -n dev.multiprompt.companion/dev.multiprompt.companion.MainActivity
adb shell dumpsys package dev.multiprompt.companion > "$EVIDENCE/published-package.txt"
grep -F "versionName=$(jq -er '.versionName' "$EVIDENCE/published-update.json")" "$EVIDENCE/published-package.txt"
adb exec-out screencap -p > "$EVIDENCE/published-installed.png"
test -s "$EVIDENCE/published-installed.png"
rm "$RUNNER_TEMP/published.apk"
