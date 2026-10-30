#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/message-actions
status=0
"${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.reader.ChatHistoryActorTest,dev.multiprompt.companion.reader.MessageProtocolTest,dev.multiprompt.companion.ChatReaderBundleTest,dev.multiprompt.companion.ui.MessageActionsTest,dev.multiprompt.companion.ui.PendingMessagesTest,dev.multiprompt.companion.ui.ReplyReferenceTest,dev.multiprompt.companion.ui.SessionNoteChipTest || status=$?
find app/build/outputs/androidTest-results -type f -name '*.xml' -exec cp '{}' build/message-actions/ \;
find app/build/outputs -type d -name pending-messages -exec cp -R '{}' build/message-actions/ \;
find app/build/outputs -type f \( -name 'message-actions-*.png' -o -name 'reply-*.png' -o -name 'reply-*.json' -o -name 'session-note-*.png' \) -exec cp '{}' build/message-actions/ \;
if [ "$status" -ne 0 ]; then exit "$status"; fi
python3 - <<'PYTESTCOUNTS' | tee build/message-actions/test-counts.txt
from pathlib import Path
import xml.etree.ElementTree as ET

required = {
    'dev.multiprompt.companion.reader.ChatHistoryActorTest',
    'dev.multiprompt.companion.reader.MessageProtocolTest',
    'dev.multiprompt.companion.ChatReaderBundleTest',
    'dev.multiprompt.companion.ui.PendingMessagesTest',
}
counts = {name: 0 for name in required}
for path in Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        name = case.get('classname')
        if name in counts:
            assert case.find('failure') is None and case.find('error') is None and case.find('skipped') is None, (path, name, case.get('name'))
            counts[name] += 1
for name, count in sorted(counts.items()):
    assert count > 0, f'No executed tests: {name}'
    print(f'INSTRUMENTATION_TEST_COUNT {name} {count}')
PYTESTCOUNTS
python3 tools/check-message-action-images.py build/message-actions
echo MESSAGE_ACTIONS_EMULATOR_OK
