import json
import os
from pathlib import Path
import subprocess
import time

root = Path(os.environ["EVIDENCE"])
samples = root / "visibility.txt"
events = root / "journey.jsonl"


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def event(kind, **fields):
    with events.open("a") as output:
        output.write(json.dumps(dict(event=kind, at=time.time_ns() // 1000000, **fields)) + "\n")


def shot(name):
    with (root / (name + ".png")).open("wb") as output:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=output, check=True)


def server_events():
    raw = run("sudo", "cat", "/home/verifyssh/working-events.jsonl")
    return [json.loads(line) for line in raw.splitlines()]


def auth_count():
    text = run("sudo", "cat", os.environ["FIXTURE"] + "/sshd.log")
    return text.count("Accepted publickey for verifyssh")


shot("before-turn")
subprocess.run(["adb", "logcat", "-c"], check=True)
with (root / "recording.txt").open("w") as output:
    recording = subprocess.Popen(["adb", "shell", "screenrecord --time-limit 180 /sdcard/working-dots.mp4 & echo $! > /sdcard/working-dots.pid; wait"], stdout=output, stderr=subprocess.STDOUT)
try:
    event("clock", device_at=int(run("adb", "shell", "date", "+%s%3N")))
    subprocess.run(["sudo", "-u", "verifyssh", "touch", "/home/verifyssh/start-working-proof"], check=True)
    start = time.monotonic()
    phases = set()
    while time.monotonic() - start < 155:
        elapsed = time.monotonic() - start
        if elapsed >= 5 and "start" not in phases:
            shot("quiet-tools")
            phases.add("start")
        if elapsed >= 45 and "lock" not in phases:
            event("lock")
            run("adb", "shell", "input", "keyevent", "KEYCODE_SLEEP")
            phases.add("lock")
        if elapsed >= 48 and "unlock" not in phases:
            run("adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            run("adb", "shell", "wm", "dismiss-keyguard")
            event("unlock")
            shot("after-unlock")
            phases.add("unlock")
        if elapsed >= 80 and "disconnect" not in phases:
            before = auth_count()
            event("disconnect", authentications=before)
            run("sudo", "ss", "-K", "sport = :2222")
            phases.add("disconnect")
        if "disconnect" in phases and "reconnected" not in phases and auth_count() > before:
            event("reconnected", authentications=auth_count())
            shot("after-reconnect")
            phases.add("reconnected")
        if any(row["event"] == "complete" for row in server_events()):
            time.sleep(2)
            shot("after-turn")
            break
        time.sleep(0.2)
    else:
        raise AssertionError("Quiet tool turn did not complete")
    assert phases == {"start", "lock", "unlock", "disconnect", "reconnected"}, phases
finally:
    pid = run("adb", "shell", "cat", "/sdcard/working-dots.pid")
    assert pid.isdigit()
    subprocess.run(["adb", "shell", "kill", "-2", pid], check=False)
    recording.wait(timeout=20)
    subprocess.run(["adb", "pull", "/sdcard/working-dots.mp4", str(root / "working-dots.mp4")], check=True)
    (root / "server.jsonl").write_text(run("sudo", "cat", "/home/verifyssh/working-events.jsonl") + "\n")
    (root / "working-logcat.txt").write_text(run("adb", "logcat", "-d", "-v", "epoch", "-s", "SessionWorking:I"))
    transcript = run("sudo", "find", "/home/verifyssh/.claude/projects", "-name", "*.jsonl").splitlines()
    assert len(transcript) == 1, transcript
    (root / "transcript.jsonl").write_text(run("sudo", "cat", transcript[0]) + "\n")
subprocess.run(["python3", "tools/working-dots/check.py", str(root)], check=True)
