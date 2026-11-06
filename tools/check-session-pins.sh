#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "${GITHUB_ACTIONS:-}" = true
file=app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt
theme=app/src/main/java/dev/multiprompt/companion/ui/Theme.kt
bubble=app/src/main/java/dev/multiprompt/companion/ui/ReplyableBubble.kt
actions=app/src/androidTest/java/dev/multiprompt/companion/ui/MessageActionsTest.kt
base=build/session-rows/pins
mkdir -p "$base"
cp "$file" "$base/current.kt"
cp "$theme" "$base/current-theme.kt"
cp "$bubble" "$base/current-bubble.kt"
cp "$actions" "$base/current-actions.kt"
restore() { cp "$base/current.kt" "$file"; cp "$base/current-theme.kt" "$theme"; cp "$base/current-bubble.kt" "$bubble"; cp "$base/current-actions.kt" "$actions"; }
trap restore EXIT
git show "93f137d8adc6bd8993758e8564a8430612c06c4d:$file" > "$base/original.kt"
git show "93f137d8adc6bd8993758e8564a8430612c06c4d:$theme" > "$base/original-theme.kt"
git show "93f137d8adc6bd8993758e8564a8430612c06c4d:$bubble" > "$base/original-bubble.kt"
git show "bd3c1b86:$actions" > "$base/original-actions.kt"

run_pins() {
  local phase=$1 status=0 dir
  rm -rf "$base/$phase" \
    app/build/outputs/connected_android_test_additional_output \
    app/build/outputs/androidTest-results/connected
  mkdir -p "$base/$phase"
  "${GRADLE:-gradle}" --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.multiprompt.companion.ui.SessionPinsTest \
    > "$base/$phase/run.log" 2>&1 || status=$?
  cat "$base/$phase/run.log"
  for dir in app/build/outputs/androidTest-results/connected app/build/outputs/connected_android_test_additional_output; do
    if test -d "$dir"; then
      find "$dir" -type f \( -name '*.xml' -o -name '*.png' \) \
        -exec cp --parents '{}' "$base/$phase/" \;
    fi
  done
  printf '%s\n' "$status" > "$base/$phase/status"
}

verify_pins() {
  python3 - "$base/$1" "$1" <<'PYTHON'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
phase = sys.argv[2]
status = int((root / "status").read_text().strip())
assert (status != 0 if phase == "before" else status == 0), status
xmls = list(root.rglob("*.xml"))
assert xmls, "No XML results"
cases = []
for path in xmls:
    cases.extend(ET.parse(path).iter("testcase"))
expected = {"readerNormal", "readerReversed", "material3Normal", "material3Reversed"}
assert len(cases) == 4, [(c.get("classname"), c.get("name")) for c in cases]
assert {c.get("name") for c in cases} == expected
assert all(c.get("classname") == "dev.multiprompt.companion.ui.SessionPinsTest" for c in cases)
for case in cases:
    failures = case.findall("failure")
    errors = case.findall("error")
    skipped = case.findall("skipped")
    assert not errors and not skipped, case.get("name")
    if phase == "before":
        assert len(failures) == 1, case.get("name")
        failure = failures[0]
        text = " ".join(failure.itertext()) + " " + " ".join(failure.attrib.values())
        assert "PINNED_STRIP_MUST_NOT_SHOW" in text, (case.get("name"), text)
    else:
        assert not failures, case.get("name")
if phase == "after":
    pngs = [p for p in root.rglob("*.png") if "session-pins" in p.parts]
    expected_pngs = {
        f"{case}-{stage}.png"
        for case in ("reader-normal", "reader-reversed", "m3-normal", "m3-reversed")
        for stage in ("top", "newest")
    }
    assert len(pngs) == 8 and {p.name for p in pngs} == expected_pngs, pngs
    assert all(p.stat().st_size > 0 for p in pngs), pngs
PYTHON
}

cp "$base/original.kt" "$file"
cp "$base/original-theme.kt" "$theme"
cp "$base/original-bubble.kt" "$bubble"
cp "$base/original-actions.kt" "$actions"
run_pins before
restore
run_pins after
verify_pins before
echo SESSION_PINS_ORIGINAL_REJECTED
verify_pins after
echo SESSION_PINS_STRIP_GONE
