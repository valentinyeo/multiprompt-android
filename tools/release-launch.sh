#!/usr/bin/env bash
set -euo pipefail

package=dev.multiprompt.companion.m3
activity=dev.multiprompt.companion.MainActivity
evidence=build/release-launch-evidence

capture_evidence() {
  adb shell dumpsys activity activities > "$evidence/activity.txt" 2>&1 || true
  adb logcat -d > "$evidence/logcat.txt" 2>&1 || true
  adb exec-out screencap -p > "$evidence/screenshot.png" 2>/dev/null || true
}
trap capture_evidence EXIT

adb install -g app/build/outputs/apk/m3/app-m3.apk
adb shell am start -W -n "$package/$activity"
for attempt in {1..15}; do
  if adb shell dumpsys activity activities | grep -E "(mResumedActivity|topResumedActivity).*${package//./\\.}/(dev\\.multiprompt\\.companion\\.)?MainActivity" && adb shell pidof "$package"; then
    exit 0
  fi
  sleep 1
done
echo 'MainActivity did not resume with the package alive' >&2
exit 1
