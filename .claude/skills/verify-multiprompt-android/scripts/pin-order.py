#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET

NAMES = ("verify-session", "verify-other")
PACKAGE = "dev.multiprompt.companion"


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    if not match:
        raise SystemExit("pin row bounds missing")
    rect = tuple(map(int, match.groups()))
    if rect[0] >= rect[2] or rect[1] >= rect[3]:
        raise SystemExit("pin row bounds empty")
    return rect


def rows(root):
    found = []
    for name in NAMES:
        nodes = [
            node for node in root.iter("node")
            if node.get("text") == name and (node.get("package") or "").startswith(PACKAGE)
        ]
        if len(nodes) != 1:
            raise SystemExit(f"expected one {name} row, found {len(nodes)}")
        found.append((name, bounds(nodes[0])))
    found.sort(key=lambda item: (item[1][1], item[1][0], item[0]))
    upper, lower = found
    if upper[1][3] > lower[1][1]:
        raise SystemExit(f"inbox rows overlap: {upper[0]} {upper[1]} vs {lower[0]} {lower[1]}")
    return upper, lower


def main():
    mode, path = sys.argv[1], sys.argv[2]
    upper, lower = rows(ET.parse(path).getroot())
    if mode == "order":
        print(f"top {upper[0]} bottom {lower[0]}")
        return
    if mode == "above":
        name = sys.argv[3]
        if name not in NAMES:
            raise SystemExit(f"unknown session {name}")
        if upper[0] != name:
            raise SystemExit(f"{name} is not above the other chat")
        print(f"PINNED_ABOVE {name}")
        return
    if mode == "same":
        top, bottom = sys.argv[3], sys.argv[4]
        if (upper[0], lower[0]) != (top, bottom):
            raise SystemExit(f"order is {upper[0]} then {lower[0]}, expected {top} then {bottom}")
        print("PIN_ORDER_RESTORED")
        return
    raise SystemExit("pin order mode must be order, above, or same")


if __name__ == "__main__":
    main()
