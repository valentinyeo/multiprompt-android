import importlib.util
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, call, patch

spec = importlib.util.spec_from_file_location("app_name_drive", Path(__file__).with_name("drive.py"))
drive = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = drive
spec.loader.exec_module(drive)


class FrozenPackagesTest(unittest.TestCase):
    def test_valid_tables(self):
        self.assertEqual(drive.frozen_packages("\nFrozen packages:\n  (none)\n"), set())
        self.assertEqual(
            drive.frozen_packages(
                f"Frozen packages:\n  package={drive.PACKAGE}, refCounts=1\n"
                "  package=other.package, refCounts=2\n"
            ),
            {drive.PACKAGE, "other.package"},
        )
        self.assertNotIn(
            drive.PACKAGE,
            drive.frozen_packages(
                f"Frozen packages:\n  package={drive.PACKAGE}.other, refCounts=1\n"
            ),
        )

    def test_invalid_tables(self):
        for output in (
            "",
            "(none)",
            "Frozen packages:\n",
            "Frozen packages:\nunknown",
            "Frozen packages:\n  package=other, refCounts=0",
            "Frozen packages:\n  (none)\n  package=other, refCounts=1",
            "Frozen packages:\n  package=other, refCounts=1\n  package=other, refCounts=2",
        ):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                drive.frozen_packages(output)


def update_page(button_enabled):
    root = ET.fromstring(
        f'<hierarchy><node clickable="true" enabled="{button_enabled}">'
        '<node clickable="false" enabled="true" text="Check now"/></node></hierarchy>'
    )
    return root, root[0][0]


class WaitPageTest(unittest.TestCase):
    def proof(self, pages, offered):
        proof = object.__new__(drive.Proof)
        proof.plan = SimpleNamespace(package=drive.PACKAGE, target={"versionName": "target"})
        proof.dump = Mock(side_effect=[(name, root) for name, root in pages])
        proof.has = Mock(side_effect=lambda root, package, value: root is offered or value in (
            "App updates", "Installed: baseline (1)"
        ))
        proof.visible_nodes = Mock(
            side_effect=lambda root, package, value: [(root[0][0], (0, 0, 1, 1))]
        )
        proof.tap = Mock()
        proof.event = Mock()
        return proof

    def test_retries_check_then_requires_update_offer(self):
        baseline = {"versionName": "baseline", "versionCode": 1}
        error, _ = update_page("true")
        offered, _ = update_page("true")
        proof = self.proof([(b"error", error), (b"offered", offered)], offered)
        with patch.object(drive.time, "sleep"):
            self.assertEqual(proof.wait_page(baseline, None, available=True), (b"offered", offered))
        proof.tap.assert_called_once_with(drive.PACKAGE, "Check now")
        proof.event.assert_called_once_with("update-check-retried")

    def test_waits_while_check_button_is_disabled(self):
        baseline = {"versionName": "baseline", "versionCode": 1}
        checking, _ = update_page("false")
        offered, _ = update_page("true")
        proof = self.proof([(b"checking", checking), (b"offered", offered)], offered)
        with patch.object(drive.time, "sleep"):
            self.assertEqual(proof.wait_page(baseline, None, available=True), (b"offered", offered))
        proof.tap.assert_not_called()

    def test_actionable_follows_clickable_ancestor(self):
        proof = object.__new__(drive.Proof)
        root, label = update_page("false")
        self.assertFalse(proof.actionable(root, label))
        root, label = update_page("true")
        self.assertTrue(proof.actionable(root, label))


class WaitInstallTest(unittest.TestCase):
    def test_waits_for_unfreeze_and_fails_closed(self):
        frozen = f"Frozen packages:\n  package={drive.PACKAGE}, refCounts=1\n".encode()
        none = b"Frozen packages:\n  (none)\n"
        info = {"versionCode": 2, "versionName": "target", "uid": 123}
        for second, succeeds in (
            (none, True),
            (b"Frozen packages:\n  package=other.package, refCounts=1\n", True),
            (b"unrecognized", False),
        ):
            with self.subTest(second=second), tempfile.TemporaryDirectory() as directory:
                proof = object.__new__(drive.Proof)
                proof.plan = SimpleNamespace(
                    package=drive.PACKAGE,
                    baseline={"versionCode": 1, "versionName": "baseline"},
                    target={"versionCode": 2, "versionName": "target"},
                )
                proof.evidence = Path(directory)
                proof.download_clicked = True
                proof.check_recording = Mock()
                proof.installed = Mock(return_value=info)
                proof.event = Mock()
                proof.open_update = Mock()
                with patch.object(
                    drive, "adb", side_effect=[SimpleNamespace(stdout=frozen), SimpleNamespace(stdout=second)]
                ) as probe, patch.object(drive.time, "sleep") as sleep:
                    if succeeds:
                        self.assertEqual(proof.wait_install(123), info)
                    else:
                        with self.assertRaises(RuntimeError):
                            proof.wait_install(123)
                self.assertEqual(
                    probe.call_args_list,
                    [call("shell", "dumpsys", "package", "frozen")] * 2,
                )
                self.assertEqual(proof.installed.call_count, 2)
                self.assertEqual(proof.check_recording.call_count, 2)
                sleep.assert_called_once_with(1)
                proof.open_update.assert_not_called()
                self.assertEqual((proof.evidence / "package-freeze.txt").read_bytes(), second)


if __name__ == "__main__":
    unittest.main()
