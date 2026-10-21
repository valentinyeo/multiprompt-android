#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
mode, value = sys.argv[2:4]
parents = {child: parent for parent in root.iter() for child in parent}
matches = []
for node in root.iter("node"):
    if mode == "text" and value == node.get("text"):
        matches.append(node)
    elif mode == "contains" and value in node.get("text", ""):
        matches.append(node)
    elif mode == "desc" and value == node.get("content-desc"):
        matches.append(node)
    elif mode == "field" and node.get("class") == "android.widget.EditText":
        if any(value == item.get("text") or value == item.get("content-desc") for item in node.iter()):
            matches.append(node)
if not matches:
    raise SystemExit(f"Missing {mode}: {value}")
if mode == "contains":
    print(f"Found {len(matches)} matching text nodes")
    raise SystemExit(0)
if len(matches) != 1:
    raise SystemExit(f"Ambiguous {mode}: {value} ({len(matches)})")
node = matches[0]
if mode != "field":
    while node.get("clickable") != "true" and node in parents:
        node = parents[node]
    if node.get("clickable") != "true" or node.get("enabled") == "false":
        raise SystemExit("Selector has no enabled clickable ancestor")
numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
    raise SystemExit("Empty selector bounds")
print((numbers[0] + numbers[2]) // 2, (numbers[1] + numbers[3]) // 2)
