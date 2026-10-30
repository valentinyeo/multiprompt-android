import json
from pathlib import Path
import sys


def verify(samples, server, journey):
    start = next(row["at"] for row in server if row["event"] == "start")
    end = next(row["at"] for row in server if row["event"] == "end")
    assert end - start > 120000, "Quiet turn must exceed two minutes"
    tools = [row for row in server if row["event"] == "tool"]
    assert len(tools) > 120 and all(start <= row["at"] <= end for row in tools), "Missing actual tool receipts"
    clock = next(row for row in journey if row["event"] == "clock")
    offset = clock["at"] - clock["device_at"]
    samples = [dict(row, at=row["at"] + offset) for row in samples]
    lock = next(row["at"] for row in journey if row["event"] == "lock")
    unlock = next(row["at"] for row in journey if row["event"] == "unlock")
    disconnect = next(row for row in journey if row["event"] == "disconnect")
    reconnect = next(row for row in journey if row["event"] == "reconnected")
    assert start < lock < unlock < disconnect["at"] < reconnect["at"] < end
    assert reconnect["authentications"] > disconnect["authentications"], "No real SSH reconnect"
    relevant = [row for row in samples if start - 1000 <= row["at"] <= end + 2000]
    assert relevant and relevant[0]["at"] < start and relevant[-1]["at"] >= end + 1000, "Missing transition coverage"
    assert all(0 < b["at"] - a["at"] <= 1000 for a, b in zip(relevant, relevant[1:])), "Visibility sampling gap"
    shown = next(row["at"] for row in relevant if row["at"] >= start and row["visible"])
    hidden = next(row["at"] for row in relevant if row["at"] >= end and not row["visible"])
    assert shown - start <= 1000, "Dots appeared too late"
    assert hidden - end <= 1000, "Dots cleared too late"
    assert any(not row["interactive"] for row in relevant if lock <= row["at"] <= unlock), "Screen never locked"
    for row in relevant:
        at = row["at"]
        if start + 1000 <= at < end and not lock <= at <= unlock + 1000:
            assert row["interactive"] and row["visible"], f"Working dots missing at {at}"
        if end + 1000 <= at:
            assert not row["visible"], "Dots remained after turn"
    return dict(duration_ms=end - start, tools=len(tools), samples=len(relevant),
        appear_ms=shown - start, clear_ms=hidden - end, reconnect_ms=reconnect["at"] - disconnect["at"])


def visible_frame(path):
    from PIL import Image, ImageStat
    with Image.open(path) as image:
        assert min(image.size) > 100, "Screenshot is not a real display frame"
        assert max(ImageStat.Stat(image.convert("RGB")).mean) > 5, "Screenshot is still screen-off black"


def load(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


if __name__ == "__main__":
    root = Path(sys.argv[1])
    prefix = "INSTRUMENTATION_STATUS: sample="
    samples = [json.loads(line[len(prefix):]) for line in (root / "visibility.txt").read_text().splitlines() if line.startswith(prefix)]
    result = verify(samples, load(root / "server.jsonl"), load(root / "journey.jsonl"))
    transcript = load(root / "transcript.jsonl")
    assert len(transcript) == 2, "Unexpected chat text during quiet tools"
    assert (root / "working-dots.mp4").stat().st_size > 10000, "Recording missing"
    for name in ["before-turn", "quiet-tools", "after-unlock", "after-reconnect", "after-turn"]:
        visible_frame(root / (name + ".png"))
    (root / "result.json").write_text(json.dumps(dict(status="pass", **result), indent=2) + "\n")
    print("RELEASE_WORKING_DOTS_PROVEN", json.dumps(result))
