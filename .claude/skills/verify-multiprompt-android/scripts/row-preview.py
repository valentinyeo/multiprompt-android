#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

QUESTION = "Which target should ship?"
PANE = "Pane reply stays."
STEP = "Rewrite the inbox row"
STORED = "Stored reply stays off the row."
PACKAGE = "dev.multiprompt.companion"
MODES = {
    "question": ("verify-session", QUESTION, (PANE, STEP, STORED), "ROW_QUESTION_SHOWN"),
    "step": ("live-rowstep", STEP, (QUESTION, PANE, STORED), "ROW_STEP_SHOWN"),
}


def session_row(root, name):
    parents = {child: parent for parent in root.iter() for child in parent}
    names = [
        node for node in root.iter("node")
        if node.get("text") == name and (node.get("package") or "").startswith(PACKAGE)
    ]
    if len(names) != 1:
        raise SystemExit(f"expected one {name} row, found {len(names)}")
    row = names[0]
    while row.get("clickable") != "true" and row in parents:
        row = parents[row]
    if row.get("clickable") != "true" or not (row.get("package") or "").startswith(PACKAGE):
        raise SystemExit("session row is not clickable")
    return row


def main():
    mode, path = sys.argv[1], sys.argv[2]
    if mode not in MODES:
        raise SystemExit("row preview mode must be question or step")
    name, expected, banned, marker = MODES[mode]
    root = ET.parse(path).getroot()
    row = session_row(root, name)
    texts = [node.get("text", "") for node in row.iter("node")]
    if texts.count(expected) != 1:
        raise SystemExit(f"expected one {expected!r} preview on {name}, found {texts.count(expected)}")
    for text in banned:
        if text in texts:
            raise SystemExit(f"{text!r} is still on the {name} row")
    print(marker)


if __name__ == "__main__":
    main()
