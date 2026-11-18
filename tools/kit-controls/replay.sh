#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."

probe=app/src/main/java/dev/multiprompt/companion/ui/KitBoundaryProbe.kt
test ! -e "$probe"
trap 'rm -f "$probe"' EXIT
cat > "$probe" <<'KOTLIN'
package dev.multiprompt.companion.ui

import androidx.compose.material3.AlertDialog as PretendKitDialog
import androidx.compose.runtime.Composable

@Composable
internal fun KitBoundaryProbe() {
    PretendKitDialog(onDismissRequest = {}, confirmButton = {})
    androidx.compose.material3.AlertDialog(onDismissRequest = {}, confirmButton = {})
    androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = {}) {}
}
KOTLIN

report=app/build/reports/lint-results-debug.xml
rm -f "$report"
if "${GRADLE:-./gradlew}" -Dorg.gradle.jvmargs=-Xmx3g lintDebug; then
  echo 'KIT_CONTROL_REQUIRED: a new Material dialog or menu escaped the kit boundary' >&2
  exit 1
fi
python3 - "$report" <<'PYTHON'
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
issues = [issue for issue in root.findall('issue') if issue.get('id') == 'KitControlRequired' and any(location.get('file', '').endswith('KitBoundaryProbe.kt') for location in issue.findall('location'))]
assert len(issues) == 3, [(issue.get('id'), issue.get('message')) for issue in root.findall('issue')]
assert sum('Use KitSheet with KitPrimaryButton/KitSecondaryButton' in issue.get('message', '') for issue in issues) == 2
assert sum('Use KitSheet (or KitMenu) with KitMenuRow' in issue.get('message', '') for issue in issues) == 1
output = Path('app/build/reports/kit-controls')
output.mkdir(parents=True, exist_ok=True)
shutil.copyfile(sys.argv[1], output / 'new-controls-rejected.xml')
print('KIT_CONTROL_REQUIRED_RED_OK: real Material dependency, alias, qualified dialog and menu rejected')
PYTHON
