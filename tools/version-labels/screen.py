#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET


def check(root, version, required):
    description = f"Version {version}, open release notes"
    labels = [
        node for node in root.iter("node")
        if node.get("content-desc") == description and node.get("text") == version
    ]
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
