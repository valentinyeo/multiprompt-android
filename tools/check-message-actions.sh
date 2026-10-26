#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/message-actions
"${GRADLE:-./gradlew}" --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.MessageActionsTest
find app/build/outputs -type f -name 'message-actions-*.png' -exec cp '{}' build/message-actions/ \;
python3 tools/check-message-action-images.py build/message-actions
echo MESSAGE_ACTIONS_EMULATOR_OK
