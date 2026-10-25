#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
status=0
"${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.ChatImagePreviewTest || status=$?
adb logcat -d > build/image-preview-logcat.txt
adb exec-out screencap -p > build/image-preview-screen.png
if [ "$status" -eq 0 ]; then
  adb pull /sdcard/Android/data/dev.multiprompt.companion.debug/files/image-unfurl.png build/image-unfurl.png
fi
exit "$status"
