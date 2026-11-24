#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
rounds=${ROUNDS:-.bisect/rounds.json}
apks=${APKS:-bisect-apks}
mkdir -p "$apks"
: > "$apks/apk-sha256.txt"
index=0
for sha in $(python3 -B tools/bisect-mubo-453/plan.py "$rounds"); do
    index=$((index + 1))
    source="bisect-source/$sha"
    if [ ! -d "$source" ]; then
        git worktree add --detach "$source" "$sha"
    fi
    if [ "$index" -eq 1 ]; then
        tools/ci/gradle-retry.sh -p "$source" -Dorg.gradle.jvmargs=-Xmx3g -PversionName="0.1.0-bisect" -PversionCode=1 assembleDebug assembleDebugAndroidTest
    else
        tools/ci/gradle-retry.sh -p "$source" -Dorg.gradle.jvmargs=-Xmx3g -PversionName="0.1.0-bisect" -PversionCode=1 assembleDebug
    fi
    mkdir -p "$apks/$sha"
    cp "$source/app/build/outputs/apk/debug/app-debug.apk" "$apks/$sha/app-debug.apk"
    if [ "$index" -eq 1 ]; then
        cp "$source/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$apks/$sha/app-debug-androidTest.apk"
    fi
    sha256sum "$apks/$sha/app-debug.apk" >> "$apks/apk-sha256.txt"
done
