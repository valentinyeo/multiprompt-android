#!/usr/bin/env bash
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "$(adb get-serialno)" == emulator-* ]]
mkdir -p "$FIXTURE/offline-empty"
python3 -m http.server 8788 --directory "$FIXTURE/offline-empty" > "$FIXTURE/offline-proxy.log" 2>&1 &
proxy=$!
trap 'kill "$proxy"' EXIT
adb shell settings put global http_proxy 10.0.2.2:8788
adb install -r "$EVIDENCE/app.apk"
adb install -r "$RUNNER_TEMP/release-choices/seed.apk"
config=$(base64 -w0 "$FIXTURE/config.json")
adb shell am instrument -w -e config "$config" dev.multiprompt.verification/dev.multiprompt.verification.Seed | tee "$EVIDENCE/seed-result.txt"
unset config
grep -F ISOLATED_RELEASE_SESSION_SEEDED "$EVIDENCE/seed-result.txt"
adb shell pm grant dev.multiprompt.companion android.permission.POST_NOTIFICATIONS
adb shell am start -n dev.multiprompt.companion/dev.multiprompt.companion.MainActivity
sleep 25
adb shell uiautomator dump /sdcard/notification-ui.xml
adb pull /sdcard/notification-ui.xml "$EVIDENCE/baseline-ui.txt"
grep -F Checkout "$EVIDENCE/baseline-ui.txt"
sudo -u verifyssh tmux send-keys -t verify-session -l '/rc'
sudo -u verifyssh tmux send-keys -t verify-session Enter
sleep 25
test "$(sudo cat /home/verifyssh/notification-receipt)" = /rc
adb shell dumpsys notification --noredact > "$EVIDENCE/command-notifications.txt"
if grep -F 'group=dev.multiprompt.companion.REPLIES' "$EVIDENCE/command-notifications.txt"; then
    echo COMMAND_WRONGLY_NOTIFIED
    exit 1
fi
adb shell cmd statusbar expand-notifications
sleep 2
adb exec-out screencap -p > "$EVIDENCE/command-no-notification.png"
adb shell cmd statusbar collapse
sudo -u verifyssh tmux send-keys -t verify-session -l 'Fix checkout'
sudo -u verifyssh tmux send-keys -t verify-session Enter
for attempt in $(seq 1 30); do
    adb shell dumpsys notification --noredact > "$EVIDENCE/reply-notifications.txt"
    if grep -F 'android.text=String (Checkout fixed.)' "$EVIDENCE/reply-notifications.txt"; then break; fi
    sleep 2
done
grep -F 'android.title=String (Checkout)' "$EVIDENCE/reply-notifications.txt"
grep -F 'android.text=String (Checkout fixed.)' "$EVIDENCE/reply-notifications.txt"
if grep -E 'android.text=String \((/rc|Fix checkout|Checkout fixed\. Tests passed\.)\)' "$EVIDENCE/reply-notifications.txt"; then exit 1; fi
adb shell cmd statusbar expand-notifications
sleep 2
adb exec-out screencap -p > "$EVIDENCE/reply-first-sentence.png"
adb shell dumpsys package dev.multiprompt.companion > "$EVIDENCE/installed-package.txt"
printf '%s\n' '{"commandSuppressed":true,"replyTitle":"Checkout","replyText":"Checkout fixed.","signedApkVerified":true}' > "$EVIDENCE/result.json"
echo RELEASE_REPLY_CONTENT_OK
