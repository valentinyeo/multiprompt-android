#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
node --test tools/root-files.test.js tools/strip-comments.test.js
node tools/root-files.mjs
node tools/strip-comments.mjs --check
"${GRADLE:-./gradlew}" --no-daemon lintDebug test
