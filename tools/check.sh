#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
node --test tools/root-files.test.js tools/strip-comments.test.js tools/release-status.test.js tools/workflow-inputs.test.js
python3 tools/session-routing.test.py
node tools/root-files.mjs
node tools/strip-comments.mjs --check
"${GRADLE:-./gradlew}" --no-daemon -Dorg.gradle.jvmargs=-Xmx3g lintDebug test testM3UnitTest
