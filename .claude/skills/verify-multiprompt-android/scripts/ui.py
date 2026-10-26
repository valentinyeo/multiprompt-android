#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
mode, value = sys.argv[2:4]
parents = {child: parent for parent in root.iter() for child in parent}
matches = []
for node in root.iter("node"):
    if mode in ("text", "absent", "version") and value == node.get("text"):
        matches.append(node)
    elif mode in ("contains", "lacks", "text-containing") and value in node.get("text", ""):
        matches.append(node)
    elif mode in ("desc", "checked", "unchecked") and value == node.get("content-desc"):
        matches.append(node)
    elif mode == "field" and node.get("class") == "android.widget.EditText":
        if any(value == item.get("text") or value == item.get("content-desc") for item in node.iter()):
            matches.append(node)
if mode in ("absent", "lacks"):
    if not any(n.get("package") == "dev.multiprompt.companion.debug" for n in root.iter("node")):
        raise SystemExit("App UI is missing")
    if matches:
        raise SystemExit(f"Unexpected text: {value}")
    print(f"Absent text: {value}")
    raise SystemExit(0)
if not matches:
    raise SystemExit(f"Missing {mode}: {value}")
if mode == "contains":
    print(f"Found {len(matches)} matching text nodes")
    raise SystemExit(0)
if len(matches) != 1:
    raise SystemExit(f"Ambiguous {mode}: {value} ({len(matches)})")
node = matches[0]
if mode == "version":
    if node.get("content-desc") != f"Version {value}, open release notes":
        raise SystemExit("Version label description does not match")
if mode in ("checked", "unchecked"):
    expected = "true" if mode == "checked" else "false"
    if node.get("checkable") != "true" or node.get("checked") != expected:
        raise SystemExit(f"Expected {mode}: {value}")
if node.get("enabled") == "false":
    raise SystemExit("Selector is disabled")
if mode not in ("field", "version", "checked", "unchecked"):
    ancestor = node
    while ancestor.get("clickable") != "true" and ancestor in parents:
        ancestor = parents[ancestor]
    if ancestor.get("clickable") == "true":
        if ancestor.get("enabled") == "false":
            raise SystemExit("Selector ancestor is disabled")
        node = ancestor
numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
    raise SystemExit("Empty selector bounds")
if mode == "version":
    print(value)
elif mode in ("checked", "unchecked"):
    print(f"{mode}: {value}")
else:
    print((numbers[0] + numbers[2]) // 2, (numbers[1] + numbers[3]) // 2)
