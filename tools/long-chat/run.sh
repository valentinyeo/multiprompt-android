#!/usr/bin/env bash
set -euo pipefail
adb install -r -g "$EVIDENCE/app.apk"
bash tools/release-choices/seed.sh
adb push "$FIXTURE/user-key" /data/local/tmp/long-chat-key
adb shell chmod 644 /data/local/tmp/long-chat-key
host_key=$(cut -d' ' -f1,2 "$FIXTURE/host-key.pub")
recorder=
finish() {
    code=$?
    trap - EXIT
    if [ -n "$recorder" ]; then
        adb shell kill -INT "$recorder" || true
        wait "$record_shell" || true
    fi
    adb pull /sdcard/long-chat.mp4 "$EVIDENCE/long-chat.mp4" || true
    adb pull /sdcard/Android/data/dev.multiprompt.companion/files/long-chat "$EVIDENCE/screens" || true
    adb logcat -d > "$EVIDENCE/logcat.txt" || true
    adb shell rm -f /data/local/tmp/long-chat-key
    kill "$(cat "$FIXTURE/offline-proxy.pid")"
    exit "$code"
}
trap finish EXIT
adb shell 'screenrecord --time-limit 180 --bit-rate 1000000 /sdcard/long-chat.mp4 & echo $! > /data/local/tmp/long-chat-recorder; wait' &
record_shell=$!
adb shell 'while [ ! -s /data/local/tmp/long-chat-recorder ]; do sleep 0.1; done'
recorder=$(adb shell cat /data/local/tmp/long-chat-recorder | tr -d '\r')
[[ "$recorder" =~ ^[0-9]+$ ]]
phase=initial
if [[ "${SMOKE_ONLY:-false}" == true || "${NEGATIVE_CONTROL:-false}" == true ]]; then phase=smoke; fi
adb shell "am instrument -w -e phase '$phase' -e hostKey '$host_key' dev.multiprompt.verification/dev.multiprompt.verification.Observe" | tee "$EVIDENCE/initial.txt"
if [[ "${NEGATIVE_CONTROL:-false}" == true ]]; then
    grep -F 'VISIBLE_REPLY_MISSING: LONG_CHAT_LATEST_1156' "$EVIDENCE/initial.txt"
    if grep -F 'LONG_CHAT_UI_OK' "$EVIDENCE/initial.txt"; then exit 1; fi
    echo MISSING_TRANSCRIPT_REJECTED
    exit 0
fi
grep -F LONG_CHAT_UI_OK "$EVIDENCE/initial.txt"
adb shell am force-stop dev.multiprompt.companion
adb shell am instrument -w -e phase reopen \
    dev.multiprompt.verification/dev.multiprompt.verification.Observe | tee "$EVIDENCE/reopen.txt"
grep -F LONG_CHAT_UI_OK "$EVIDENCE/reopen.txt"
echo LONG_CHAT_RELEASE_PROVEN
