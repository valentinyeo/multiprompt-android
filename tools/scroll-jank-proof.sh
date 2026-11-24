#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/scroll-jank
rm -rf "$out"
mkdir -p "$out"
"${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.ScrollJankTest
result=$(find app/build/outputs -type d -name scroll-jank -print -quit)
test -n "$result"
cp "$result"/*.json "$out/"
python3 -B tools/scroll-jank/report.py "$out" | tee "$out/report.md"
