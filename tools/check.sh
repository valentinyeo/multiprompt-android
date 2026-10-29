#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
node --test tools/root-files.test.js tools/strip-comments.test.js tools/release-status.test.js tools/workflow-inputs.test.js tools/pr-checks.test.js
python3 -B tools/send-frame.test.py
python3 -B tools/working-dots/check.test.py
python3 -B tools/history-continuity.test.py
python3 tools/terminal-console.test.py
python3 tools/session-routing.test.py
python3 -B tools/structured-questions.test.py
python3 -B tools/native-skills.test.py
python3 -B tools/release-choices/ui.test.py
python3 -B tools/check-approved-style-source.test.py
python3 -B tools/account-journey/ui.test.py
python3 -B tools/check-day-theme-images.test.py
python3 -B tools/emulator.test.py
node tools/root-files.mjs
node tools/strip-comments.mjs --check
"${GRADLE:-./gradlew}" --no-daemon -Dorg.gradle.jvmargs=-Xmx3g lintDebug test testM3UnitTest
