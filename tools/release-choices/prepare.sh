#!/usr/bin/env bash
set -euo pipefail
: "${EVIDENCE:?}"
: "${ANDROID_KEYSTORE_BASE64:?}"
: "${ANDROID_KEYSTORE_PASSWORD:?}"
: "${ANDROID_KEY_ALIAS:?}"
: "${ANDROID_KEY_PASSWORD:?}"
mkdir -p "$RUNNER_TEMP/release-choices/classes" "$RUNNER_TEMP/release-choices/dex" "$EVIDENCE"
work="$RUNNER_TEMP/release-choices"
tools="$ANDROID_HOME/build-tools/35.0.0"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$work/signing.jks"
if [[ "${CANDIDATE:-false}" == true ]]; then
    cat > keystore.properties <<EOF
storeFile=$work/signing.jks
storePassword=$ANDROID_KEYSTORE_PASSWORD
keyAlias=$ANDROID_KEY_ALIAS
keyPassword=$ANDROID_KEY_PASSWORD
EOF
    gradle --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g -PversionCode=1000000000 -PversionName=0.1.2-candidate assembleRelease
    cp app/build/outputs/apk/release/app-release.apk "$EVIDENCE/app.apk"
    printf '%s\n' "$GITHUB_SHA" > "$EVIDENCE/app-source-sha.txt"
    printf '%s\n' 0.1.2-candidate > "$EVIDENCE/version.txt"
else
    curl -fsSL https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json -o "$EVIDENCE/manifest.json"
    jq -e --arg commit "$EXPECTED_COMMIT" '.schemaVersion == 1 and .commit == $commit and .sizeBytes > 0 and (.sha256 | length) == 64 and .apkUrl == "https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion.apk"' "$EVIDENCE/manifest.json"
    curl -fsSL "$(jq -er '.apkUrl' "$EVIDENCE/manifest.json")" -o "$EVIDENCE/app.apk"
    printf '%s  %s\n' "$(jq -er '.sha256' "$EVIDENCE/manifest.json")" "$EVIDENCE/app.apk" | sha256sum --check
    test "$(stat -c%s "$EVIDENCE/app.apk")" = "$(jq -er '.sizeBytes' "$EVIDENCE/manifest.json")"
    jq -er '.commit' "$EVIDENCE/manifest.json" > "$EVIDENCE/app-source-sha.txt"
    jq -er '.versionName' "$EVIDENCE/manifest.json" > "$EVIDENCE/version.txt"
fi
sha256sum "$EVIDENCE/app.apk" > "$EVIDENCE/app-apk-sha256.txt"
"$tools/apksigner" verify --print-certs "$EVIDENCE/app.apk" > "$EVIDENCE/apk-certificate.txt"
"$tools/aapt" dump badging "$EVIDENCE/app.apk" > "$EVIDENCE/apk-package.txt"
grep -F "package: name='dev.multiprompt.companion'" "$EVIDENCE/apk-package.txt"
cat > "$work/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.multiprompt.verification">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
    <application android:label="Release fixture seed" />
    <instrumentation android:name="dev.multiprompt.verification.Seed" android:targetPackage="dev.multiprompt.companion" />
</manifest>
XML
observer=()
if [[ "${VERIFY_FEATURE:-}" == chat-working.md || "${VERIFY_FEATURE:-}" == long-chat.md || "${VERIFY_FEATURE:-}" == bubble-width.md ]]; then
    observer=(tools/working-dots/Observe.java)
    if [[ "$VERIFY_FEATURE" == long-chat.md ]]; then observer=(tools/long-chat/Observe.java); fi
    if [[ "$VERIFY_FEATURE" == bubble-width.md ]]; then observer=(tools/bubble-width/Observe.java); fi
    sed -i 's|</manifest>|<instrumentation android:name="dev.multiprompt.verification.Observe" android:targetPackage="dev.multiprompt.companion" /></manifest>|' "$work/AndroidManifest.xml"
fi
javac -source 8 -target 8 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$work/classes" "${SEED_SOURCE:-tools/release-choices/Seed.java}" "${observer[@]}"
"$tools/d8" --lib "$ANDROID_HOME/platforms/android-35/android.jar" --output "$work/dex" "$work/classes/dev/multiprompt/verification/"*.class
"$tools/aapt" package -f -M "$work/AndroidManifest.xml" -I "$ANDROID_HOME/platforms/android-35/android.jar" -F "$work/seed-unsigned.apk"
(cd "$work/dex" && zip -q "$work/seed-unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$work/seed-unsigned.apk" "$work/seed-aligned.apk"
"$tools/apksigner" sign --ks "$work/signing.jks" --ks-key-alias "$ANDROID_KEY_ALIAS" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD --out "$work/seed.apk" "$work/seed-aligned.apk"
"$tools/apksigner" verify --print-certs "$work/seed.apk" > "$EVIDENCE/seed-certificate.txt"
diff <(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$EVIDENCE/apk-certificate.txt") <(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$EVIDENCE/seed-certificate.txt")
rm "$work/signing.jks"
rm -f keystore.properties
