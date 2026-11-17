#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

PACKAGE = "dev.multiprompt.companion"


def nodes(root):
    return [node for node in root.iter("node") if (node.get("package") or "").startswith(PACKAGE)]


def editors(root):
    return [node for node in nodes(root) if node.get("class") == "android.widget.EditText"]


def main():
    mode, path = sys.argv[1], sys.argv[2]
    root = ET.parse(path).getroot()
    if not nodes(root):
        raise SystemExit("App UI is missing")
    if mode == "hidden":
        chat_id = sys.argv[3]
        shown = [node for node in nodes(root) if node.get("text") == chat_id]
        if shown:
            raise SystemExit("chat id is already on screen")
        print("COPY_CHAT_ID_HIDDEN")
        return
    fields = editors(root)
    if len(fields) != 1:
        raise SystemExit(f"expected one composer, found {len(fields)}")
    text = fields[0].get("text") or ""
    if mode == "empty":
        if text:
            raise SystemExit(f"composer is not empty: {text}")
        print("COPY_CHAT_ID_EMPTY")
        return
    if mode == "pasted":
        chat_id = sys.argv[3]
        if text != chat_id:
            raise SystemExit(f"composer has {text!r}, expected {chat_id!r}")
        print("COPY_CHAT_ID_PASTED")
        return
    raise SystemExit("copy chat id mode must be hidden, empty or pasted")


if __name__ == "__main__":
    main()
