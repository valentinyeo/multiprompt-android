#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$PWD/build/bubble-width"
mkdir -p "$out"
package=dev.multiprompt.companion.debug
if [ "${PUBLISHED:-false}" = true ]; then package=dev.multiprompt.companion; fi
case "${1:?build or test}" in
  build)
    source="$PWD"
    variant=Debug
    args=()
    if [ "${PUBLISHED:-false}" = true ]; then
      curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o "$out/manifest.json"
      commit=$(jq -er '.commit | select(test("^[a-f0-9]{40}$"))' "$out/manifest.json")
      git fetch origin "$commit"
      source="${RUNNER_TEMP:?}/bubble-width-source"
      mkdir -p "$source"
      git archive "$commit" | tar -x -C "$source"
      rm -rf "$source/app/src/androidTest"
      test_dir=app/src/androidTest/java/dev/multiprompt/companion/ui
      mkdir -p "$source/$test_dir"
      cp "$test_dir/ChatBubbleWidthTest.kt" "$source/$test_dir/"
      cp keystore.properties "$source/"
      cp -r signing "$source/"
      python3 - "$source/app/build.gradle.kts" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if 'testBuildType =' not in s:
    s = s.replace('    compileSdk = 36', '    compileSdk = 36\n    testBuildType = providers.gradleProperty("instrumentationBuildType").orNull ?: "debug"')
p.write_text(s)
PY
      variant=Release
      args=(-PinstrumentationBuildType=release "-PversionCode=$(jq -er '.versionCode' "$out/manifest.json")" "-PversionName=$(jq -er '.versionName' "$out/manifest.json")")
      curl -fsSL "$(jq -er '.apkUrl' "$out/manifest.json")" -o "$out/published.apk"
      printf '%s  %s\n' "$(jq -er '.sha256' "$out/manifest.json")" "$out/published.apk" | sha256sum --check
      test "$(stat -c%s "$out/published.apk")" = "$(jq -er '.sizeBytes' "$out/manifest.json")"
    fi
    gradle -p "$source" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process "${args[@]}" "assemble$variant" "assemble${variant}AndroidTest"
    if [ "$variant" = Debug ]; then cp "$source/app/build/outputs/apk/debug/app-debug.apk" "$out/published.apk"; fi
    cp "$source/app/build/outputs/apk/androidTest/${variant,,}/app-${variant,,}-androidTest.apk" "$out/test.apk"
    ;;
  test)
    adb shell wm size 1080x1920
    adb install -r -g "$out/published.apk"
    adb install -r -g "$out/test.apk"
    adb shell dumpsys package "$package" > "$out/installed-package.txt"
    adb shell am instrument -w -r -e class dev.multiprompt.companion.ui.ChatBubbleWidthTest -e additionalTestOutputDir "/sdcard/Android/media/$package/width-proof" "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
    adb pull "/sdcard/Android/media/$package/width-proof/bubble-width" "$out/"
    grep -F 'OK (1 test)' "$out/instrumentation.txt"
    echo CHAT_BUBBLE_WIDTH_OK
    ;;
  *) exit 2 ;;
esac
