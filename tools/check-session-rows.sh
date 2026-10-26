#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/session-rows/source
file=app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt
cp "$file" build/session-rows/source/MultipromptApp.kt
restore() { cp build/session-rows/source/MultipromptApp.kt "$file"; }
trap restore EXIT
run_rows() {
  local phase=$1 status=0 result
  rm -rf app/build/outputs/connected_android_test_additional_output
  "${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.SessionRowsTest > "build/session-rows/$phase.log" 2>&1 || status=$?
  cat "build/session-rows/$phase.log"
  result=$(find app/build/outputs -type d -name session-rows -print -quit)
  test -n "$result"
  mkdir -p "build/session-rows/$phase"
  cp "$result"/* "build/session-rows/$phase/"
  return "$status"
}
git show 5204eda:app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt > "$file"
if run_rows before; then
  echo 'Original rows unexpectedly passed' >&2
  exit 1
fi
grep -F 'still shows status lines' build/session-rows/before/rows.txt
grep -E 'yellow marker pixels=[1-9]' build/session-rows/before/rows.txt
echo SESSION_ROWS_RED_OK
restore
trap - EXIT
run_rows after
test "$(find build/session-rows/after -name '*.png' | wc -l)" -eq 5
echo SESSION_ROWS_EMULATOR_OK
