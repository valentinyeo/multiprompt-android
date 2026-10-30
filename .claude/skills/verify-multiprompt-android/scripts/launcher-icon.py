#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from PIL import Image

APP_PACKAGE = "dev.multiprompt.companion.debug"
LABEL = "multiprompt"


def bounds(node):
    values = [int(n) for n in re.findall(r"-?\d+", node.get("bounds", ""))]
    assert len(values) == 4 and values[2] > values[0] and values[3] > values[1], values
    return values


def launcher_cells(path):
    root = ET.parse(path).getroot()
    return [n for n in root.iter("node") if n.get("text") == LABEL and n.get("package") != APP_PACKAGE]


def measure(image):
    width, height = image.size
    pixels = image.convert("RGB").load()
    cx, cy = width / 2, height * 0.62
    radius = width * 0.25
    inside = white = dark = cyan = 0
    for y in range(height):
        for x in range(width):
            if (x - cx) ** 2 + (y - cy) ** 2 > radius ** 2:
                continue
            inside += 1
            r, g, b = pixels[x, y]
            if min(r, g, b) >= 215:
                white += 1
            if max(r, g, b) <= 44:
                dark += 1
            if b >= 200 and r <= 170 and g >= 150:
                cyan += 1
    return {"white": white / inside, "dark": dark / inside, "cyan": cyan / inside}


def verdict(stats):
    reasons = []
    if stats["white"] < 0.25:
        reasons.append(f"white head area {stats['white']:.3f} is below 0.25")
    if stats["dark"] < 0.15:
        reasons.append(f"dark tile and visor area {stats['dark']:.3f} is below 0.15")
    if stats["cyan"] > 0.005:
        reasons.append(f"old cyan glyph pixels {stats['cyan']:.3f} are above 0.005")
    return reasons


def icon_region(cell_bounds):
    left, top, right, bottom = cell_bounds
    height = bottom - top
    return (left, top, right, top + int(height * 0.62))


mode = sys.argv[1]
if mode == "resource-file":
    table = Path(sys.argv[2]).read_text()
    name = sys.argv[3]
    entries = re.split(r"(?m)^\s*resource ", table)
    matches = [entry for entry in entries[1:] if re.match(r"\S+\s+" + re.escape(name) + r"(?:\s|$)", entry)]
    assert len(matches) == 1, f"LAUNCHER_RESOURCE_NOT_UNIQUE: {name}"
    paths = set(re.findall(r"(?m)^\s*\([^)]*\)\s+\(file\)\s+(\S+\.xml)(?:\s|$)", matches[0]))
    assert len(paths) == 1, f"LAUNCHER_RESOURCE_FILE_NOT_UNIQUE: {name}"
    print(paths.pop())
    raise SystemExit(0)

if mode in ("find", "home", "position"):
    cells = launcher_cells(sys.argv[2])
    if len(cells) != 1:
        raise SystemExit(f"Expected one launcher cell labelled {LABEL}, found {len(cells)}")
    if mode == "home":
        ids = {n.get("resource-id", "").split(":id/")[-1] for n in ET.parse(sys.argv[2]).iter("node")}
        assert "workspace" in ids and "apps_view" not in ids, "LAUNCHER_NOT_HOME_SCREEN"
    if mode == "position":
        left, top, right, bottom = bounds(cells[0])
        print((left + right) // 2, top + int((bottom - top) * 0.31))
    else:
        print(cells[0].get("bounds"))
    raise SystemExit(0)

if mode == "check":
    xml_path, screenshot, crop_path, result_path = sys.argv[2:6]
    cells = launcher_cells(xml_path)
    assert len(cells) == 1, f"Expected one launcher cell labelled {LABEL}, found {len(cells)}"
    region = icon_region(bounds(cells[0]))
    crop = Image.open(screenshot).convert("RGB").crop(region)
    crop.save(crop_path)
    stats = measure(crop)
    reasons = verdict(stats)
    result = {"cell": cells[0].get("bounds"), "region": list(region), "stats": stats, "failures": reasons}
    with open(result_path, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2)
    if reasons:
        raise SystemExit("LAUNCHER_ICON_NOT_ROBOT: " + "; ".join(reasons))
    print("LAUNCHER_ICON_IS_ROBOT")
    raise SystemExit(0)

raise SystemExit(f"Unknown mode: {mode}")
