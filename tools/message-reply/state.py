#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET


def point(root, value):
    matches = [node for node in root.iter("node") if node.get("text") == value]
    if len(matches) != 1:
        raise SystemExit(f"long-press target missing or ambiguous ({len(matches)})")
    numbers = list(map(int, re.findall(r"\d+", matches[0].get("bounds", ""))))
    if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
        raise SystemExit("long-press target has empty bounds")
    return (numbers[0] + numbers[2]) // 2, (numbers[1] + numbers[3]) // 2


def main():
    x, y = point(ET.parse(sys.argv[1]).getroot(), sys.argv[2])
    print(x, y)


if __name__ == "__main__":
    main()
