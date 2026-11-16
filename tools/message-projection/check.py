from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OWNER = "app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt"
EDGE = "app/src/main/java/dev/multiprompt/companion/reader/LegacyMessageIdentity.kt"
BASELINE = "tools/message-projection/baseline.json"


def violations(files):
    found = Counter()
    for name, source in files.items():
        if not name.startswith("app/src/main/") or not name.endswith(".kt"):
            continue
        if name != OWNER and re.search(r"\bChatHistoryMessage\s*\(", source):
            found[f"{name}: independent-row-constructor"] += len(re.findall(r"\bChatHistoryMessage\s*\(", source))
        if name == OWNER and re.search(r"\bfun\s+(?:managedMessages|displayMessages)\b", source):
            found[f"{name}: parallel-projections"] += 1
        if name == OWNER and ".hashCode()" in source:
            found[f"{name}: lossy-message-identity"] += 1
        if name != EDGE:
            for call in re.findall(r"LegacyMessageIdentity\.(shared|delivered|reply)\(", source):
                found[f"{name}: legacy-{call}"] += 1
            if name == OWNER and re.search(r"\.replace\(\s*\"\\r\"|\.trimEnd\(", source):
                found[f"{name}: display-text-matching"] += 1
    return found


def check(root=ROOT, ref=None):
    if ref:
        names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref, "app/src/main"], cwd=root, text=True).splitlines()
        files = {name: subprocess.check_output(["git", "show", f"{ref}:{name}"], cwd=root, text=True) for name in names if name.endswith(".kt")}
    else:
        files = {str(path.relative_to(root)): path.read_text() for path in (root / "app/src/main").rglob("*.kt")}
    baseline = Counter(json.loads((root / BASELINE).read_text()))
    base = subprocess.run(["git", "show", f"origin/main:{BASELINE}"], cwd=root, capture_output=True, text=True)
    if base.returncode == 0 and baseline - Counter(json.loads(base.stdout)):
        raise ValueError("MESSAGE_PROJECTION_DUPLICATE: baseline may only shrink; reconcile the new path in ChatHistory.projectMessages.")
    new = violations(files) - baseline
    if new:
        raise ValueError("MESSAGE_PROJECTION_DUPLICATE: " + "; ".join(sorted(new)) + ". Reconcile it in ChatHistory.projectMessages; do not append an independent echo. Legacy text matching belongs at LegacyMessageIdentity only.")
    print("MESSAGE_PROJECTION_OK: old paths " + json.dumps(dict(violations(files) & baseline)))


if __name__ == "__main__":
    try:
        check(ref=sys.argv[1] if len(sys.argv) > 1 else None)
    except ValueError as error:
        sys.exit(str(error))
