#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
status=0
"${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.ChatImagePreviewTest || status=$?
adb logcat -d > build/image-preview-logcat.txt
adb exec-out screencap -p > build/image-preview-screen.png
if [ "$status" -eq 0 ]; then
  image=$(find app/build/outputs -type f -name image-unfurl.png -print -quit)
  test -n "$image"
  cp "$image" build/image-unfurl.png
fi
exit "$status"
