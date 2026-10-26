#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/message-actions
status=0
"${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.MessageActionsTest,dev.multiprompt.companion.ui.PendingMessagesTest,dev.multiprompt.companion.ui.ReplyReferenceTest || status=$?
find app/build/outputs -type d -name pending-messages -exec cp -R '{}' build/message-actions/ \;
find app/build/outputs -type f \( -name 'message-actions-*.png' -o -name 'reply-*.png' -o -name 'reply-layout.json' \) -exec cp '{}' build/message-actions/ \;
if [ "$status" -ne 0 ]; then exit "$status"; fi
python3 tools/check-message-action-images.py build/message-actions
echo MESSAGE_ACTIONS_EMULATOR_OK
