from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
MAIN = "app/src/main/java/dev/multiprompt/companion/"
OWNER = MAIN + "reader/ChatHistory.kt"
IDENTITY = MAIN + "reader/MessageIdentity.kt"
EDGE = MAIN + "reader/LegacyMessageIdentity.kt"
BASELINE = "tools/message-projection/baseline.json"
IDENTITY_BASELINE = "tools/message-projection/identity-baseline.json"
TEXT_SCOPE = (MAIN + "reader/", MAIN + "ui/ChatHistoryList.kt")
KEY_LITERALS = ('"@shared:', '"@held:', '"@local:', '"@live:', '"@unknown:', '"transcript:${', '"block:${', '"$sid:$inode:')
TEXT_FIELDS = (".text", "identityText")
NULLISH = {"null", '""', "''", "JSONObject.NULL"}
COMPARISON = re.compile(r"[!=]=")
LEFT_OPERAND = re.compile(r"[\w.\]\)]+$")
RIGHT_OPERAND = re.compile(r"[\w.\[\]()]+")
PROJECTION_RULES = {"independent-row-constructor", "parallel-projections", "lossy-message-identity", "display-text-matching"}
IDENTITY_RULES = {"independent-key", "raw-text-match", "legacy-bypass", "untyped-matcher"}
PROJECTION_MESSAGE = ("MESSAGE_PROJECTION_DUPLICATE: {found}. Reconcile it in ChatHistory.projectMessages; "
                      "do not append an independent echo.")
IDENTITY_MESSAGE = ("MESSAGE_IDENTITY_BYPASSED: {found}. Take every chat row key from MessageIdentity "
                    "(key/shared/held/local/live/unknown/unread) and compare message text with "
                    "MessageIdentity.same on identityText values; delete the local key string or raw text comparison.")


def rule(key):
    return key.split(": ", 1)[-1]


def text_equalities(line):
    hits = 0
    for match in COMPARISON.finditer(line):
        left = LEFT_OPERAND.search(line[:match.start()].rstrip())
        right = RIGHT_OPERAND.match(line[match.end():].strip())
        if left is None or right is None:
            continue
        pair = (left.group(0), right.group(0).rstrip(")]"))
        if pair[0] in NULLISH or pair[1] in NULLISH:
            continue
        if any(operand.endswith(TEXT_FIELDS) for operand in pair):
            hits += 1
    return hits


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
        if name == OWNER and re.search(r"\.replace\(\s*\"\\r\"|\.trimEnd\(", source):
            found[f"{name}: display-text-matching"] += 1
        if name == EDGE and re.search(r"fun\s+same\s*\([^)]*:\s*String", source):
            found[f"{name}: untyped-matcher"] += 1
        if name not in (IDENTITY, EDGE):
            keys = sum(source.count(literal) for literal in KEY_LITERALS)
            if keys:
                found[f"{name}: independent-key"] += keys
            legacy = source.count("LegacyMessageIdentity.")
            if legacy:
                found[f"{name}: legacy-bypass"] += legacy
        if any(name.startswith(scope) for scope in TEXT_SCOPE) and name not in (IDENTITY, EDGE):
            lines = source.splitlines()
            raw = sum(text_equalities(line) for line in lines) + source.count(".text.hashCode()") + source.count(".text.equals(")
            if raw:
                found[f"{name}: raw-text-match"] += raw
    return found


def load(root, ref):
    if ref:
        names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref, "app/src/main"], cwd=root, text=True).splitlines()
        return {name: subprocess.check_output(["git", "show", f"{ref}:{name}"], cwd=root, text=True) for name in names if name.endswith(".kt")}
    return {str(path.relative_to(root)): path.read_text() for path in (root / "app/src/main").rglob("*.kt")}


def budget(root, path):
    allowed = Counter(json.loads((root / path).read_text()))
    base = subprocess.run(["git", "show", f"origin/main:{path}"], cwd=root, capture_output=True, text=True)
    if base.returncode == 0 and allowed - Counter(json.loads(base.stdout)):
        raise ValueError(f"MESSAGE_IDENTITY_BYPASSED: {path} may only shrink. Reconcile the allowance with the path it covers.")
    return allowed


def check(root=ROOT, ref=None):
    files = load(root, ref)
    found = violations(files)
    allowed = budget(root, BASELINE) + budget(root, IDENTITY_BASELINE)
    new = found - allowed
    bypass = Counter({key: count for key, count in new.items() if rule(key) in IDENTITY_RULES})
    projection = new - bypass
    if bypass:
        raise ValueError(IDENTITY_MESSAGE.format(found="; ".join(sorted(bypass))))
    if projection:
        raise ValueError(PROJECTION_MESSAGE.format(found="; ".join(sorted(projection))))
    print("MESSAGE_PROJECTION_OK: old paths " + json.dumps({k: v for k, v in found.items() if rule(k) in PROJECTION_RULES}))
    print("MESSAGE_IDENTITY_OK: old paths " + json.dumps({k: v for k, v in found.items() if rule(k) in IDENTITY_RULES}))


if __name__ == "__main__":
    try:
        check(ref=sys.argv[1] if len(sys.argv) > 1 else None)
    except ValueError as error:
        sys.exit(str(error))
