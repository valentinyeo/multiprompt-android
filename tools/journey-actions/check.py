import json
from collections import Counter
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
DRIVER = ".claude/skills/verify-multiprompt-android/scripts/drive.sh"
CLI = ".claude/skills/verify-multiprompt-android/scripts/mpa"


def violations(root):
    found = Counter()
    for directory in (root / ".claude/skills/verify-multiprompt-android/scripts", root / "tools"):
        for path in directory.rglob("*"):
            if ".test." in path.name or path == root / "tools/journey-actions/check.py":
                continue
            relative = path.relative_to(root).as_posix()
            if path.suffix not in (".py", ".sh") and relative != CLI:
                continue
            source = path.read_text()
            if relative == DRIVER:
                helper = source.split("tap_node() {", 1)[-1].split("\n}", 1)[0]
                if '"reach-$1"' not in helper:
                    found[f"{relative}: tap_node bypasses reachability"] += 1
                else:
                    source = source.replace('tap) adb shell input tap "$x1" "$y1"; return ;;', "", 1)
            elif relative == CLI:
                if "from journey_action import Unreachable, plan" not in source:
                    found[f"{relative}: tap bypasses reachability"] += 1
                else:
                    source = source.replace('adb("shell", "input", "tap", coordinates[0], coordinates[1])', "", 1)
            for line in source.splitlines():
                if re.search(r"\binput\s+tap\b", line) or re.search(r"['\"]input['\"].*['\"]tap['\"]", line):
                    found[f"{relative}: {line.strip()}"] += 1
    return found


def check(root=ROOT):
    baseline_path = "tools/journey-actions/baseline.json"
    baseline = Counter(json.loads((root / baseline_path).read_text()))
    base = subprocess.run(["git", "show", f"origin/main:{baseline_path}"], cwd=root, capture_output=True, text=True)
    if base.returncode == 0:
        added = baseline - Counter(json.loads(base.stdout))
        if added:
            raise ValueError(f"JOURNEY_ACTION_UNREACHABLE: baseline may only shrink: {dict(added)}")
    new = violations(root) - baseline
    if new:
        raise ValueError("JOURNEY_ACTION_UNREACHABLE: use drive.sh tap_text/tap_desc/tap_node instead of raw taps: " + json.dumps(dict(new), indent=2))
    print(f"JOURNEY_ACTION_RATCHET_OK: {sum(violations(root).values())} legacy bypasses remain in tools/journey-actions/baseline.json")


if __name__ == "__main__":
    try:
        check()
    except ValueError as error:
        sys.exit(str(error))
