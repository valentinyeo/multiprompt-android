#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from PIL import Image


def bounds(node):
    values = [int(n) for n in re.findall(r"-?\d+", node.get("bounds", ""))]
    assert len(values) == 4 and values[2] > values[0] and values[3] > values[1], values
    return values


def one(nodes, text):
    found = [node for node in nodes if text in node.get("text", "")]
    assert len(found) == 1, (text, len(found))
    return found[0]


mode = sys.argv[1]
if mode == "transport":
    lines = Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1 and lines[0].startswith("VERIFY_RECEIVED:"), lines
    text = lines[0].removeprefix("VERIFY_RECEIVED:")
    prefix = " ".join(("Same first words " + "for this example " * 8).split())[:80].rstrip()
    assert text == "👍 to: " + prefix + " [mp:6d8e353d25c5fe23]", text
    Path(sys.argv[3]).write_text(json.dumps(dict(text=text, submissions=1), ensure_ascii=False, indent=2), encoding="utf-8")
    print("REACTION_REACHED_REAL_TMUX_ONCE")
    raise SystemExit(0)

nodes = list(ET.parse(sys.argv[2]).getroot().iter("node"))
if mode == "mixed":
    texts = [node.get("text", "") for node in nodes]
    assert "My follow-up stays visible" in texts, texts
    assert "Answer to the follow-up" in texts, texts
    assert not any("[mp:6d8e353d25c5fe23]" in text for text in texts), texts
    assert not any("Padded reaction received." in text for text in texts), texts
    assert any(node.get("content-desc") == "Reactions: 👍" for node in nodes), "badge missing"
    Path(sys.argv[3]).write_text(json.dumps(dict(status="passed", mixedWords=True, answer=True, badge=True, wireHidden=True)))
    print("MIXED_REACTION_DISPLAY_VERIFIED")
    raise SystemExit(0)

first = bounds(one(nodes, "FIRST REPLY"))
if mode == "point":
    print((first[0] + first[2]) // 2, (first[1] + first[3]) // 2)
    raise SystemExit(0)

assert mode == "check", mode
image = Image.open(sys.argv[3]).convert("RGB")
stage = sys.argv[4]
second = bounds(one(nodes, "SECOND REPLY"))
badges = [node for node in nodes if node.get("content-desc") == "Reactions: 👍"]
assert len(badges) == 1, ("expected one thumbs-up badge", len(badges))
badge = bounds(badges[0])
assert first[3] <= badge[1] < badge[3] <= second[1], ("badge must stay on first reply", first, badge, second)
assert badge[2] - badge[0] < image.width // 3, ("small badge, not a message bubble", badge)
assert 0 <= badge[0] < badge[2] <= image.width and 0 <= badge[1] < badge[3] <= image.height
crop = image.crop(tuple(badge))
colors = sorted(crop.getcolors(crop.width * crop.height) or [], reverse=True)
assert len(colors) > 3 and crop.width * crop.height - colors[0][0] > 20, "badge glyph must be painted"
palette = {rgb: count for count, rgb in colors}
light = sum(image.getpixel((0, (badge[1] + badge[3]) // 2))) > 3 * 128
fill, outline = ((225, 225, 225), (155, 155, 155)) if light else ((53, 53, 53), (74, 74, 74))
assert palette.get(fill, 0) > crop.width * crop.height // 4, "desktop grey reaction fill missing"
assert palette.get(outline, 0) > 10, "desktop grey reaction outline missing"
quoted = [node.get("text") for node in nodes if "👍 to:" in node.get("text", "")]
replies = [node.get("text") for node in nodes if "Reaction received." in node.get("text", "")]
assert not replies, ("the session reply to a matched reaction must stay hidden", replies)
if stage == "fallback":
    assert len(quoted) == 1 and "Unavailable reply" in quoted[0], quoted
else:
    assert not quoted, ("matched reaction must not make a message bubble", quoted)
if stage == "badge":
    edits = [node for node in nodes if node.get("class") == "android.widget.EditText"]
    assert any(node.get("text") == "draft stays here" for node in edits), "reaction must retain the composer draft"
out = Path(sys.argv[5])
data = json.loads(out.read_text()) if out.exists() else dict(feature="chat-reactions", checks=[])
data["checks"].append(dict(stage=stage, badge=badge, first=first, second=second, passed=True))
data["status"] = "passed" if {row["stage"] for row in data["checks"]} >= {"badge", "restart", "fallback"} else "incomplete"
out.write_text(json.dumps(data, indent=2), encoding="utf-8")
print("REACTION_BADGE_PROVEN", stage)
