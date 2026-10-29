import subprocess
import tempfile
import unittest
from pathlib import Path


class TerminalConsoleTest(unittest.TestCase):
    def test_full_history_and_independent_incoming_receipt(self):
        root = Path(__file__).resolve().parents[1]
        script = root / '.claude/skills/verify-multiprompt-android/scripts/terminal-console.py'
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / 'receipts.txt'
            result = subprocess.run(['python3', str(script), str(receipt)], input='SHELL_NEW_CLOSED\nSHELL_NEW_OPEN\n', text=True, capture_output=True, check=True)
            self.assertEqual(result.stdout.splitlines(), [f'SHELL_HISTORY_{i:04}' for i in range(1, 501)] + ['VERIFY_READY_528', 'SHELL_NEW_CLOSED', 'SHELL_NEW_OPEN'])
            self.assertEqual(receipt.read_text().splitlines(), ['SHELL_NEW_CLOSED', 'SHELL_NEW_OPEN'])

    def test_history_swipe_stays_inside_open_and_closed_keyboard_viewports(self):
        root = Path(__file__).resolve().parents[1]
        script = root / '.claude/skills/verify-multiprompt-android/scripts/ui.py'
        for bottom in (1794, 1100):
            with self.subTest(bottom=bottom), tempfile.TemporaryDirectory() as directory:
                xml = Path(directory) / 'ui.xml'
                xml.write_text(f'<hierarchy><node package="dev.multiprompt.companion.debug" scrollable="true" bounds="[0,231][1080,{bottom}]" /></hierarchy>')
                result = subprocess.run(['python3', str(script), str(xml), 'scroll-back', ''], text=True, capture_output=True, check=True)
                x1, y1, x2, y2 = map(int, result.stdout.split())
                self.assertEqual((x1, x2), (540, 540))
                self.assertGreater(y1, 231)
                self.assertLess(y2, bottom)
                self.assertGreater(y2 - y1, (bottom - 231) // 2)

    def test_numeric_field_clear_replaces_existing_digits_without_control_keys(self):
        root = Path(__file__).resolve().parents[1]
        script = root / '.claude/skills/verify-multiprompt-android/scripts/ui.py'
        for text in ('22', '22222222222222', ''):
            with self.subTest(text=text), tempfile.TemporaryDirectory() as directory:
                xml = Path(directory) / 'ui.xml'
                xml.write_text(f'<hierarchy><node class="android.widget.EditText" focused="true" text="{text}" bounds="[0,0][100,100]"><node text="Port" /></node></hierarchy>')
                result = subprocess.run(['python3', str(script), str(xml), 'clear-field', 'Port'], text=True, capture_output=True, check=True)
                self.assertEqual(result.stdout.split(), ['123'] + ['67'] * len(text))


if __name__ == '__main__':
    unittest.main()
