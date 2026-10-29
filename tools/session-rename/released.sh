#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${EVIDENCE:?}" "${ANDROID_HOME:?}"
mkdir -p "$EVIDENCE"
build="$RUNNER_TEMP/rename-proof"
mkdir -p "$build/classes" "$build/dex"
curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o "$EVIDENCE/manifest.json"
python3 - "$EVIDENCE" "$build" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
from urllib.request import urlopen
proof, build = map(Path, sys.argv[1:])
manifest = json.loads((proof / 'manifest.json').read_text())
assert manifest['schemaVersion'] == 1 and manifest['versionCode'] > 100000
assert manifest['apkUrl'] == 'https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion.apk'
if os.environ.get('EXPECT_STALE') != 'true':
    assert manifest['commit'] == os.environ['GITHUB_SHA'], manifest
apk = urlopen(manifest['apkUrl']).read()
assert len(apk) == manifest['sizeBytes'] and hashlib.sha256(apk).hexdigest() == manifest['sha256']
(build / 'release.apk').write_bytes(apk)
print('PUBLISHED_APK_VERIFIED', manifest['commit'], manifest['versionName'])
PY
sdk="$ANDROID_HOME/build-tools/36.0.0"
platform="$ANDROID_HOME/platforms/android-36/android.jar"
javac -source 17 -target 17 -classpath "$platform" -d "$build/classes" tools/session-rename/RenameJourney.java
"$sdk/d8" --lib "$platform" --output "$build/dex" $(find "$build/classes" -name '*.class')
"$sdk/aapt2" link -I "$platform" --manifest tools/session-rename/AndroidManifest.xml -o "$build/proof.apk"
(cd "$build/dex" && zip -q "$build/proof.apk" classes.dex)
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$build/signing.jks"
"$sdk/apksigner" sign --ks "$build/signing.jks" --ks-pass env:KEYSTORE_PASSWORD --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASSWORD "$build/proof.apk"
rm "$build/signing.jks"
"$sdk/apksigner" verify --print-certs "$build/release.apk" > "$EVIDENCE/release-signature.txt"
"$sdk/apksigner" verify --print-certs "$build/proof.apk" > "$build/proof-signature.txt"
diff <(grep 'certificate SHA-256 digest:' "$EVIDENCE/release-signature.txt") <(grep 'certificate SHA-256 digest:' "$build/proof-signature.txt")
adb install -r -g "$build/release.apk"
adb install -r "$build/proof.apk"
adb shell svc wifi disable
adb shell svc data disable
runner=dev.multiprompt.proof/dev.multiprompt.proof.RenameJourney
adb shell am instrument -w -e phase seed "$runner" | tee "$EVIDENCE/seed.txt"
grep -F RENAME_SEED_OK "$EVIDENCE/seed.txt"
adb shell am force-stop dev.multiprompt.companion
adb shell 'screenrecord /sdcard/rename.mp4 & echo $! > /sdcard/rename.pid; wait' > "$build/record.log" 2>&1 &
record_pid=$!
cleanup() {
  kill "$record_pid" 2>/dev/null || true
  adb shell 'kill -2 "$(cat /sdcard/rename.pid)"' || true
  wait "$record_pid" 2>/dev/null || true
  adb pull /sdcard/rename.mp4 "$EVIDENCE/rename.mp4" || true
  adb pull /sdcard/Android/data/dev.multiprompt.companion/files/rename-proof/. "$EVIDENCE/" || true
  adb logcat -d > "$EVIDENCE/logcat.txt"
}
trap cleanup EXIT
adb shell am instrument -w -e phase rename "$runner" | tee "$EVIDENCE/rename.txt"
if [ "${EXPECT_STALE:-false}" = true ]; then
  grep -F STALE_INBOX_AFTER_RENAME "$EVIDENCE/rename.txt"
  echo ORIGINAL_RELEASE_STALE_RENAME_REPRODUCED
  exit 0
fi
grep -F RENAME_RENAME_OK "$EVIDENCE/rename.txt"
adb shell am force-stop dev.multiprompt.companion
adb shell am instrument -w -e phase reopened "$runner" | tee "$EVIDENCE/reopened.txt"
grep -F RENAME_REOPENED_OK "$EVIDENCE/reopened.txt"
echo RELEASED_RENAME_JOURNEY_OK
