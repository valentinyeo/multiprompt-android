#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "$(adb get-serialno)" == emulator-* ]]
[[ "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion || "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion.m3 ]]
if [[ "$VERIFY_PACKAGE" == dev.multiprompt.companion.m3 ]]; then [[ "${VERIFY_FEATURE:-}" == chat-resync.md ]]; fi
python3 -m http.server 8788 > "$FIXTURE/offline-proxy.log" 2>&1 &
echo "$!" > "$FIXTURE/offline-proxy.pid"
adb shell settings put global http_proxy 10.0.2.2:8788
adb install -r "$RUNNER_TEMP/release-choices/seed.apk"
adb shell am instrument -w dev.multiprompt.verification/dev.multiprompt.verification.Seed | tee "$EVIDENCE/seed-result.txt"
grep -F ISOLATED_RELEASE_SESSION_SEEDED "$EVIDENCE/seed-result.txt"
printf '%s\n' 'Account session is an isolated offline fixture. No production login or account request is part of this proof.' > "$EVIDENCE/session-scope.txt"
