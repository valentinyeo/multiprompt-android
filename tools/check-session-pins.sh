#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test "${GITHUB_ACTIONS:-}" = true
file=app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt
base=build/session-rows/pins
mkdir -p "$base"
cp "$file" "$base/current.kt"
restore() { cp "$base/current.kt" "$file"; }
trap restore EXIT
git show "bebcb708b7ae9338230c2c5b04fa769484f46ccd:$file" > "$base/original.kt"

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
        assert "PINNED_STRIP_MUST_SCROLL" in text, (case.get("name"), text)
    else:
        assert not failures, case.get("name")
if phase == "after":
    pngs = [p for p in root.rglob("*.png") if "session-pins" in p.parts]
    expected_pngs = {
        f"{case}-{stage}.png"
        for case in ("reader-normal", "reader-reversed", "m3-normal", "m3-reversed")
        for stage in ("top", "scrolled", "restored")
    }
    assert len(pngs) == 12 and {p.name for p in pngs} == expected_pngs, pngs
    assert all(p.stat().st_size > 0 for p in pngs), pngs
PYTHON
}

cp "$base/original.kt" "$file"
run_pins before
restore
run_pins after
verify_pins before
echo SESSION_PINS_ORIGINAL_REJECTED
verify_pins after
echo SESSION_PINS_SCROLL_OK
