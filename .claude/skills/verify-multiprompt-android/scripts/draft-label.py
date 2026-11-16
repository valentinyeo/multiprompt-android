#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET

PREVIEW = "Inbox preview stays."
SESSION = "verify-session"
PACKAGE = "dev.multiprompt.companion"


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    if not match:
        raise SystemExit("draft row bounds missing")
    rect = tuple(map(int, match.groups()))
    if rect[0] >= rect[2] or rect[1] >= rect[3]:
        raise SystemExit("draft row bounds empty")
    return rect


def session_row(root):
    parents = {child: parent for parent in root.iter() for child in parent}
    names = [
        node for node in root.iter("node")
        if node.get("text") == SESSION and (node.get("package") or "").startswith(PACKAGE)
    ]
    if len(names) != 1:
        raise SystemExit(f"expected one {SESSION} row, found {len(names)}")
    row = names[0]
    while row.get("clickable") != "true" and row in parents:
        row = parents[row]
    if row.get("clickable") != "true" or not (row.get("package") or "").startswith(PACKAGE):
        raise SystemExit("session row is not clickable")
    return row


def main():
    mode, path = sys.argv[1], sys.argv[2]
    if mode not in ("present", "absent"):
        raise SystemExit("draft label mode must be present or absent")
    root = ET.parse(path).getroot()
    row = session_row(root)
    drafts = [node for node in row.iter("node") if node.get("text") == "Draft"]
    previews = [node for node in row.iter("node") if node.get("text") == PREVIEW]
    if len(previews) != 1:
        raise SystemExit(f"expected the inbox preview beside the draft label, found {len(previews)}")
    if mode == "absent":
        stray = [
            node for node in root.iter("node")
            if node.get("text") == "Draft" and (node.get("package") or "").startswith(PACKAGE)
        ]
        if stray or drafts:
            raise SystemExit("Draft label still visible")
        print("DRAFT_LABEL_GONE")
        return
    if len(drafts) != 1:
        raise SystemExit(f"expected one Draft label, found {len(drafts)}")
    draft, preview = bounds(drafts[0]), bounds(previews[0])
    if draft[2] > preview[0] or draft[1] >= preview[3] or preview[1] >= draft[3]:
        raise SystemExit(f"Draft label is not before the preview: {draft} vs {preview}")
    print("DRAFT_BEFORE_PREVIEW")


if __name__ == "__main__":
    main()
