#!/usr/bin/env python3
import os
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
mode, value = sys.argv[2:4]
if mode.startswith("reach-"):
    from journey_action import Unreachable, plan
    try:
        print(plan(root, mode.removeprefix("reach-"), value, sys.argv[4] if len(sys.argv) > 4 else "forward"))
    except Unreachable as error:
        raise SystemExit(f"JOURNEY_ACTION_UNREACHABLE: {value or mode.removeprefix('reach-')!r}: {error}")
    raise SystemExit(0)
parents = {child: parent for parent in root.iter() for child in parent}
matches = []
if mode == "terminal":
    def bounds(description):
        nodes = [n for n in root.iter("node") if n.get("content-desc") == description]
        if len(nodes) != 1:
            raise SystemExit(f"Missing or ambiguous terminal control: {description}")
        return list(map(int, re.findall(r"\d+", nodes[0].get("bounds", ""))))
    toolbar = bounds("Show keyboard")
    keys = bounds("Esc key")
    if len(toolbar) != 4 or len(keys) != 4 or keys[1] <= toolbar[3]:
        raise SystemExit("Terminal typing area is not visible")
    print((keys[0] + toolbar[2]) // 2, (toolbar[3] + keys[1]) // 2)
    raise SystemExit(0)
package = os.environ.get("VERIFY_PACKAGE", "dev.multiprompt.companion.debug")
for node in root.iter("node"):
    if mode == "scroll-back" and node.get("scrollable") == "true" and node.get("package") == package and node.get("class") != "android.widget.HorizontalScrollView":
        matches.append(node)
    elif mode in ("text", "absent", "version") and value == node.get("text"):
        matches.append(node)
    elif mode in ("contains", "lacks", "text-containing") and value in node.get("text", ""):
        matches.append(node)
    elif mode in ("desc", "checked", "unchecked", "key-row") and value == node.get("content-desc"):
        matches.append(node)
    elif mode in ("editor", "editor-value") and node.get("class") == "android.widget.EditText" and node.get("package") == package:
        matches.append(node)
    elif mode == "field-value" and node.get("class") == "android.widget.EditText" and node.get("focused") == "true":
        matches.append(node)
    elif mode in ("field", "focused-field") and node.get("class") == "android.widget.EditText":
        if value == "" and mode == "focused-field":
            if node.get("focused") == "true" and node.get("package") == package:
                matches.append(node)
        elif any(value == item.get("text") or value == item.get("content-desc") for item in node.iter()):
            matches.append(node)
if mode in ("absent", "lacks"):
    if not any(n.get("package") == package for n in root.iter("node")):
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
if mode == "key-row":
    while node.get("scrollable") != "true" and node in parents:
        node = parents[node]
    if node.get("scrollable") != "true":
        raise SystemExit("Terminal key row is not scrollable")
if mode == "focused-field" and node.get("focused") != "true":
    raise SystemExit(f"Field is not focused: {value}")
if mode in ("field-value", "editor-value"):
    expected = sys.argv[4]
    if (mode == "editor-value" or not expected or node.get("password") != "true") and node.get("text") != expected:
        raise SystemExit(f"Field {value} has {node.get('text')!r}, expected {expected!r}")
    print("FIELD_VALUE_VERIFIED")
    raise SystemExit(0)
if mode == "version":
    if node.get("content-desc") != f"Version {value}, open release notes":
        raise SystemExit("Version label description does not match")
if mode in ("checked", "unchecked"):
    expected = "true" if mode == "checked" else "false"
    if node.get("checkable") != "true" or node.get("checked") != expected:
        raise SystemExit(f"Expected {mode}: {value}")
if node.get("enabled") == "false":
    raise SystemExit("Selector is disabled")
if mode not in ("field", "focused-field", "editor", "version", "checked", "unchecked", "scroll-back", "key-row"):
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
if mode == "key-row":
    inset = (numbers[2] - numbers[0]) // 5
    y = (numbers[1] + numbers[3]) // 2
    print(numbers[0] + inset, y, numbers[2] - inset, y)
elif mode == "scroll-back":
    x = (numbers[0] + numbers[2]) // 2
    inset = (numbers[3] - numbers[1]) // 5
    print(x, numbers[1] + inset, x, numbers[3] - inset)
elif mode == "version":
    print(value)
elif mode in ("checked", "unchecked"):
    print(f"{mode}: {value}")
else:
    print((numbers[0] + numbers[2]) // 2, (numbers[1] + numbers[3]) // 2)
