import re
import subprocess
import sys
from pathlib import Path


SCREENS = {
    "sign-in": ("multiprompt", "sign in"),
    "inbox": ("inbox", "ready for your next prompt"),
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
        raise ValueError(f"{path}: missing expected {screen} text: {missing}; OCR: {text}")
    print(f"NAMED_SCREEN_OK {path.name} {screen}")


def verify_directory(directory):
    for theme in ("dark", "light"):
        for screen in SCREENS:
            verify_image(directory / f"{theme}-{screen}.png", screen)
    print("DAY_THEME_SCREEN_IDENTITY_OK")


if __name__ == "__main__":
    verify_directory(Path(sys.argv[1]))
