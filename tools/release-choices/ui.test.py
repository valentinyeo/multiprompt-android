import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class FieldReadbackTest(unittest.TestCase):
    script = Path(os.environ.get('CHOICES_UI', Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/ui.py'))

    def select(self, mode, actual, focused='true'):
        xml = f'<hierarchy><node class="android.widget.EditText" text="{actual}" focused="{focused}" bounds="[0,0][100,100]"><node text="Hostname or IP" /></node></hierarchy>'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ui.xml'
            path.write_text(xml)
            return subprocess.run(['python3', str(self.script), str(path), mode, 'Hostname or IP', '10.0.2.2'], capture_output=True, text=True)

    def test_missing_first_character_rejected(self):
        self.assertNotEqual(self.select('field-value', '0.0.2.2').returncode, 0)

    def test_exact_hostname_accepted(self):
        result = self.select('field-value', '10.0.2.2')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('FIELD_VALUE_VERIFIED', result.stdout)

    def test_unfocused_field_rejected_before_typing(self):
        self.assertNotEqual(self.select('focused-field', '', focused='false').returncode, 0)

    def test_focused_field_accepted_before_typing(self):
        result = self.select('focused-field', '')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '50 50')


if __name__ == '__main__':
    unittest.main()
