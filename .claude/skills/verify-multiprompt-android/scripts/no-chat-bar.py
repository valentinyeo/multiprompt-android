#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET
from PIL import Image

root = ET.parse(sys.argv[1]).getroot()
fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
assert len(fields) == 1, "Expected chat composer"
x1, y1, x2, y2 = map(int, re.findall(r"\d+", fields[0].get("bounds", "")))
image = Image.open(sys.argv[2]).convert("RGB")
assert 0 < y1 < y2 <= image.height and 0 <= x1 < x2 <= image.width
for y in range(max(0, y1 - (y2 - y1)), y1):
    blue = sum(b > r + 30 and b > g + 20
               for r, g, b in (image.getpixel((x, y)) for x in range(x1, x2)))
    assert blue < (x2 - x1) / 4, f"Blue progress strip above composer at y={y}"
if sys.argv[3] == "sending":
    spinners = [n for n in root.iter("node") if n.get("class") == "android.widget.ProgressBar"]
    assert any(y1 <= int(re.findall(r"\d+", n.get("bounds", ""))[1]) < y2 for n in spinners), "Send spinner missing"
print("NO_CHAT_BAR " + sys.argv[3])
