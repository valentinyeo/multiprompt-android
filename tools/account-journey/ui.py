#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
mode, value = sys.argv[2:4]
nodes = [n for n in root.iter("node") if n.get("text") == value]
if mode == "has":
    raise SystemExit(0 if nodes else 1)
if mode == "tap":
    if not nodes:
        raise SystemExit(1)
    left, top, right, bottom = map(int, __import__("re").findall(r"\d+", nodes[0].get("bounds")))
    print((left + right) // 2, (top + bottom) // 2)
