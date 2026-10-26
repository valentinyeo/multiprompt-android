#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/approved-style
status=0
"${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.ApprovedStyleTest > build/approved-style/emulator.log 2>&1 || status=$?
cat build/approved-style/emulator.log
result=$(find app/build/outputs -type d -name approved-style -print -quit)
test -n "$result"
cp "$result"/* build/approved-style/
test "$status" -eq 0
echo APPROVED_STYLE_EMULATOR_OK
