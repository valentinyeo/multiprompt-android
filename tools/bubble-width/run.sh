#!/usr/bin/env bash
set -euo pipefail
adb shell wm size 1080x2400
adb install -r -g "$EVIDENCE/app.apk"
adb shell cmd uimode night yes
adb shell dumpsys package dev.multiprompt.companion > "$EVIDENCE/installed-package.txt"
bash tools/release-choices/seed.sh
adb push "$FIXTURE/user-key" /data/local/tmp/long-chat-key
adb shell chmod 644 /data/local/tmp/long-chat-key
host_key=$(cut -d' ' -f1,2 "$FIXTURE/host-key.pub")
finish() {
    code=$?
    trap - EXIT
    adb shell rm -f /data/local/tmp/long-chat-key
    adb logcat -d > "$EVIDENCE/logcat.txt" || true
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
trap finish EXIT
adb shell "am instrument -w -e hostKey '$host_key' dev.multiprompt.verification/dev.multiprompt.verification.Observe" | tee "$EVIDENCE/observer.txt"
grep -F WIDTH_SCREEN_CAPTURED "$EVIDENCE/observer.txt"
adb pull /sdcard/Android/data/dev.multiprompt.companion/files/bubble-width "$EVIDENCE/screens"
if [[ "$WIDTH_PHASE" == before ]]; then
    if python3 tools/bubble-width/check.py "$EVIDENCE/screens" after > "$EVIDENCE/old-width-red.txt" 2>&1; then
        cat "$EVIDENCE/old-width-red.txt"
        exit 1
    fi
    cat "$EVIDENCE/old-width-red.txt"
    grep -F "AssertionError: ('WIDTH_MAXIMUM_MISMATCH'" "$EVIDENCE/old-width-red.txt"
fi
python3 tools/bubble-width/check.py "$EVIDENCE/screens" "$WIDTH_PHASE"
