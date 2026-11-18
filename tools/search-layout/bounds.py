#!/usr/bin/env python3
import json
import re
import sys
import xml.etree.ElementTree as ET


def box(node):
    numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
        raise SystemExit("empty bounds")
    return numbers


def ime_top(ime, window):
    blob = ime + "\n" + window
    if not re.search(r"mInputShown=true", blob):
        raise SystemExit("keyboard is hidden")
    match = None
    for pattern in (
        r"(?i)inputmethod[\s\S]{0,1200}?mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
        r"(?i)type=ime frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
    ):
        match = re.search(pattern, blob)
        if match is not None:
            break
    if match is None:
        raise SystemExit("keyboard frame missing")
    top = int(match.group(2))
    bottom = int(match.group(4))
    if bottom <= top:
        raise SystemExit("keyboard frame is empty")
    return top


def check(root, ime, window, query, description):
    fields = [
        node for node in root.iter("node")
        if node.get("class") == "android.widget.EditText" and (
            node.get("text") == query or any(
                item.get("text") == "Search all sessions" or item.get("content-desc") == "Search all sessions"
                for item in node.iter()
            )
        )
    ]
    if len(fields) != 1:
        raise SystemExit(f"search field missing or ambiguous ({len(fields)})")
    rows = [node for node in root.iter("node") if node.get("content-desc") == description]
    if len(rows) != 1:
        raise SystemExit(f"search row missing or ambiguous ({len(rows)})")
    field = box(fields[0])
    row = box(rows[0])
    keyboard = ime_top(ime, window)
    if field[3] > row[1]:
        raise SystemExit("search result is not below the search box")
    if row[3] > keyboard:
        raise SystemExit("search result is not above the keyboard")
    return {
        "query": query,
        "description": description,
        "fieldBottom": field[3],
        "rowTop": row[1],
        "rowBottom": row[3],
        "keyboardTop": keyboard,
        "aboveKeyboard": True,
    }


def main():
    xml, ime_path, window_path, query, description = sys.argv[1:6]
    result = check(
        ET.parse(xml).getroot(),
        open(ime_path, encoding="utf-8", errors="replace").read(),
        open(window_path, encoding="utf-8", errors="replace").read(),
        query,
        description,
    )
    json.dump(result, sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
