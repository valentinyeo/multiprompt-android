#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

xml, expected, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
nodes = [n for n in ET.parse(xml).iter("node") if "Queued" in n.get("text", "")]
if len(nodes) != expected:
    print(f"IGSH1008_QUEUED_COUNT_{name}: expected {expected} Queued labels, found {len(nodes)}", file=sys.stderr)
    raise SystemExit(1)
print(f"QUEUED_LABELS_{name}={len(nodes)}")
