#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

gradle_run() {
  "${GRADLE:-./gradlew}" -Dorg.gradle.jvmargs=-Xmx3g "$@"
}

source_checks() {
  node --test tools/root-files.test.js tools/strip-comments.test.js tools/release-status.test.js tools/verify-published.test.mjs tools/workflow-inputs.test.js tools/pr-checks.test.js tools/release-notes/notes.test.mjs tools/baseline-profile.test.mjs
  python3 -B tools/session-gestures/scroll.test.py
  python3 -B tools/send-frame.test.py
  python3 -B tools/working-dots/check.test.py
  bash tools/check-canonical-chat-reader.sh
  python3 -B tools/history-continuity.test.py
  python3 -B tools/queue-receipts.test.py
  python3 tools/terminal-console.test.py
  python3 tools/session-routing.test.py
  python3 -B tools/codex-sync.test.py
  python3 -B tools/structured-questions.test.py
  python3 -B tools/native-skills.test.py
  python3 -B tools/release-choices/ui.test.py
  python3 -B tools/check-approved-style-source.test.py
  python3 -B tools/account-journey/ui.test.py
  python3 -B tools/check-day-theme-images.test.py
  python3 -B tools/emulator.test.py
  python3 -B tools/startup-time/report.test.py
  python3 -B tools/long-chat/check.test.py
  python3 -B tools/app-name/drive.test.py
  python3 -B tools/app-name/select_run.test.py
  node tools/root-files.mjs
  node tools/strip-comments.mjs --check
}

unit_red_checks() {
  (
    file=app/src/main/java/dev/multiprompt/companion/MainViewModel.kt
    saved=$(mktemp)
    cp "$file" "$saved"
    trap 'cp "$saved" "$file"; rm -f "$saved"' EXIT
    python3 - "$file" <<'PYTHON'
from pathlib import Path
import sys
path = Path(sys.argv[1])
source = path.read_text()
old = 'publishAccountLayout(photosChanged = true)'
new = 'publishAccountLayout()'
assert source.count(old) == 1
path.write_text(source.replace(old, new, 1))
PYTHON
    result=app/build/test-results/testDebugUnitTest/TEST-dev.multiprompt.companion.account.AccountHostsAppTest.xml
    rm -f "$result"
    if gradle_run testDebugUnitTest --tests '*AccountHostsAppTest.lateBotPhotoChangesStateWithoutChangingLayoutAndCanBeReplacedOrReset'; then
      echo 'Unpublished photo unexpectedly changed state' >&2
      exit 1
    fi
    python3 - "$result" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
cases = [case for case in root.iter('testcase') if case.get('name') == 'lateBotPhotoChangesStateWithoutChangingLayoutAndCanBeReplacedOrReset']
assert len(cases) == 1
failures = cases[0].findall('failure')
assert len(failures) == 1 and 'LATE_PHOTO_STATE_UNCHANGED' in ''.join(failures[0].itertext())
print('LATE_PHOTO_STATE_RED_OK: LATE_PHOTO_STATE_UNCHANGED')
PYTHON
  )
}

unit_checks() {
  gradle_run testDebugUnitTest
}

m3_unit_checks() {
  gradle_run testM3UnitTest
}

lint_checks() {
  gradle_run lintDebug
}

case "${1:-all}" in
  source) source_checks ;;
  unit) unit_checks ;;
  unit-red) unit_red_checks ;;
  m3) m3_unit_checks ;;
  lint) lint_checks ;;
  all) source_checks; unit_red_checks; unit_checks; m3_unit_checks; lint_checks ;;
  *) echo "unknown phase $1: use source, unit, unit-red, m3, lint or all" >&2; exit 2 ;;
esac
