#!/usr/bin/env python3
import os
import subprocess
import sys
import time
from pathlib import Path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Terminal proof runs only on the GitHub runner")
step, evidence = sys.argv[1], Path(sys.argv[2])
errors = {
    "ready": "Interactive Bash prompt did not appear",
    "typed": "Typed echo did not produce exactly one TYPED_42 output line",
    "sleep": "Typed sleep 300 did not start a real sleep process",
    "interrupt": "Ctrl+C did not produce ^C, a fresh prompt and terminate sleep 300 within 5 seconds",
    "history": "Up twice and Enter did not produce a second TYPED_42 output line",
    "tab": "Tab and Enter did not list /tmp/verify_tabfile_unique.txt",
}
deadline = time.monotonic() + 5
while True:
    pane = subprocess.check_output(
        ["sudo", "-u", "verifyssh", "tmux", "capture-pane", "-p", "-t", "verify-session"],
        text=True,
    )
    (evidence / f"shell-input-{step}.txt").write_text(pane)
    lines = pane.splitlines()
    prompt = pane.rstrip().endswith("VERIFY_SHELL>")
    passed = False
    if step == "ready":
        passed = prompt
    elif step == "typed":
        passed = lines.count("TYPED_42") == 1 and prompt
    elif step in ("sleep", "interrupt"):
        process = subprocess.run(["pgrep", "-f", "sleep 300"], capture_output=True, text=True)
        (evidence / f"shell-input-{step}-pgrep.txt").write_text(process.stdout)
        if process.returncode not in (0, 1):
            raise SystemExit(f"pgrep failed: {process.stderr}")
        passed = process.returncode == 0 if step == "sleep" else (
            "^C" in pane and prompt and process.returncode == 1
        )
    elif step == "history":
        passed = lines.count("TYPED_42") == 2 and prompt
    elif step == "tab":
        passed = "/tmp/verify_tabfile_unique.txt" in lines and prompt
    if passed:
        message = f"SHELL_INPUT_{step.upper()}_OK"
        print(message)
        with (evidence / "shell-input-result.txt").open("a") as stream:
            stream.write(message + "\n")
        break
    if time.monotonic() >= deadline:
        raise SystemExit(errors[step])
    time.sleep(0.2)
