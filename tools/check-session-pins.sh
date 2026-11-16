#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "${GITHUB_ACTIONS:-}" = true
historical=93f137d8adc6bd8993758e8564a8430612c06c4d
base=build/session-rows/pins
tree="${RUNNER_TEMP}/pins-historical"
mkdir -p "$base/replay" "$base/before" "$base/after"
python3 tools/historical_control.py classify-log \
  --expected PINNED_STRIP_MUST_NOT_SHOW \
  --log tools/session-pins/igsh-2306-compile.txt \
  | tee "$base/replay/classification.txt"
grep -F 'CONTROL_INVALID:' "$base/replay/classification.txt"
grep -F 'ComposableFunction1' tools/session-pins/igsh-2306-compile.txt
echo HISTORICAL_MIXED_SOURCE_CONTROL_INVALID

rm -rf "$tree"
git worktree prune
git worktree add --detach "$tree" "$historical"
cleanup() { git worktree remove --force "$tree" 2>/dev/null || true; }
trap cleanup EXIT
if [ -n "${ANDROID_HOME:-}" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$tree/local.properties"
fi
cp tools/session-pins/HistoricalPinsOracle.kt \
  "$tree/app/src/androidTest/java/dev/multiprompt/companion/ui/HistoricalPinsOracle.kt"

collect() {
  local phase=$1 root=$2 start=$PWD
  (
    cd "$root"
    for dir in app/build/outputs/androidTest-results/connected app/build/outputs/connected_android_test_additional_output; do
      if [ -d "$dir" ]; then
        find "$dir" -type f \( -name '*.xml' -o -name '*.png' \) -exec cp --parents '{}' "$start/$base/$phase/" \;
      fi
    done
  )
}

run_connected() {
  local project=$1 class=$2 phase=$3 status=0
  rm -rf "$project/app/build/outputs/connected_android_test_additional_output" \
    "$project/app/build/outputs/androidTest-results/connected"
  "${GRADLE:-gradle}" -p "$project" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g \
    connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$class" \
    > "$base/$phase/run.log" 2>&1 || status=$?
  cat "$base/$phase/run.log"
  printf '%s\n' "$status" > "$base/$phase/status"
  collect "$phase" "$project"
}

run_connected "$tree" dev.multiprompt.companion.ui.HistoricalPinsOracle before
python3 tools/historical_control.py classify-run \
  --phase before \
  --expected PINNED_STRIP_MUST_NOT_SHOW \
  --log "$base/before/run.log" \
  --results "$base/before" \
  --status "$base/before/status"
echo SESSION_PINS_ORIGINAL_REJECTED
cleanup
trap - EXIT

run_connected . dev.multiprompt.companion.ui.SessionPinsTest after
python3 tools/historical_control.py classify-run \
  --phase after \
  --expected PINNED_STRIP_MUST_NOT_SHOW \
  --log "$base/after/run.log" \
  --results "$base/after" \
  --status "$base/after/status"
python3 - "$base/after" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
pngs = [path for path in root.rglob('*.png') if 'session-pins' in path.parts]
expected = {
    f'{case}-{stage}.png'
    for case in ('reader-normal', 'reader-reversed', 'm3-normal', 'm3-reversed')
    for stage in ('top', 'newest')
}
assert len(pngs) == 8 and {path.name for path in pngs} == expected, pngs
assert all(path.stat().st_size > 0 for path in pngs), pngs
PY
echo SESSION_PINS_STRIP_GONE
