#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "$(adb get-serialno)" == emulator-* ]]
[[ "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion || "${VERIFY_PACKAGE:-}" == dev.multiprompt.companion.m3 ]]
if [[ "$VERIFY_PACKAGE" == dev.multiprompt.companion.m3 ]]; then [[ "${VERIFY_FEATURE:-}" == chat-resync.md || "${VERIFY_FEATURE:-}" == session-favorites.md ]]; fi
python3 -m http.server 8788 > "$FIXTURE/offline-proxy.log" 2>&1 &
echo "$!" > "$FIXTURE/offline-proxy.pid"
adb shell settings put global http_proxy 10.0.2.2:8788
if [[ "${VERIFY_FEATURE:-}" == session-favorites.md ]]; then
    kill -0 "$(cat "$FIXTURE/offline-proxy.pid")"
    [[ "$(adb shell settings get global http_proxy | tr -d '\r')" == 10.0.2.2:8788 ]]
    python3 - <<'PYPROXY'
import socket
import time
for attempt in range(20):
    try:
        with socket.create_connection(('127.0.0.1', 8788), timeout=1) as connection:
            connection.sendall(b'CONNECT fixture.invalid:443 HTTP/1.1\r\nHost: fixture.invalid\r\n\r\n')
            assert b' 501 ' in connection.recv(1024).split(b'\r\n', 1)[0]
        break
    except OSError:
        if attempt == 19:
            raise
        time.sleep(0.1)
PYPROXY
fi
adb install -r "$RUNNER_TEMP/release-choices/seed.apk"
if [[ "${VERIFY_FEATURE:-}" == session-favorites.md ]]; then
    adb shell am instrument -w -e mode favorites-account dev.multiprompt.verification/dev.multiprompt.verification.Seed | tee "$EVIDENCE/seed-result.txt"
else
    adb shell am instrument -w dev.multiprompt.verification/dev.multiprompt.verification.Seed | tee "$EVIDENCE/seed-result.txt"
fi
grep -F ISOLATED_RELEASE_SESSION_SEEDED "$EVIDENCE/seed-result.txt"
printf '%s\n' 'Account session is an isolated offline fixture. No production login or account request is part of this proof.' > "$EVIDENCE/session-scope.txt"
