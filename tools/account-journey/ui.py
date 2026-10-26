#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
mode, value = sys.argv[2:4]
labels = lambda n: [n.get("text") or "", n.get("content-desc") or ""]
if mode in ("has", "tap", "selected"):
    nodes = [n for n in root.iter("node") if value in labels(n)]
else:
    needle = value.lower()
    nodes = [n for n in root.iter("node") if any(needle in label.lower() for label in labels(n))]
if mode == "selected":
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = [node for node in nodes if any(
        target.get("selected") == "true" or (target.get("checkable") == "true" and target.get("checked") == "true")
        for target in (node, parents.get(node, node))
    )]
if mode in ("has", "hasi", "selected"):
    raise SystemExit(0 if nodes else 1)
if not nodes:
    raise SystemExit(1)
left, top, right, bottom = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
print((left + right) // 2, (top + bottom) // 2)
