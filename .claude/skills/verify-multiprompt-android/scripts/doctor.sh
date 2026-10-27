#!/usr/bin/env bash
set -euo pipefail
: "${EXPECTED_VERSION:?Set from the APK build version}"
: "${EVIDENCE:?Set the evidence directory}"
package=${VERIFY_PACKAGE:-dev.multiprompt.companion.debug}
mkdir -p "$EVIDENCE"
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]
adb shell dumpsys package "$package" > "$EVIDENCE/package.txt"
sed -n 's/^[[:space:]]*versionName=//p' "$EVIDENCE/package.txt" | grep -Fx "$EXPECTED_VERSION"
adb shell dumpsys activity activities > "$EVIDENCE/activity.txt"
grep -E "(mResumedActivity|topResumedActivity).*${package}/.*MainActivity" "$EVIDENCE/activity.txt"
adb shell uiautomator dump /sdcard/verify-doctor.xml
adb pull /sdcard/verify-doctor.xml "$EVIDENCE/doctor.xml"
python3 - "$EVIDENCE/doctor.xml" "$package" <<'PYDOCTOR'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert any(n.get("package") == sys.argv[2] for n in root.iter("node"))
PYDOCTOR
printf 'DOCTOR READY: %s %s\n' "$package" "$EXPECTED_VERSION"
