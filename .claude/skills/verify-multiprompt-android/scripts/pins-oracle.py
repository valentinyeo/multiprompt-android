#!/usr/bin/env python3
import json
import re
import sys
import xml.etree.ElementTree as ET

mode, path, package, *args = sys.argv[1:]
if package not in ("dev.multiprompt.companion", "dev.multiprompt.companion.m3"):
    raise SystemExit("PINS_INVALID_PACKAGE")
root = ET.parse(path).getroot()
nodes = list(root.iter("node"))
if not nodes or not any(n.get("package") == package for n in nodes):
    raise SystemExit("PINS_APP_NOT_VISIBLE")
parents = {child: parent for parent in root.iter() for child in parent}
pattern = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")

def raw_bounds(node):
    match = pattern.fullmatch(node.get("bounds", ""))
    if not match:
        raise ValueError("invalid bounds")
    return tuple(map(int, match.groups()))

app_roots = [n for n in nodes if n.get("package") == package
             and (parents.get(n) is root or parents.get(n) not in nodes
                  or parents.get(n).get("package") != package)]
if not app_roots:
    raise SystemExit("PINS_SCREEN_BOUNDS_MISSING")
screen = raw_bounds(app_roots[0])
if screen[2] <= screen[0] or screen[3] <= screen[1]:
    raise SystemExit("PINS_SCREEN_BOUNDS_INVALID")

def visible(n):
    if n.get("package") != package or n.get("visible-to-user") == "false":
        return False
    try:
        x1, y1, x2, y2 = raw_bounds(n)
    except ValueError:
        return False
    return (screen[0] <= x1 < x2 <= screen[2]
            and screen[1] <= y1 < y2 <= screen[3])

def named(name):
    return [n for n in nodes if visible(n)
            and (n.get("text") == name or n.get("content-desc") == name)]

pin_names = [f"pin-{i:02d}" for i in range(12)]


def one(name):
    matches = named(name)
    if len(matches) != 1:
        raise ValueError("expected one visible " + name)
    return matches[0]

def header():
    return {name: list(raw_bounds(one(name))) for name in
            ("Inbox", "Search sessions", "New session", "Session list actions")}

def clickable(name):
    n = one(name)
    while n is not None and n is not root:
        if n.get("clickable") == "true" and visible(n):
            return n
        n = parents.get(n)
    raise ValueError("no visible clickable ancestor for " + name)

try:
    if mode == "guard":
        print("PINS_PACKAGE_VISIBLE")
    elif mode == "header":
        print(json.dumps(header(), sort_keys=True))
    elif mode == "top":
        controls = header()
        top = max(rect[3] for rect in controls.values())
        bottom = screen[3]
        for name in ("Frequent", "All", "Favorites"):
            try:
                tab = raw_bounds(clickable(name))
            except ValueError as error:
                raise ValueError("invalid fixed tab " + name + ": " + str(error))
            if tab[1] + tab[3] < screen[1] + screen[3]:
                top = max(top, tab[3])
            else:
                bottom = min(bottom, tab[1])
        if top >= bottom:
            raise ValueError("invalid content viewport after fixed tabs")
        complete = False
        for name in pin_names:
            if named(name):
                try:
                    pin = raw_bounds(clickable(name))
                except ValueError:
                    continue
                if top <= pin[1] and pin[3] <= bottom:
                    complete = True
                    break
        if not complete:
            raise ValueError("no visible clickable pin fully inside content viewport")
        print("PINS_TOP_OK")
    elif mode == "pin-bounds":
        if len(args) != 1 or (args[0] != "any" and args[0] not in pin_names):
            raise ValueError("invalid pin")
        if args[0] == "any":
            pin = None
            for name in pin_names:
                if named(name):
                    try:
                        pin = clickable(name)
                        break
                    except ValueError:
                        pass
            if pin is None:
                raise ValueError("no visible clickable pin")
        else:
            pin = clickable(args[0])
        x1, y1, x2, y2 = raw_bounds(pin)
        y = (y1 + y2) // 2
        print(screen[0] + (screen[2] - screen[0]) // 8, y,
              screen[0] + (screen[2] - screen[0]) * 7 // 8, y)
    elif mode == "swipe-area":
        controls = header()
        top = max(rect[3] for rect in controls.values())
        height = screen[3] - top
        if height < 100:
            raise ValueError("list viewport too small")
        print((screen[0] + screen[2]) // 2, top + height // 4, top + height * 4 // 5)
    elif mode == "reader":
        if not any(n.get("class") == "android.widget.EditText" and visible(n)
                   and any(visible(child)
                           and (child.get("text") == "Message pin-11"
                                or child.get("content-desc") == "Message pin-11")
                           for child in n.iter("node")) for n in nodes):
            raise ValueError("reader placeholder missing")
        print("PINS_READER_OK")
    elif mode == "scrolled":
        if len(args) != 1:
            raise ValueError("header JSON required")
        with open(args[0], encoding="utf-8") as source:
            previous = json.load(source)
        if header() != previous:
            raise ValueError("header moved")
        if not any(visible(n) and re.fullmatch(r"row-(?:0[0-9]|1[0-9]|2[0-7])",
                                              n.get("text", "")) for n in nodes):
            raise ValueError("fixture row missing")
        if any(named(name) for name in pin_names):
            raise SystemExit("PINNED_STRIP_MUST_SCROLL")
        print("PINS_SCROLLED_OK")
    else:
        raise ValueError("unknown mode")
except (ValueError, OSError, json.JSONDecodeError) as error:
    raise SystemExit("PINS_ORACLE_FAILED: " + str(error))
