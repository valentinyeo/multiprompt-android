from collections import Counter
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SCOPE = "app/src/main/"
UI = "app/src/main/java/dev/multiprompt/companion/ui/"
KIT = UI + "DesignKit.kt"
THEME = UI + "Theme.kt"
EXEMPT = {KIT, THEME}
CODE = "KIT_MATERIAL_BYPASSED"

COMPONENTS = {
    "AlertDialog": "KitSheet with KitPrimaryButton/KitSecondaryButton",
    "BasicAlertDialog": "KitSheet with KitPrimaryButton/KitSecondaryButton",
    "DropdownMenu": "KitMenu with KitMenuRow, or KitSheet for a full-screen menu",
    "ExposedDropdownMenu": "KitMenu with KitMenuRow",
    "DropdownMenuItem": "KitMenuRow",
    "Button": "KitPrimaryButton",
    "ElevatedButton": "KitPrimaryButton",
    "OutlinedButton": "KitSecondaryButton",
    "TextButton": "KitSecondaryButton",
    "IconButton": "KitRoundButton",
    "FloatingActionButton": "KitRoundButton",
    "SmallFloatingActionButton": "KitRoundButton",
    "LargeFloatingActionButton": "KitRoundButton",
    "ExtendedFloatingActionButton": "KitSecondaryButton",
    "FilledIconButton": "KitRoundButton",
    "OutlinedIconButton": "KitRoundButton",
    "IconToggleButton": "KitToggle",
    "FilledIconToggleButton": "KitToggle",
    "OutlinedIconToggleButton": "KitToggle",
    "Switch": "KitToggle",
    "ModalBottomSheet": "KitSheet",
}

MATERIAL_IMPORT = re.compile(r"^\s*import\s+androidx\.compose\.material3?\.")
TONAL_CONTROL = re.compile(r"(?<![.\w])FilledTonal\w*\s*\(")
PURPLE_NAME = re.compile(r"\b[Pp]urple[A-Za-z0-9_]*")
PURPLE_LITERALS = {
    "6750a4", "d0bcff", "ccc2dc", "efb8c8", "21005d", "381e72", "1d192b", "4a4458",
    "e8def8", "625b71", "7d5260", "eaddff", "ffd8e4", "f3edf7", "fffbfe",
}
HEX_LITERAL = re.compile(r"0[xX]([0-9a-fA-F]{8})")
TONAL_ELEVATION = re.compile(r"\btonalElevation\s*=")


def component_calls(name):
    return re.compile(r"(?<![.\w])" + name + r"\s*\(")


def files(root=ROOT, ref=None):
    if ref:
        base = str(root)
        names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref, SCOPE],
                                        cwd=base, text=True).splitlines()
        return {name: subprocess.check_output(["git", "show", f"{ref}:{name}"], cwd=base, text=True)
                for name in names if name.endswith(".kt")}
    folder = root / SCOPE
    return {p.relative_to(root).as_posix(): p.read_text() for p in folder.rglob("*.kt")}


def violations(sources):
    found = Counter()
    for name, source in sources.items():
        if not name.startswith(SCOPE) or not name.endswith(".kt") or name in EXEMPT:
            continue
        for line in source.splitlines():
            if not MATERIAL_IMPORT.match(line):
                continue
            symbol = line.rsplit(".", 1)[-1].strip()
            if symbol in COMPONENTS:
                found[f"{name}: {symbol}"] += 1
            elif symbol.startswith("FilledTonal"):
                found[f"{name}: tonal-fill"] += 1
        for symbol in COMPONENTS:
            calls = len(component_calls(symbol).findall(source))
            if calls:
                found[f"{name}: {symbol}"] += calls
        for rule, key in ((TONAL_CONTROL, "tonal-fill"), (TONAL_ELEVATION, "tonal-elevation"),
                          (PURPLE_NAME, "purple")):
            hits = len(rule.findall(source))
            if hits:
                found[f"{name}: {key}"] += hits
        for digits in HEX_LITERAL.findall(source):
            if digits[2:].lower() in PURPLE_LITERALS:
                found[f"{name}: purple"] += 1
    return found


def check(root=ROOT, ref=None):
    found = violations(files(root, ref))
    if found:
        raise ValueError(
            CODE + ": " + "; ".join(sorted(f"{k} ({v})" for k, v in found.items()))
            + ". Menus and dialogs are KitSheet/KitMenu with KitMenuRow, switches and toggles are KitToggle, "
            "buttons are KitPrimaryButton/KitSecondaryButton/KitRoundButton, and the kit carries no tonal fill "
            "or purple colour. Move the screen onto ui/DesignKit.kt instead of the Material control."
        )
    print(f"{CODE}_OK: " + str(sum(found.values())))


if __name__ == "__main__":
    try:
        check(ref=sys.argv[1] if len(sys.argv) > 1 else None)
    except ValueError as error:
        sys.exit(str(error))
