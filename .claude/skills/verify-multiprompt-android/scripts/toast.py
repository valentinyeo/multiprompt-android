#!/usr/bin/env python3
import sys

from PIL import Image

MIN_NEW_DARK_PIXELS = 2000


def dark_pixels(path):
    image = Image.open(path).convert("L")
    width, height = image.size
    region = image.crop((int(width * 0.2), int(height * 0.82), int(width * 0.8), int(height * 0.9)))
    return sum(1 for value in region.getdata() if value < 90)


settled = dark_pixels(sys.argv[1])
gains = {path: dark_pixels(path) - settled for path in sys.argv[2:]}
print(f"settled dark pixels={settled}")
for path, gain in gains.items():
    print(f"{path}: gain {gain}")
best = max(gains, key=gains.get)
if gains[best] < MIN_NEW_DARK_PIXELS:
    raise SystemExit("No toast pill appeared")
print(f"TOAST_PILL_APPEARED {best}")
