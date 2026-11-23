from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SCOPE = "app/src/main/java/dev/multiprompt/companion/ssh/"
COMMANDS = SCOPE + "TmuxCommands.kt"
BASELINE = "tools/tmux-target/baseline.json"
TARGET_BODY = 'private fun target(sessionName: String): String = TmuxParser.shellQuote("${exactName(sessionName)}:")'
SESSION_BODY = 'private fun sessionTarget(sessionName: String): String = TmuxParser.shellQuote(exactName(sessionName))'


def files(root=ROOT, ref=None):
    if ref:
        names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref, "app/src/main/java/dev/multiprompt/companion/ssh"],
                                        cwd=root, text=True).splitlines()
        return {name: subprocess.check_output(["git", "show", f"{ref}:{name}"], cwd=root, text=True)
                for name in names if name.endswith(".kt")}
    folder = root / "app/src/main/java/dev/multiprompt/companion/ssh"
    return {p.relative_to(root).as_posix(): p.read_text() for p in folder.rglob("*.kt")}


def violations(sources):
    found = Counter()
    commands = sources.get(COMMANDS, "")
    if TARGET_BODY.replace(" ", "") not in commands.replace(" ", ""):
        found[f"{COMMANDS}: target-helper-missing-exact"] += 1
    if SESSION_BODY.replace(" ", "") not in commands.replace(" ", ""):
        found[f"{COMMANDS}: session-target-helper-missing-exact"] += 1
    for name, source in sources.items():
        for kind, pattern in (
            ("colon-name-quote", r'TmuxParser\.shellQuote\(\s*"\$\{?[A-Za-z_]'),
            ("inline-exact-quote", r'TmuxParser\.shellQuote\(\s*"\s*='),
        ):
            matches = re.findall(pattern, source)
            if not matches:
                continue
            for line in source.splitlines():
                if "shellQuote" not in line:
                    continue
                if kind == "colon-name-quote" and re.search(r'TmuxParser\.shellQuote\(\s*"\$', line):
                    if "exactName" in line:
                        continue
                    found[f"{name}: {kind}"] += 1
                elif kind == "inline-exact-quote" and re.search(r'TmuxParser\.shellQuote\(\s*"\s*=', line):
                    if "private fun sessionTarget" in line or "sessionTarget(sessionName)" in line:
                        continue
                    if 'shellQuote(exactName' in line or 'shellQuote("${exactName' in line:
                        continue
                    found[f"{name}: {kind}"] += 1
        for func in re.split(r"\n(?=\s*(?:private |internal )?fun )", source):
            if "has-session -t $session" in func and "TmuxParser.shellQuote(fresh)" in func and "sessionTarget(fresh)" not in func:
                found[f"{name}: has-session-name-target"] += 1
            if 'private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")' in func.replace(" ", ""):
                found[f"{name}: target-without-exact-prefix"] += 1
    return found


def check(root=ROOT, ref=None):
    baseline = Counter(json.loads((root / BASELINE).read_text()))
    base = subprocess.run(["git", "show", f"origin/main:{BASELINE}"], cwd=root, capture_output=True, text=True)
    if base.returncode == 0 and baseline - Counter(json.loads(base.stdout)):
        raise ValueError("TMUX_TARGET_UNQUOTED: baseline may only shrink; fix tmux -t selectors with sessionTarget() or target(), not ad hoc shellQuote.")
    new = violations(files(root, ref)) - baseline
    if new:
        raise ValueError(
            "TMUX_TARGET_UNQUOTED: "
            + "; ".join(sorted(f"{k} ({v})" for k, v in new.items()))
            + ". Build tmux -t selectors only through TmuxCommands.sessionTarget() or target(); has-session must use sessionTarget, not shellQuote(fresh)."
        )
    print("TMUX_TARGET_OK: " + json.dumps(dict(violations(files(root, ref)) & baseline)))


if __name__ == "__main__":
    try:
        check(ref=sys.argv[1] if len(sys.argv) > 1 else None)
    except ValueError as error:
        sys.exit(str(error))