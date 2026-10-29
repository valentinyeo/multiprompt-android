import re
import subprocess
import sys
import tempfile
from PIL import Image
from pathlib import Path


SCREENS = {
    "sign-in": ("multiprompt", "sign in"),
    "inbox": ("inbox", "session 0", "session 1"),
    "menu": ("settings", "arrange splits"),
    "settings": ("session list settings", "default reader font size"),
    "chat": ("message 0", "claude reader"),
}


def verify_image(path, screen):
    result = subprocess.run(
        ["tesseract", str(path), "stdout"], check=True, capture_output=True, text=True
    )
    text = " ".join(re.findall(r"[a-z0-9]+", result.stdout.lower()))
    missing = [label for label in SCREENS[screen] if label not in text]
    if missing:
        with Image.open(path) as image:
            for width_fraction in (.4, .8):
                with tempfile.NamedTemporaryFile(suffix=".png") as header:
                    image.crop((0, int(image.height * .03), int(image.width * width_fraction), int(image.height * .13))).save(header.name)
                    result = subprocess.run(
                        ["tesseract", header.name, "stdout", "--psm", "6"], check=True, capture_output=True, text=True
                    )
                    text += " " + " ".join(re.findall(r"[a-z0-9]+", result.stdout.lower()))
                missing = [label for label in SCREENS[screen] if label not in text]
                if not missing:
                    break
    if missing and screen == "menu":
        with Image.open(path) as image, tempfile.NamedTemporaryFile(suffix=".png") as menu:
            image.crop((int(image.width * .44), int(image.height * .1), image.width, int(image.height * .7))).save(menu.name)
            result = subprocess.run(
                ["tesseract", menu.name, "stdout", "--psm", "6"], check=True, capture_output=True, text=True
            )
            text += " " + " ".join(re.findall(r"[a-z0-9]+", result.stdout.lower()))
            missing = [label for label in SCREENS[screen] if label not in text]
    if missing:
        raise ValueError(f"{path}: missing expected {screen} text: {missing}; OCR: {text}")
    print(f"NAMED_SCREEN_OK {path.name} {screen}")


def verify_directory(directory):
    for theme in ("dark", "light"):
        for screen in SCREENS:
            verify_image(directory / f"{theme}-{screen}.png", screen)
    print("DAY_THEME_SCREEN_IDENTITY_OK")


if __name__ == "__main__":
    verify_directory(Path(sys.argv[1]))
