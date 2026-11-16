#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

PACKAGE = "dev.multiprompt.companion"
PLACEHOLDER = "Message verify-session"


def app_nodes(root):
    return [
        node for node in root.iter("node")
        if (node.get("package") or "").startswith(PACKAGE)
    ]


def count(nodes, attr, value):
    return sum(1 for node in nodes if node.get(attr) == value)


def filled(nodes):
    return [
        node.get("text") for node in nodes
        if node.get("class") == "android.widget.EditText" and node.get("text") and node.get("text") != PLACEHOLDER
    ]


def main():
    mode, path = sys.argv[1], sys.argv[2]
    nodes = app_nodes(ET.parse(path).getroot())
    cancels = count(nodes, "content-desc", "Cancel dictation")
    starts = count(nodes, "content-desc", "Start dictation")
    stops = count(nodes, "content-desc", "Stop dictation")
    placeholders = count(nodes, "text", PLACEHOLDER)
    text = filled(nodes)
    if mode == "bar":
        if cancels != 1 or starts != 0 or stops != 1:
            raise SystemExit(f"dictation bar missing: cancel={cancels} start={starts} stop={stops}")
        if placeholders or text:
            raise SystemExit(f"composer still visible during dictation: placeholder={placeholders} text={text}")
        print("DICTATION_CANCEL_VISIBLE")
        return
    if mode == "empty":
        if starts != 1 or cancels != 0 or stops != 0 or placeholders < 1 or text:
            raise SystemExit(
                f"composer was not empty after cancel: cancel={cancels} start={starts} stop={stops} placeholder={placeholders} text={text}"
            )
        print("DICTATION_COMPOSER_EMPTY")
        return
    raise SystemExit("dictation cancel mode must be bar or empty")


if __name__ == "__main__":
    main()
