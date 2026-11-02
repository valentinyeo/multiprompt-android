#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

gradle_run() {
  "${GRADLE:-./gradlew}" -Dorg.gradle.jvmargs=-Xmx3g "$@"
}

source_checks() {
  node --test tools/root-files.test.js tools/strip-comments.test.js tools/release-status.test.js tools/verify-published.test.mjs tools/workflow-inputs.test.js tools/pr-checks.test.js
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
  python3 -B tools/long-chat/check.test.py
  python3 -B tools/app-name/drive.test.py
  node tools/root-files.mjs
  node tools/strip-comments.mjs --check
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
  m3) m3_unit_checks ;;
  lint) lint_checks ;;
  all) source_checks; unit_checks; m3_unit_checks; lint_checks ;;
  *) echo "unknown phase $1: use source, unit, m3, lint or all" >&2; exit 2 ;;
esac
