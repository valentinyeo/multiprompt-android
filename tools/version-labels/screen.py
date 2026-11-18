#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET


def label_texts(node):
    texts = [node.get("text") or ""]
    texts.extend((child.get("text") or "") for child in node.iter("node") if child is not node)
    return [text for text in texts if text]


def check(root, version, required):
    description = f"Version {version}, open release notes"
    labels = []
    for node in root.iter("node"):
        if node.get("content-desc") != description:
            continue
        visible = label_texts(node)
        if visible and version not in visible:
            raise SystemExit("version label text does not match")
        labels.append(node)
    if len(labels) != 1:
        raise SystemExit(f"expected one version label, found {len(labels)}")
    shown = [node.get("text") or "" for node in root.iter("node")]
    missing = [item for item in required if item not in shown]
    if missing:
        raise SystemExit("missing " + ", ".join(missing))
    return {"version": version, "labels": 1, "required": list(required)}


def main():
    version = sys.argv[2]
    required = sys.argv[3:]
    json.dump(check(ET.parse(sys.argv[1]).getroot(), version, required), sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
