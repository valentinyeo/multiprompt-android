#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
file=app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt
mkdir -p build/robot-faces
cp "$file" build/robot-faces/candidate.kt
avatar=app/src/main/java/dev/multiprompt/companion/ui/BotAvatar.kt
cp "$avatar" build/robot-faces/avatar.kt
restore() { cp build/robot-faces/candidate.kt "$file"; cp build/robot-faces/avatar.kt "$avatar"; }
trap restore EXIT
run_robots() {
    local phase=$1 status=0 result
    rm -rf app/build/outputs/connected_android_test_additional_output
    "${GRADLE:-gradle}" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.RobotFacesTest > "build/robot-faces/$phase.log" 2>&1 || status=$?
    cat "build/robot-faces/$phase.log"
    mkdir -p "build/robot-faces/$phase"
    cp app/build/outputs/androidTest-results/connected/debug/TEST-*.xml "build/robot-faces/$phase/"
    result=$(find app/build/outputs -type d -name robot-faces -print -quit)
    if [ -n "$result" ]; then cp "$result"/*.png "build/robot-faces/$phase/"; fi
    return "$status"
}
python3 - "$avatar" <<'PYCONTROL'
from pathlib import Path
import sys
path = Path(sys.argv[1])
s = path.read_text()
anchor = 'color?.mod(7) ?: workspaceColor'
if anchor in s:
    s = s.replace(anchor, 'color?.mod(7) ?: 0')
else:
    legacy = '(hash % 20u).toInt(), workspaceColor'
    if legacy not in s:
        raise SystemExit('robot control cannot patch the workspace colour expression; update tools/robot-faces/proof.sh')
    s = s.replace(legacy, '(hash % 20u).toInt(), 0')
path.write_text(s)
PYCONTROL
if run_robots workspace-before; then echo 'Unshared colors unexpectedly match desktop' >&2; exit 1; fi
python3 - <<'PYCONTROL'
from pathlib import Path
import xml.etree.ElementTree as ET
cases = [c for f in Path('build/robot-faces/workspace-before').glob('TEST-*.xml') for c in ET.parse(f).iter('testcase')]
assert len(cases) == 1
failure = cases[0].find('failure')
assert failure is not None and 'workspace hue' in failure.text, ET.tostring(cases[0])
print('UNSHARED_WORKSPACE_COLORS_REJECTED')
PYCONTROL
restore
trap - EXIT
run_robots after
test -s build/robot-faces/after/robot-inbox.png
test -s build/robot-faces/after/robot-chat-title.png
run_read_aloud() {
    local status=0
    rm -rf app/build/outputs/connected_android_test_additional_output app/build/outputs/androidTest-results/connected
    "${GRADLE:-gradle}" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.ReadAloudSoundTest > build/robot-faces/read-aloud.log 2>&1 || status=$?
    cat build/robot-faces/read-aloud.log
    local result
    result=$(find app/build/outputs -type d -name robot-faces -print -quit)
    if [ -n "$result" ]; then mkdir -p build/robot-faces/read-aloud && cp "$result"/* build/robot-faces/read-aloud/ 2>/dev/null || true; fi
    return "$status"
}
run_read_aloud
test -s build/robot-faces/read-aloud/read-aloud.wav
echo ROBOT_FACES_EMULATOR_VERIFIED
