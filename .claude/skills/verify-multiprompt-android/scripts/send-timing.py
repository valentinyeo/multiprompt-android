#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET

xml_path, log_path, sent = sys.argv[1:4]
marks = {}
for line in open(log_path, errors="replace"):
    found = re.search(r"SendTiming.*?: (\w+) \+(\d+)ms", line)
    if found:
        marks[found.group(1)] = int(found.group(2))
print("SEND_TIMING " + " ".join(f"{k}={v}ms" for k, v in marks.items()))
assert "message_added" in marks, "message_added mark missing"
assert "box_cleared_frame" in marks, "composer was still full when the UI was captured"
assert marks["box_cleared_frame"] < 100, f"box cleared {marks['box_cleared_frame']} ms after tap, limit 100"
root = ET.parse(xml_path).getroot()
fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
assert len(fields) == 1, "Expected chat composer"
assert sent not in fields[0].get("text", ""), "Composer still holds the sent text"
shown = [n for n in root.iter("node") if n.get("class") != "android.widget.EditText" and sent in n.get("text", "")]
assert shown, "Sent message is not on screen"
print("SEND_AT_TAP_OK")
