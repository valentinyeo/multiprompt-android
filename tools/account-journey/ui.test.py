import subprocess
import tempfile
import unittest
from pathlib import Path


class SplitSelectionTest(unittest.TestCase):
    def test_selected_checks_selection_not_just_the_visible_label(self):
        tool = Path(__file__).with_name("ui.py")
        for attributes, expected in (('selected="false"', 1), ('selected="true"', 0), ('checkable="true" checked="true"', 0), ('checkable="true" checked="false"', 1)):
            for nested in (False, True):
                with self.subTest(attributes=attributes, nested=nested), tempfile.TemporaryDirectory() as folder:
                    path = Path(folder) / "screen.xml"
                    label = '<node text="Favorites" bounds="[0,0][100,48]" />'
                    path.write_text(f'<hierarchy><node {attributes}>{label}</node></hierarchy>' if nested else f'<hierarchy><node text="Favorites" {attributes} bounds="[0,0][100,48]" /></hierarchy>')
                    result = subprocess.run(["python3", str(tool), str(path), "selected", "Favorites"], capture_output=True)
                    self.assertEqual(expected, result.returncode)
                    result = subprocess.run(["python3", str(tool), str(path), "has", "Favorites"], capture_output=True)
                    self.assertEqual(0, result.returncode)
                    result = subprocess.run(["python3", str(tool), str(path), "selected", "Work"], capture_output=True)
                    self.assertEqual(1, result.returncode)


class LayoutTimingTest(unittest.TestCase):
    def run_move(self, sync_seconds, navigation_seconds):
        source = Path(__file__).with_name("drive.sh").read_text()
        move = source.split('layout "$admin" list | tee "$EVIDENCE/cli-list.txt"\n', 1)[1].split('layout "$member" list', 1)[0]
        with tempfile.TemporaryDirectory() as folder:
            clock = Path(folder) / "clock"
            clock.write_text("0")
            script = r'''
set -euo pipefail
EVIDENCE=$1
sync_seconds=$2
navigation_seconds=$3
admin=fixture
clock="$EVIDENCE/clock"
now() { cat "$clock"; }
advance() { python3 -c 'from pathlib import Path; import sys; p=Path(sys.argv[1]); p.write_text(str(float(p.read_text()) + float(sys.argv[2])))' "$clock" "$1"; }
layout() { advance 0.1; }
wait_text() { :; }
wait_gone() { advance "$sync_seconds"; printf '<hierarchy />' > "$EVIDENCE/current.xml"; }
tap_split() { advance "$navigation_seconds"; }
shot() { :; }
'''
            script += move + '\nopen_seconds=1\n' + source.splitlines()[-1]
            script += '\nprintf "section_seconds=%s\\n" "$section_seconds"\n'
            return subprocess.run(["bash", "-c", script, "timing-test", folder, str(sync_seconds), str(navigation_seconds)], capture_output=True, text=True)

    def test_failed_ui_dump_cannot_count_as_a_removed_row(self):
        source = Path(__file__).with_name("drive.sh").read_text()
        wait = 'wait_gone() {' + source.split('wait_gone() {', 1)[1].split('tap_split() {', 1)[0]
        script = 'set -euo pipefail\ndump_ui() { return 1; }\nhas_text() { return 1; }\n'
        result = subprocess.run(["bash", "-c", script + wait + '\nwait_gone runner-1 10 hasi'], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_driver_navigation_is_not_layout_sync_latency(self):
        result = self.run_move(sync_seconds=4, navigation_seconds=11)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("section_seconds=4.1", result.stdout)

    def test_slow_layout_sync_still_fails_the_ten_second_limit(self):
        result = self.run_move(sync_seconds=11, navigation_seconds=1)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
