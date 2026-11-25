#!/usr/bin/env bash
set -euo pipefail
: "${EVIDENCE:?}"
: "${ANDROID_KEYSTORE_BASE64:?}"
: "${ANDROID_KEYSTORE_PASSWORD:?}"
: "${ANDROID_KEY_ALIAS:?}"
: "${ANDROID_KEY_PASSWORD:?}"
case "${RELEASE_CHANNEL:-android-latest}" in
    android-latest)
        package=dev.multiprompt.companion
        task=assembleRelease
        apk=app/build/outputs/apk/release/app-release.apk
        suffix=
        manifest_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json
        apk_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion.apk
        ;;
    android-m3-latest)
        [[ ( "${VERIFY_FEATURE:-}" == settings-navigation.md || "${VERIFY_FEATURE:-}" == chat-resync.md || "${VERIFY_FEATURE:-}" == chat-shared.md || "${VERIFY_FEATURE:-}" == session-favorites.md || "${VERIFY_FEATURE:-}" == chat-queued.md || "${VERIFY_FEATURE:-}" == session-routing.md ) && -z "${APP_REF:-}" ]]
        package=dev.multiprompt.companion.m3
        task=assembleM3
        apk=app/build/outputs/apk/m3/app-m3.apk
        suffix=-m3
        manifest_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3-update.json
        apk_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3.apk
        ;;
    *) printf 'Unknown release channel\n' >&2; exit 2 ;;
esac
mkdir -p "$RUNNER_TEMP/release-choices/classes" "$RUNNER_TEMP/release-choices/dex" "$EVIDENCE"
work="$RUNNER_TEMP/release-choices"
tools="$ANDROID_HOME/build-tools/35.0.0"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$work/signing.jks"
app_apk="$EVIDENCE/app.apk"
if [[ -n "${CANDIDATE_APK:-}" ]]; then
    [[ "$EXPECTED_COMMIT" == "$GITHUB_SHA" && "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]]
    app_apk=$CANDIDATE_APK
    test -s "$app_apk"
    printf '%s\n' "$GITHUB_SHA" > "$EVIDENCE/app-source-sha.txt"
    printf '%s\n' "${EXPECTED_VERSION:?}" > "$EVIDENCE/version.txt"
elif [[ "${CANDIDATE:-false}" == true ]]; then
    app_source="$GITHUB_WORKSPACE"
    if [[ -n "${APP_REF:-}" ]]; then
        [[ "$APP_REF" =~ ^[0-9a-f]{40}$ ]]
        app_source="$GITHUB_WORKSPACE/app-source"
        [[ "$(git -C "$app_source" rev-parse HEAD)" == "$APP_REF" ]]
    fi
    source_sha=$(git -C "$app_source" rev-parse HEAD)
    if [[ "${VERIFY_FEATURE:-}" == settings-navigation.md ]]; then
        [[ "${EXPECTED_COMMIT:-$source_sha}" == "$source_sha" && "$source_sha" =~ ^[0-9a-f]{40}$ ]]
    fi
    cat > "$app_source/keystore.properties" <<EOF
storeFile=$work/signing.jks
storePassword=$ANDROID_KEYSTORE_PASSWORD
keyAlias=$ANDROID_KEY_ALIAS
keyPassword=$ANDROID_KEY_PASSWORD
EOF
    version_code=1000000000
    if [[ "${VERIFY_FEATURE:-}" == update-card.md ]]; then version_code=1; fi
    tools/ci/gradle-retry.sh -p "$app_source" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g "-PversionCode=$version_code" -PversionName=0.1.2-candidate "$task"
    cp "$app_source/$apk" "$app_apk"
    printf '%s\n' "$source_sha" > "$EVIDENCE/app-source-sha.txt"
    printf '%s\n' "0.1.2-candidate$suffix" > "$EVIDENCE/version.txt"
else
    curl -fsSL "$manifest_url" -o "$EVIDENCE/manifest.json"
    [[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]]
    jq -e --arg commit "$EXPECTED_COMMIT" --arg apk_url "$apk_url" '.schemaVersion == 1 and .commit == $commit and .sizeBytes > 0 and (.sha256 | length) == 64 and .apkUrl == $apk_url' "$EVIDENCE/manifest.json"
    curl -fsSL "$(jq -er '.apkUrl' "$EVIDENCE/manifest.json")" -o "$app_apk"
    printf '%s  %s\n' "$(jq -er '.sha256' "$EVIDENCE/manifest.json")" "$app_apk" | sha256sum --check
    test "$(stat -c%s "$app_apk")" = "$(jq -er '.sizeBytes' "$EVIDENCE/manifest.json")"
    if [[ "${VERIFY_FEATURE:-}" == session-routing.md && "${RELEASE_CHANNEL:-}" == android-m3-latest && "$EXPECTED_COMMIT" == 056ee39de07940f27c16123b3652ae4f3017bf5a ]]; then
        [[ "$(jq -er '.sha256' "$EVIDENCE/manifest.json")" == d70eb97be6c633198058db524e4b2d336d7aa818a5cddf1b46ea1fa6c2e57a42 ]]
    fi
    jq -er '.commit' "$EVIDENCE/manifest.json" > "$EVIDENCE/app-source-sha.txt"
    jq -er '.versionName' "$EVIDENCE/manifest.json" > "$EVIDENCE/version.txt"
fi
sha256sum "$app_apk" > "$EVIDENCE/app-apk-sha256.txt"
stat -c%s "$app_apk" > "$EVIDENCE/app-apk-size.txt"
test "$(cat "$EVIDENCE/app-apk-size.txt")" -gt 0
"$tools/apksigner" verify --print-certs "$app_apk" > "$EVIDENCE/apk-certificate.txt"
"$tools/aapt" dump badging "$app_apk" > "$EVIDENCE/apk-package.txt"
grep -F "package: name='$package'" "$EVIDENCE/apk-package.txt"
if [[ "${VERIFY_FEATURE:-}" == session-routing.md || "${VERIFY_FEATURE:-}" == settings-navigation.md ]]; then
    grep -F "versionName='$(cat "$EVIDENCE/version.txt")'" "$EVIDENCE/apk-package.txt"
fi
printf '%s\n' "$package" > "$EVIDENCE/package-name.txt"
cat > "$work/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.multiprompt.verification">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
    <application android:label="Release fixture seed" />
    <instrumentation android:name="dev.multiprompt.verification.Seed" android:targetPackage="$package" />
</manifest>
XML
observer=()
if [[ "${VERIFY_FEATURE:-}" == chat-working.md || "${VERIFY_FEATURE:-}" == long-chat.md || "${VERIFY_FEATURE:-}" == bubble-width.md ]]; then
    observer=(tools/working-dots/Observe.java)
    if [[ "$VERIFY_FEATURE" == long-chat.md ]]; then observer=(tools/long-chat/Observe.java); fi
    if [[ "$VERIFY_FEATURE" == bubble-width.md ]]; then observer=(tools/bubble-width/Observe.java); fi
    sed -i "s|</manifest>|<instrumentation android:name=\"dev.multiprompt.verification.Observe\" android:targetPackage=\"$package\" /></manifest>|" "$work/AndroidManifest.xml"
fi
javac -source 8 -target 8 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$work/classes" "${SEED_SOURCE:-tools/release-choices/Seed.java}" "${observer[@]}"
"$tools/d8" --lib "$ANDROID_HOME/platforms/android-35/android.jar" --output "$work/dex" "$work/classes/dev/multiprompt/verification/"*.class
"$tools/aapt" package -f -M "$work/AndroidManifest.xml" -I "$ANDROID_HOME/platforms/android-35/android.jar" -F "$work/seed-unsigned.apk"
(cd "$work/dex" && zip -q "$work/seed-unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$work/seed-unsigned.apk" "$work/seed-aligned.apk"
"$tools/apksigner" sign --ks "$work/signing.jks" --ks-key-alias "$ANDROID_KEY_ALIAS" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD --out "$work/seed.apk" "$work/seed-aligned.apk"
"$tools/apksigner" verify --print-certs "$work/seed.apk" > "$EVIDENCE/seed-certificate.txt"
grep -Eq '^Signer #1 certificate SHA-256 digest: [0-9a-fA-F]{64}$' "$EVIDENCE/apk-certificate.txt"
grep -Eq '^Signer #1 certificate SHA-256 digest: [0-9a-fA-F]{64}$' "$EVIDENCE/seed-certificate.txt"
diff <(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$EVIDENCE/apk-certificate.txt") <(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$EVIDENCE/seed-certificate.txt")
rm "$work/signing.jks"
rm -f "${app_source:-$GITHUB_WORKSPACE}/keystore.properties"
