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
python3 - "$file" <<'PYTHON'
from pathlib import Path
import subprocess
import sys
path = Path(sys.argv[1])
current = path.read_text()
old = subprocess.check_output(['git', 'show', '5204eda:' + str(path)], text=True)
start = 'private fun SessionCard('
end = '@Composable\nprivate fun DissolvedSessionCard('
original = old[old.index(start):old.index(end)]
original = original.replace('workspaceName: String?,', 'workspaceName: String? = null,').replace('etaLabel: String?,', 'etaLabel: String? = null,')
path.write_text(current[:current.index(start)] + original + current[current.index(end):])
PYTHON
if run_rows before; then
  echo 'Original rows unexpectedly passed' >&2
  exit 1
fi
grep -E 'yellow marker pixels=[1-9]' build/session-rows/before/rows.txt
python3 - <<'PYTHON'
from pathlib import Path
import xml.etree.ElementTree as ET
failures = []
for path in Path('app/build/outputs/androidTest-results/connected').rglob('*.xml'):
    for case in ET.parse(path).iter('testcase'):
        if case.get('name') == 'longPressRemovesAndAddsFavoriteWithoutOpeningOrDeletingSession':
            failures.extend(''.join(failure.itertext()) for failure in case.findall('failure'))
assert any('Remove from Favorites' in failure for failure in failures), failures
print('FAVORITE_ACTIONS_RED_OK')
PYTHON
echo SESSION_ROWS_RED_OK
restore
trap - EXIT
run_rows after
test "$(find build/session-rows/after -name '*.png' | wc -l)" -eq 9
grep -F 'FAVORITE_ACTIONS_OK:' build/session-rows/after/favorite-actions.txt
echo SESSION_ROWS_EMULATOR_OK
