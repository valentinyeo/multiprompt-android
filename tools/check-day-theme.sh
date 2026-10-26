#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/day-theme
run_theme() {
  local label=$1 status=0
  rm -rf app/build/outputs/connected_android_test_additional_output
  "${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.DayThemeTest > "build/day-theme/$label.log" 2>&1 || status=$?
  cat "build/day-theme/$label.log"
  local result
  result=$(find app/build/outputs -type d -name day-theme -print -quit)
  test -n "$result"
  mkdir -p "build/day-theme/$label"
  cp "$result"/* "build/day-theme/$label/"
  python3 tools/check-day-theme-images.py "build/day-theme/$label" || return 1
  if [ "$label" = after ] && [ "$status" -ne 0 ]; then
    failure=$(grep -m 1 -E '(^e:|DAY_THEME_CONTRAST)' "build/day-theme/$label.log" || tail -n 1 "build/day-theme/$label.log")
    printf '::error title=Day theme proof::%s\n' "$failure"
  fi
  return "$status"
}
if [ -n "${DAY_THEME_BASE_REF:-}" ]; then
  base=$(git rev-parse --verify "$DAY_THEME_BASE_REF^{commit}")
  printf 'DAY_THEME_BASE_COMMIT=%s\n' "$base"
  files=(app/src/main/java/dev/multiprompt/companion/ui/Theme.kt app/src/main/java/dev/multiprompt/companion/ui/ChatBubble.kt app/src/main/java/dev/multiprompt/companion/ui/TopFade.kt app/src/main/java/dev/multiprompt/companion/MainActivity.kt app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt)
  mkdir -p build/day-theme/source
  restore() {
    for file in "${files[@]}"; do cp "build/day-theme/source/$(basename "$file")" "$file"; done
  }
  for file in "${files[@]}"; do cp "$file" "build/day-theme/source/$(basename "$file")"; done
  trap restore EXIT
  for file in "${files[@]}"; do git show "$base:$file" > "$file"; done
  if run_theme before; then
    echo 'Expected original light theme to fail contrast checks' >&2
    exit 1
  fi
  test -f build/day-theme/before/contrast.txt
  grep -E 'contrast=[0-3]\.|status icons do not follow' build/day-theme/before/contrast.txt
  echo DAY_THEME_RED_OK
  restore
  trap - EXIT
fi
run_theme after
if [ -n "${DAY_THEME_BASE_REF:-}" ]; then
  python3 - <<'PY'
from pathlib import Path
from PIL import Image, ImageChops
for before in sorted(Path('build/day-theme/before').glob('dark-*.png')):
    a = Image.open(before).convert('RGB')
    b = Image.open(Path('build/day-theme/after') / before.name).convert('RGB')
    assert a.size == b.size
    crop = (0, 80, a.width, a.height - 80)
    assert ImageChops.difference(a.crop(crop), b.crop(crop)).getbbox() is None, before.name
    print('UNCHANGED_DARK', before.name)
assert len(list(Path('build/day-theme/before').glob('dark-*.png'))) == 5
print('DAY_THEME_DARK_UNCHANGED_OK')
PY
fi
if [ "${DAY_THEME_RELEASED_UPDATE:-false}" = true ]; then
  curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o build/day-theme/release-manifest.json
  python3 - <<'PYTHON'
import hashlib, json, os, subprocess
from urllib.request import urlopen
manifest = json.load(open('build/day-theme/release-manifest.json'))
assert manifest['schemaVersion'] == 1
assert manifest['commit'] == os.environ['GITHUB_SHA']
assert manifest['apkUrl'] == 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion.apk'
apk = urlopen(manifest['apkUrl']).read()
assert len(apk) == manifest['sizeBytes']
assert hashlib.sha256(apk).hexdigest() == manifest['sha256']
open('build/day-theme/released.apk', 'wb').write(apk)
subprocess.run([os.environ['ANDROID_HOME'] + '/build-tools/36.0.0/apksigner', 'verify', '--print-certs', 'build/day-theme/released.apk'], check=True)
print('SIGNED_DAY_THEME_UPDATE_OK', manifest['versionName'], manifest['commit'])
PYTHON
  adb shell cmd uimode night no
  adb install -r -g build/day-theme/released.apk
  adb shell am start -W -n dev.multiprompt.companion/.MainActivity
  adb shell uiautomator dump /sdcard/day-theme.xml
  adb pull /sdcard/day-theme.xml build/day-theme/release-screen.xml
  python3 - <<'PYTHON'
import xml.etree.ElementTree as ET
root = ET.parse('build/day-theme/release-screen.xml')
assert any(node.get('text') == 'Sign in' for node in root.iter('node'))
assert any(node.get('text') == 'multiprompt' for node in root.iter('node'))
print('RELEASED_DAY_THEME_VISIBLE_OK')
PYTHON
  adb exec-out screencap -p > build/day-theme/released-sign-in.png
  adb shell dumpsys package dev.multiprompt.companion > build/day-theme/release-package.txt
  rm build/day-theme/released.apk
fi
echo DAY_THEME_EMULATOR_OK
