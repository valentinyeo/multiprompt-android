#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET


def check(root):
    texts = [node.get("text") or "" for node in root.iter("node")]
    if any(text == "Conversation" for text in texts):
        raise SystemExit("Conversation header is present")
    subtitle = next((
        text for text in texts
        if text in ("Model / effort", "waiting for input") or text.endswith(" · waiting for input")
    ), None)
    if subtitle is None:
        raise SystemExit("reader subtitle is missing")
    return {"conversationHeader": False, "subtitle": subtitle}


def main():
    json.dump(check(ET.parse(sys.argv[1]).getroot()), sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
