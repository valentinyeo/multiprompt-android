#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
phase=${STYLE_PHASE:-after}
mkdir -p "build/approved-style/$phase"
files=(Theme.kt MultipromptApp.kt ChatBubble.kt ChatHistoryList.kt AppMenu.kt)
if [ "$phase" = before ]; then
  mkdir -p build/approved-style/source
  restore() {
    for file in "${files[@]}"; do cp "build/approved-style/source/$file" "app/src/main/java/dev/multiprompt/companion/ui/$file"; done
  }
  for file in "${files[@]}"; do
    cp "app/src/main/java/dev/multiprompt/companion/ui/$file" "build/approved-style/source/$file"
    git show ce2d32d:app/src/main/java/dev/multiprompt/companion/ui/"$file" > "app/src/main/java/dev/multiprompt/companion/ui/$file"
  done
  trap restore EXIT
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
