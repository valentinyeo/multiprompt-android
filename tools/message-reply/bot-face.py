#!/usr/bin/env python3
"""Compare the bot a chat draws for itself with the bot its mention row draws.

The mention list gained each chat's own bot appearance in PR 520. The bot face
is drawn vector art with no accessibility identity of its own, so the identity
is read in two steps: the UI tree names the avatar node ("Robot <session>"),
and the pixels inside that node are compared with the same chat's own avatar
in its reader top bar. Comparing those two crops is what proves the row draws
the chat's bot, not a borrowed one.

Measured on the shared 1080x1920 emulator, 28 dp avatars are 74x74 px. The same
bot rendered twice differs by a mean of 0.1 per channel, a one pixel
anti-aliasing shift by about 3.2, and two different bots by about 59. The
thresholds below sit between those bands, not at the edge of one.
"""

import argparse
import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageChops

BOUNDS = re.compile(r"^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$")
INSET = 0.10
SAME_MEAN = 6.0
SAME_SHARE = 0.15
STRONG_PIXEL = 16
CHANGED_MEAN = 15.0


class FaceError(Exception):
    pass


def node_bounds(xml_path, description):
    import xml.etree.ElementTree as ET

    root = ET.parse(xml_path).getroot()
    matches = [node for node in root.iter("node")
               if node.get("content-desc") == description and node.get("visible-to-user") != "false"]
    if len(matches) != 1:
        raise FaceError(f"{xml_path}: expected one visible {description!r} node, found {len(matches)}")
    match = BOUNDS.match(matches[0].get("bounds", ""))
    if not match:
        raise FaceError(f"{xml_path}: {description!r} has no readable bounds")
    left, top, right, bottom = (int(value) for value in match.groups())
    if right <= left or bottom <= top:
        raise FaceError(f"{xml_path}: {description!r} has empty bounds")
    return left, top, right, bottom


def crop(png_path, bounds):
    left, top, right, bottom = bounds
    image = Image.open(png_path).convert("RGB")
    if right > image.width or bottom > image.height:
        raise FaceError(f"{png_path}: {bounds} falls outside the {image.width}x{image.height} screenshot")
    size = min(right - left, bottom - top)
    inset = round(size * INSET)
    return image.crop((left + inset, top + inset, right - inset, bottom - inset))


def compare(first, second):
    difference = ImageChops.difference(first, second)
    data = difference.tobytes()
    total = len(data) // 3
    mean = sum(data) / (3 * total)
    strong = sum(1 for index in range(0, len(data), 3) if max(data[index:index + 3]) > STRONG_PIXEL) / total
    return {"mean": round(mean, 2), "strongShare": round(strong, 3)}


def avatar(session, role, xml_path, png_path):
    bounds = node_bounds(xml_path, f"Robot {session}")
    image = crop(png_path, bounds)
    return {"role": role, "xml": xml_path, "png": png_path, "bounds": list(bounds),
            "crop": [image.width, image.height]}, image


def main(argv=None):
    parser = argparse.ArgumentParser(description="Prove a mention row draws the chat's own bot face.")
    parser.add_argument("--session", required=True, help="Raw tmux session name the avatar description carries.")
    parser.add_argument("--before", nargs=2, required=True, metavar=("XML", "PNG"),
                        help="The chat's reader top bar before its bot look is chosen.")
    parser.add_argument("--chosen", nargs=2, required=True, metavar=("XML", "PNG"),
                        help="The same chat's reader top bar after the bot look is chosen.")
    parser.add_argument("--mention", nargs=2, required=True, metavar=("XML", "PNG"),
                        help="The mention list row for that chat.")
    parser.add_argument("--out", required=True, help="JSON result path.")
    args = parser.parse_args(argv)

    try:
        records, images = {}, {}
        for role, pair in (("before", args.before), ("chosen", args.chosen), ("mention", args.mention)):
            records[role], images[role] = avatar(args.session, role, pair[0], pair[1])
        sizes = {tuple(record["crop"]) for record in records.values()}
        if len(sizes) != 1:
            raise FaceError(f"avatar crops differ in size: {sorted(sizes)}; compare equal-size avatars only")
        if images["before"].size[0] != images["before"].size[1]:
            raise FaceError(f"avatar node is not square: {images['before'].size}")
        same = compare(images["mention"], images["chosen"])
        changed = compare(images["chosen"], images["before"])
        checks = [
            {"kind": "same-bot", "between": ["mention", "chosen"],
             "passed": same["mean"] <= SAME_MEAN and same["strongShare"] <= SAME_SHARE,
             "detail": f"mean {same['mean']} <= {SAME_MEAN}, strong share {same['strongShare']} <= {SAME_SHARE}"},
            {"kind": "look-changed", "between": ["chosen", "before"],
             "passed": changed["mean"] >= CHANGED_MEAN,
             "detail": f"mean {changed['mean']} >= {CHANGED_MEAN}"},
        ]
        passed = all(check["passed"] for check in checks)
        result = {"session": args.session, "passed": passed, "avatars": records,
                  "metrics": {"mention-vs-chosen": same, "chosen-vs-before": changed},
                  "checks": checks}
        Path(args.out).write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({key: result[key] for key in ("session", "passed", "metrics")}, separators=(",", ":")))
        if not passed:
            failed = "; ".join(check["detail"] for check in checks if not check["passed"])
            raise SystemExit(f"MENTION_BOT_FACE_FAILED: the row does not draw the chat's own bot ({failed})")
        return 0
    except FaceError as error:
        raise SystemExit(f"MENTION_BOT_FACE_UNREADABLE: {error}")


if __name__ == "__main__":
    sys.exit(main())
