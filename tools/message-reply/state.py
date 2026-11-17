#!/usr/bin/env python3
import json
import re
import sys
import xml.etree.ElementTree as ET

BUBBLE = "REPLY_BUBBLE_418"


def texts(root):
    return [node.get("text") or "" for node in root.iter("node")]


def point(root, value):
    matches = [node for node in root.iter("node") if node.get("text") == value]
    if len(matches) != 1:
        raise SystemExit(f"long-press target missing or ambiguous ({len(matches)})")
    numbers = list(map(int, re.findall(r"\d+", matches[0].get("bounds", ""))))
    if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
        raise SystemExit("long-press target has empty bounds")
    return (numbers[0] + numbers[2]) // 2, (numbers[1] + numbers[3]) // 2


def check(root, phase):
    shown = texts(root)
    descriptions = [node.get("content-desc") or "" for node in root.iter("node")]
    replying = "Replying" in shown
    if phase == "open":
        if not replying or "Cancel reply" not in descriptions or BUBBLE not in shown:
            raise SystemExit("reply bar is incomplete")
        return {"replying": True, "cancel": "Cancel reply", "preview": BUBBLE}
    if phase == "cancelled":
        if replying or "Cancel reply" in descriptions:
            raise SystemExit("reply bar is still open")
        return {"replying": False}
    raise SystemExit(f"unknown reply phase {phase}")


def main():
    if sys.argv[1] == "point":
        x, y = point(ET.parse(sys.argv[2]).getroot(), sys.argv[3])
        print(x, y)
        return
    json.dump(check(ET.parse(sys.argv[1]).getroot(), sys.argv[2]), sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
