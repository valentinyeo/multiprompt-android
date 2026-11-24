import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

TOOL = Path(__file__).with_name("layout.py")
SEEDED = {"workspaces": ["Studio"], "tabs": {"verify-session": {"workspace": "Studio", "group": "Original"}}, "group_order": None}


def layout(workspaces, tabs, group_order=None):
    document = {
        "schema": 1,
        "revision": 3,
        "workspaces": [{"name": name} for name in workspaces],
        "tabs": [
            {"kind": "tmux", "name": session, "tmux_session": session, "tmux_host": 1, "workspace": workspace, "group": group}
            for session, workspace, group in tabs
        ],
    }
    if group_order is not None:
        document["group_order"] = group_order
    return document


def preferences(path, states):
    root = ET.Element("map")
    for key, state in states.items():
        ET.SubElement(root, "string", {"name": key}).text = json.dumps(state, separators=(",", ":"))
    ET.ElementTree(root).write(path)
    return path


def receipts(path, events):
    path.write_text("\n".join(json.dumps(event, separators=(",", ":")) for event in events) + "\n")
    return path


def run(*arguments):
    return subprocess.run([sys.executable, "-B", str(TOOL), *map(str, arguments)], capture_output=True, text=True)


class AccountLayoutTest(unittest.TestCase):
    def test_prefs_checks_the_edited_layout_not_the_pulled_base(self):
        with tempfile.TemporaryDirectory() as directory:
            path = preferences(Path(directory) / "account_layout.xml", {
                "host::1": "host-id",
                "layout::fixture-account": {"base": layout(["Studio"], [("verify-session", "Studio", "Original")]),
                                            "edited": layout(["Studio", "Review"], [("verify-session", "Studio", "Renamed")])},
            })
            result = run("prefs", path, json.dumps({"workspaces": ["Studio", "Review"], "tabs": {"verify-session": {"workspace": "Studio", "group": "Renamed"}}, "group_order": None}))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), {"workspaces": ["Studio", "Review"], "tabs": {"verify-session": {"workspace": "Studio", "group": "Renamed"}}, "group_order": None})
            self.assertEqual(run("prefs", path, json.dumps(SEEDED)).returncode, 1)

    def test_missing_preference_fails_instead_of_passing_an_empty_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            path = preferences(Path(directory) / "account_layout.xml", {"host::1": "host-id"})
            result = run("prefs", path, json.dumps(SEEDED))
            self.assertEqual(result.returncode, 1)
            self.assertIn("ACCOUNT_LAYOUT_MISSING", result.stderr)

    def test_receipt_accepts_any_recorded_push_and_reports_a_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            path = receipts(Path(directory) / "receipts.txt", [
                {"action": "browser_pkce_exchange", "verified": True},
                {"action": "layout_put", "layout": layout(["Studio"], [("verify-session", "Studio", "Original")])},
                {"action": "layout_put", "layout": layout(["Studio", "Review"], [("verify-session", "Studio", "Renamed"), ("pin-00", "Studio", "Other")], ["Renamed", "Other"])},
            ])
            expected = {"workspaces": ["Studio", "Review"], "tabs": {"verify-session": {"workspace": "Studio", "group": "Renamed"}, "pin-00": {"workspace": "Studio", "group": "Other"}}, "group_order": ["Renamed", "Other"]}
            result = run("receipt", path, json.dumps(expected))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), expected)
            missing = run("receipt", path, json.dumps({"workspaces": ["Discarded"], "tabs": {}, "group_order": None}))
            self.assertEqual(missing.returncode, 1)
            self.assertIn("ACCOUNT_LAYOUT_MISMATCH", missing.stderr)

    def test_receipts_without_a_push_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = receipts(Path(directory) / "receipts.txt", [{"action": "browser_pkce_exchange", "verified": True}])
            result = run("receipt", path, json.dumps(SEEDED))
            self.assertEqual(result.returncode, 1)
            self.assertIn("ACCOUNT_LAYOUT_PUT_MISSING", result.stderr)


if __name__ == "__main__":
    unittest.main()
