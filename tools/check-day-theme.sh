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
  return "$status"
}
if [ -n "${DAY_THEME_BASE_REF:-}" ]; then
  base=$(git rev-parse --verify "$DAY_THEME_BASE_REF^{commit}")
  files=(app/src/main/java/dev/multiprompt/companion/ui/Theme.kt app/src/main/java/dev/multiprompt/companion/ui/ChatBubble.kt app/src/main/java/dev/multiprompt/companion/ui/TopFade.kt app/src/main/java/dev/multiprompt/companion/MainActivity.kt)
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
  rg 'contrast=[0-3]\.|status icons do not follow' build/day-theme/before/contrast.txt
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
echo DAY_THEME_EMULATOR_OK
