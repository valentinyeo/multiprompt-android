#!/usr/bin/env python3
import argparse
import json
import sys
import xml.etree.ElementTree as ET


def observed(layout):
    return {
        "workspaces": [workspace.get("name") for workspace in layout.get("workspaces") or []],
        "tabs": {
            tab.get("tmux_session"): {"workspace": tab.get("workspace"), "group": tab.get("group") or ""}
            for tab in layout.get("tabs") or []
        },
        "group_order": layout.get("group_order"),
    }


def stored_layout(path):
    for node in ET.parse(path).getroot().iter("string"):
        if (node.get("name") or "").startswith("layout::"):
            document = json.loads(node.text)
            return document.get("edited", document)
    raise SystemExit(f"ACCOUNT_LAYOUT_MISSING: no layout:: preference in {path}")


def accepted_layouts(path):
    layouts = []
    with open(path) as source:
        for line in source:
            if line.strip():
                event = json.loads(line)
                if event.get("action") == "layout_put":
                    layouts.append(event.get("layout") or {})
    return layouts


def compare(seen, expected):
    if seen != expected:
        raise SystemExit("ACCOUNT_LAYOUT_MISMATCH: " + json.dumps({"seen": seen, "expected": expected}, sort_keys=True, separators=(",", ":")))
    return seen


def stored(path, expected):
    return compare(observed(stored_layout(path)), expected)


def accepted(path, expected):
    layouts = accepted_layouts(path)
    if not layouts:
        raise SystemExit(f"ACCOUNT_LAYOUT_PUT_MISSING: no layout_put receipt in {path}")
    seen = [observed(layout) for layout in layouts]
    for state in seen:
        if state == expected:
            return state
    raise SystemExit("ACCOUNT_LAYOUT_MISMATCH: " + json.dumps({"seen": seen, "expected": expected}, sort_keys=True, separators=(",", ":")))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Check the account layout the app stored and the layout the account fixture accepted.")
    parser.add_argument("source", choices=["prefs", "receipt"], help="prefs reads the app's shared_prefs XML; receipt reads the fixture's JSONL receipts.")
    parser.add_argument("path", help="Evidence file to read.")
    parser.add_argument("expected", help="Expected JSON with workspaces, tabs and group_order.")
    arguments = parser.parse_args(argv)
    check = stored if arguments.source == "prefs" else accepted
    state = check(arguments.path, json.loads(arguments.expected))
    print(json.dumps(state, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
