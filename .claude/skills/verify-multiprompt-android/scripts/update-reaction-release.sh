#!/usr/bin/env bash
set -euo pipefail
: "${EVIDENCE:?Set evidence directory}"
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "$(adb get-serialno)" == emulator-* ]]
baseline="$EVIDENCE/signed-update-baseline"
out="$EVIDENCE/signed-update"
mkdir -p "$out"
curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o "$out/manifest.json"
python3 - "$baseline/manifest.json" "$out/manifest.json" "$EVIDENCE/app-source-sha.txt" <<'PY'
import json
from pathlib import Path
import sys
old, new = [json.loads(Path(name).read_text()) for name in sys.argv[1:3]]
assert new['schemaVersion'] == 1 and new['commit'] == Path(sys.argv[3]).read_text().strip(), new
assert new['versionCode'] > old['versionCode'], (old, new)
assert new['apkUrl'].startswith('https://github.com/valentinyeo/multiprompt-android/releases/download/'), new
assert len(new['sha256']) == 64 and new['sizeBytes'] > 0, new
PY
printf '%s  %s\n' "$(jq -er '.sha256' "$baseline/manifest.json")" "$baseline/app.apk" | sha256sum --check
curl -fsSL "$(jq -er '.apkUrl' "$out/manifest.json")" -o "$out/app.apk"
printf '%s  %s\n' "$(jq -er '.sha256' "$out/manifest.json")" "$out/app.apk" | sha256sum --check
test "$(stat -c%s "$out/app.apk")" = "$(jq -er '.sizeBytes' "$out/manifest.json")"
build_tools="$ANDROID_HOME/build-tools/35.0.0"
"$build_tools/apksigner" verify --print-certs "$baseline/app.apk" > "$out/baseline-certificate.txt"
"$build_tools/apksigner" verify --print-certs "$out/app.apk" > "$out/release-certificate.txt"
old_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$out/baseline-certificate.txt")
new_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$out/release-certificate.txt")
test -n "$new_cert"
test "$old_cert" = "$new_cert"
"$build_tools/aapt" dump badging "$out/app.apk" > "$out/apk-package.txt"
python3 - "$out/manifest.json" "$out/apk-package.txt" <<'PY'
import json
from pathlib import Path
import re
import sys
manifest = json.loads(Path(sys.argv[1]).read_text())
package = Path(sys.argv[2]).read_text().splitlines()[0]
assert "name='dev.multiprompt.companion'" in package, package
assert int(re.search(r"versionCode='(\d+)'", package)[1]) == manifest['versionCode'], package
assert re.search(r"versionName='([^']+)'", package)[1] == manifest['versionName'], package
PY
adb install -r -g "$baseline/app.apk"
adb shell am start -W -n dev.multiprompt.companion/dev.multiprompt.companion.MainActivity
adb shell pm grant dev.multiprompt.companion android.permission.POST_NOTIFICATIONS
version=$(jq -er '.versionName' "$out/manifest.json")
for attempt in {1..30}; do
    adb shell dumpsys notification --noredact > "$out/notifications.txt"
    if grep -F "multiprompt $version is ready" "$out/notifications.txt" >/dev/null; then break; fi
    adb shell dumpsys jobscheduler > "$out/jobs.txt"
    python3 - "$out/jobs.txt" > "$out/job-ids.txt" <<'PY'
from pathlib import Path
import re
import sys
for value in re.findall(r'JOB #\w+/(\d+):[^\n]*dev\.multiprompt\.companion/androidx\.work\.impl\.background\.systemjob\.SystemJobService', Path(sys.argv[1]).read_text()):
    print(value)
PY
    while read -r id; do
        [[ "$id" =~ ^[0-9]+$ ]]
        adb shell cmd jobscheduler run -f dev.multiprompt.companion "$id" || true
    done < "$out/job-ids.txt"
    sleep 2
done
grep -F "multiprompt $version is ready" "$out/notifications.txt"
adb shell cmd statusbar expand-notifications
adb shell uiautomator dump /sdcard/update-offer.xml >/dev/null
adb pull /sdcard/update-offer.xml "$out/update-offer.xml"
adb exec-out screencap -p > "$out/update-offer.png"
python3 - "$out/update-offer.xml" "$version" <<'PY'
import sys
import xml.etree.ElementTree as ET
nodes = ET.parse(sys.argv[1]).getroot().iter('node')
assert any('multiprompt ' + sys.argv[2] + ' is ready' in node.get('text', '') for node in nodes)
PY
adb shell cmd statusbar collapse
adb install -r -g "$out/app.apk"
adb shell am start -W -n dev.multiprompt.companion/dev.multiprompt.companion.MainActivity
adb shell dumpsys package dev.multiprompt.companion > "$out/installed-package.txt"
adb shell uiautomator dump /sdcard/update-installed.xml >/dev/null
adb pull /sdcard/update-installed.xml "$out/update-installed.xml"
adb exec-out screencap -p > "$out/update-installed.png"
python3 - "$out" "$new_cert" <<'PY'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
manifest = json.loads((root/'manifest.json').read_text())
installed = (root/'installed-package.txt').read_text()
assert 'versionName=' + manifest['versionName'] in installed
assert 'versionCode=' + str(manifest['versionCode']) in installed
(root/'result.json').write_text(json.dumps(dict(status='passed', offered=True, installed=True,
    commit=manifest['commit'], versionName=manifest['versionName'], versionCode=manifest['versionCode'],
    sha256=manifest['sha256'], certificateSha256=sys.argv[2]), indent=2))
PY
printf 'SIGNED_ANDROID_UPDATE_OFFERED_AND_PACKAGE_VERIFIED\n'
