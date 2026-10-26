#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
status=0
"${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.notify.ReplyNotificationsTest || status=$?
adb logcat -d > build/reply-notifications-logcat.txt
adb exec-out screencap -p > build/reply-notifications-screen.png
if [ "$status" -eq 0 ]; then
  image=$(find app/build/outputs -type f -name reply-notifications-shade.png -print -quit)
  test -n "$image"
  cp "$image" build/reply-notifications-shade.png
  favorite=$(find app/build/outputs -type f -name favorite-only-notifications-shade.png -print -quit)
  test -n "$favorite"
  cp "$favorite" build/favorite-only-notifications-shade.png
fi
exit "$status"
