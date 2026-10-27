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


if __name__ == "__main__":
    unittest.main()
