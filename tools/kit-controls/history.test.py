from pathlib import Path
import hashlib
import json
import os
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = Path(__file__).with_name("src") / "test" / "resources"
BASELINE = "tools/kit-controls/src/main/resources/kit-controls-baseline.properties"


def inventory(text):
    entries = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        key, count = line.rsplit("=", 1)
        if key in entries or int(count) < 1:
            raise ValueError("KIT_BASELINE_INVALID: use one positive count per resolved symbol and enclosing function")
        entries[key] = int(count)
    return entries


class KitHistoryTest(unittest.TestCase):
    def test_fixtures_are_exact_controls_removed_by_the_named_cleanup(self):
        for case in json.loads((RESOURCES / "history.json").read_text()):
            with self.subTest(pr=case["pr"]):
                fragment = (RESOURCES / case["fixture"]).read_bytes()
                self.assertEqual(hashlib.sha256(fragment).hexdigest(), case["sha256"])
                before = subprocess.check_output(["git", "show", f'{case["ref"]}:{case["path"]}'], cwd=ROOT)
                after = subprocess.check_output(["git", "show", f'{case["cleanup"]}:{case["path"]}'], cwd=ROOT)
                self.assertIn(fragment, before)
                self.assertNotIn(fragment, after)

    def test_baseline_cannot_add_allowances(self):
        current = inventory((ROOT / BASELINE).read_text())
        base = os.environ.get("BASE_SHA") or "origin/main"
        old = subprocess.run(["git", "show", f"{base}:{BASELINE}"], cwd=ROOT, capture_output=True, text=True)
        if old.returncode:
            exists = subprocess.run(["git", "cat-file", "-e", base], cwd=ROOT, capture_output=True)
            self.assertEqual(exists.returncode, 0, "KIT_BASELINE_BASE_MISSING: fetch the base revision")
        previous = inventory(old.stdout) if old.returncode == 0 else {}
        for key, count in current.items():
            self.assertLessEqual(count, previous.get(key, 0), "KIT_CONTROL_REQUIRED: migrate to ui/DesignKit.kt instead of adding a baseline allowance")

    def test_inventory_keys_retain_counts_and_function_identity(self):
        self.assertEqual(inventory("material.AlertDialog|screen.Example()=2\n"), {"material.AlertDialog|screen.Example()": 2})
        with self.assertRaises(ValueError):
            inventory("same=1\nsame=2\n")


if __name__ == "__main__":
    unittest.main()
