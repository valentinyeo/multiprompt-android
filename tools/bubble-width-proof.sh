#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$PWD/build/bubble-width"
mkdir -p "$out"
package=dev.multiprompt.companion.debug
case "${1:?build or test}" in
  build)
    gradle --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process assembleDebug assembleDebugAndroidTest
    ;;
  test)
    adb shell wm size 1080x1920
    adb install -r -g app/build/outputs/apk/debug/app-debug.apk
    adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    adb shell am instrument -w -r -e class dev.multiprompt.companion.ui.ChatBubbleWidthTest -e additionalTestOutputDir "/sdcard/Android/media/$package/width-proof" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
    adb logcat -d > "$out/logcat.txt"
    adb pull "/sdcard/Android/media/$package/width-proof/bubble-width" "$out/"
    grep -F 'OK (1 test)' "$out/instrumentation.txt"
    echo CHAT_BUBBLE_WIDTH_OK
    ;;
  *) exit 2 ;;
esac
