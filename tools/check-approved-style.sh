#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
phase=${STYLE_PHASE:-after}
mode=${1:-run}
mkdir -p "build/approved-style/$phase"
if [ "$phase" = before ]; then
  snapshot=build/approved-style/source
  restore() {
    test -d "$snapshot/main"
    test -d "$snapshot/androidTest"
    rm -rf app/src/main app/src/androidTest
    cp -a "$snapshot/main" app/src/main
    cp -a "$snapshot/androidTest" app/src/androidTest
  }
  if [ "$mode" = prepare ]; then
    mkdir -p "$snapshot"
    cp -a app/src/main "$snapshot/main"
    cp -a app/src/androidTest "$snapshot/androidTest"
    trap restore ERR
    rm -rf app/src/main app/src/androidTest
    git archive ce2d32d app/src/main app/src/androidTest | tar -x
    cp "$snapshot/androidTest/java/dev/multiprompt/companion/ui/ApprovedStyleTest.kt" app/src/androidTest/java/dev/multiprompt/companion/ui/ApprovedStyleTest.kt
  else
    test -s "$snapshot/main/java/dev/multiprompt/companion/ui/Theme.kt"
    trap restore EXIT
  fi
fi
if [ "$mode" = prepare ]; then
  "${GRADLE:-gradle}" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process assembleDebug assembleDebugAndroidTest
  trap - ERR
  exit 0
fi
status=0
"${GRADLE:-gradle}" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.ApprovedStyleTest > "build/approved-style/$phase/emulator.log" 2>&1 || status=$?
cat "build/approved-style/$phase/emulator.log"
result=$(find app/build/outputs -type d -name approved-style -print -quit)
test -n "$result"
cp "$result"/* "build/approved-style/$phase/"
python3 - "build/approved-style/$phase" <<'PYTHON'
from pathlib import Path
import runpy
import sys
verify = runpy.run_path('tools/check-day-theme-images.py')['verify_image']
folder = Path(sys.argv[1])
verify(folder / 'inbox.png', 'inbox')
verify(folder / 'chat.png', 'chat')
PYTHON
if [ "$phase" = before ]; then
  test "$status" -ne 0
  grep -F 'APPROVED_STYLE:' "build/approved-style/$phase/emulator.log"
  grep -F 'inbox controls missing: 0 pixels' "build/approved-style/$phase/samples.txt"
  echo APPROVED_STYLE_OLD_CODE_RED_OK
else
  test "$status" -eq 0
  test -s "build/approved-style/$phase/inbox.png"
  test -s "build/approved-style/$phase/chat.png"
  echo APPROVED_STYLE_EMULATOR_OK
fi
