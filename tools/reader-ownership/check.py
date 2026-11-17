import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "tools/reader-ownership/baseline.json"
READ = re.compile(r"\.\s*(streamSession|streamChatHistory|streamTranscript)\s*\(")


def source_files(root=ROOT, ref=None):
    if ref:
        paths = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref, "app/src/main/java"], cwd=root, text=True).splitlines()
        return {p: subprocess.check_output(["git", "show", f"{ref}:{p}"], cwd=root, text=True) for p in paths if p.endswith(".kt")}
    return {p.relative_to(root).as_posix(): p.read_text() for p in (root / "app/src/main/java").rglob("*.kt")}


def violations(files, baseline):
    failures = []
    for path, source in files.items():
        if "class ChatHistoryConnection" not in source and re.search(r"\bChatHistoryConnection\s*\(", source) and not path.endswith("/reader/SessionReaderConnection.kt"):
            failures.append(f"{path}: do not construct a second history owner; use SessionReaderConnection.chatHistory")
        if not path.endswith(("/MainViewModel.kt", "/reader/SessionReaderConnection.kt")) and re.search(r"\bSessionReaderConnection\s*\(", source):
            failures.append(f"{path}: session owners belong in MainViewModel's warm reader map; screens acquire an observer lease")
        if "/ui/" in path and re.search(r"\.\s*(readChatHistory|captureSession)\s*\(", source):
            failures.append(f"{path}: screen-owned reads are forbidden; use SessionReaderConnection")
        counts = Counter(READ.findall(source))
        allowed = baseline.get(path, {})
        for method, count in counts.items():
            if count > allowed.get(method, 0):
                failures.append(f"{path}: {count} {method} read sites exceed {allowed.get(method, 0)}; route reads through SessionReaderConnection and ChatPollGate, not a screen loop")
    reader = "app/src/main/java/dev/multiprompt/companion/reader/"
    history = reader + "ChatHistoryConnection.kt"
    if re.search(r"\b(?:command|queued|it)\.result\.complete\(MessageActionResult\.Unknown\)", files.get(history, "")):
        failures.append(f"{history}: use Command.Action.interrupted(generation); canceled actions not yet issued must be rejected, not reported as unknown")
    requirements = {
        reader + "SessionReaderConnection.kt": [
            r"private\s+val\s+pollGate\s*=\s*ChatPollGate\(",
            r"fun\s+observe\(\)\s*:\s*AutoCloseable\s*=\s*pollGate\.acquire\(",
            r"pollGate\s*=\s*pollGate",
            r"wanted\s*=\s*pollGate\.active",
            r"pollGate\.close\(\)",
        ],
        reader + "ChatHistoryConnection.kt": [
            r"pollGate\.onChange\(",
            r"job\s*=\s*pollGate\.launchIn\(scope\)",
            r"if\s*\(!selected\)\s*\{\s*wake\.receive\(\)",
        ],
        "app/src/main/java/dev/multiprompt/companion/ssh/TmuxCommands.kt": [
            r"trap 'exit 0' HUP INT TERM PIPE",
            r"trap 'rm -f .*' EXIT;",
        ],
        "app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt": [
            r"DisposableEffect\(connection,\s*voiceLifecycle\)",
            r"Lifecycle\.Event\.ON_STOP\s*->\s*\{\s*readerLease\?\.close\(\)\s*readerLease\s*=\s*null",
            r"Lifecycle\.Event\.ON_START\s*->\s*\{\s*readerLease\s*=\s*readerLease\s*\?:\s*connection\.observe\(\)",
            r"onDispose\s*\{\s*voiceLifecycle\.lifecycle\.removeObserver\(observer\)\s*readerLease\?\.close\(\)",
        ],
    }
    for path, patterns in requirements.items():
        source = files.get(path, "")
        for pattern in patterns:
            if not re.search(pattern, source):
                failures.append(f"{path}: missing selected-session ownership; route connection, visibility and disposal through ChatPollGate")
                break
    return failures


def baseline_growth(current, previous):
    return [f"{p}: baseline cannot admit new {method} readers; use SessionReaderConnection" for p, methods in current.items() for method, count in methods.items() if count > previous.get(p, {}).get(method, 0)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref")
    args = parser.parse_args()
    baseline = json.loads((ROOT / BASELINE).read_text())
    failures = violations(source_files(ref=args.ref), baseline)
    base = os.environ.get("BASE_SHA") or "origin/" + os.environ.get("GITHUB_BASE_REF", "main")
    if base and not args.ref:
        exists = subprocess.run(["git", "cat-file", "-e", f"{base}:{BASELINE}"], cwd=ROOT, capture_output=True).returncode == 0
        if exists:
            previous = json.loads(subprocess.check_output(["git", "show", f"{base}:{BASELINE}"], cwd=ROOT, text=True))
            failures += baseline_growth(baseline, previous)
    if failures:
        raise SystemExit("\n".join("HIDDEN_READER_IO: " + failure for failure in failures))
    print("READER_OWNERSHIP_OK: two read implementations share the session lease contract")


if __name__ == "__main__":
    main()
