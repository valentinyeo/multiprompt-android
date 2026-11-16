import os
import runpy
import subprocess
import sys
from unittest.mock import patch
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

    def test_terminal_focus_and_key_row_swipes_use_visible_bounds(self):
        script = Path(__file__).resolve().parents[1] / '.claude/skills/verify-multiprompt-android/scripts/ui.py'
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / 'ui.xml'
            xml.write_text('<hierarchy><node content-desc="Show keyboard" bounds="[900,40][1000,140]" /><node scrollable="true" bounds="[0,1000][1080,1100]"><node content-desc="Esc key" bounds="[10,1010][100,1090]" /></node></hierarchy>')
            for mode, value, expected in [('terminal', '', '505 575'), ('key-row', 'Esc key', '216 1050 864 1050')]:
                result = subprocess.run(['python3', str(script), str(xml), mode, value], text=True, capture_output=True, check=True)
                self.assertEqual(result.stdout.strip(), expected)

    def test_shell_input_proof_rejects_echoes_stale_history_and_running_sleep(self):
        script = Path(__file__).resolve().parents[1] / '.claude/skills/verify-multiprompt-android/scripts/terminal-proof.py'
        cases = [
            ('typed', 'VERIFY_SHELL> echo TYPED_$((40+2))\nTYPED_42\nVERIFY_SHELL> ', 1, True),
            ('typed', 'VERIFY_SHELL> echo TYPED_42\nVERIFY_SHELL> ', 1, False),
            ('typed', 'TYPED_42\nTYPED_42\nVERIFY_SHELL> ', 1, False),
            ('interrupt', 'VERIFY_SHELL> sleep 300\n^C\nVERIFY_SHELL> ', 1, True),
            ('interrupt', 'VERIFY_SHELL> sleep 300\n^C\nVERIFY_SHELL> ', 0, False),
            ('interrupt', 'VERIFY_SHELL> sleep 300\n^C\n', 1, False),
            ('interrupt', 'VERIFY_SHELL> ', 1, False),
            ('history', 'TYPED_42\nTYPED_42\nVERIFY_SHELL> ', 1, True),
            ('history', 'TYPED_42\nVERIFY_SHELL> ', 1, False),
            ('tab', 'VERIFY_SHELL> ls /tmp/verify_tabfile_unique.txt\n/tmp/verify_tabfile_unique.txt\nVERIFY_SHELL> ', 1, True),
            ('tab', 'VERIFY_SHELL> ls /tmp/verify_tabfile_unique.txt\nVERIFY_SHELL> ', 1, False),
        ]
        for step, pane, process_code, accepted in cases:
            with self.subTest(step=step, pane=pane, process_code=process_code), tempfile.TemporaryDirectory() as directory:
                with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(sys, 'argv', [str(script), step, directory]), patch('subprocess.check_output', return_value=pane), patch('subprocess.run', return_value=subprocess.CompletedProcess([], process_code, '123\n' if process_code == 0 else '', '')), patch('time.monotonic', side_effect=[0, 6]):
                    if accepted:
                        runpy.run_path(str(script), run_name='__main__')
                        self.assertIn(f'SHELL_INPUT_{step.upper()}_OK', (Path(directory) / 'shell-input-result.txt').read_text())
                    else:
                        with self.assertRaises(SystemExit):
                            runpy.run_path(str(script), run_name='__main__')
                        self.assertFalse((Path(directory) / 'shell-input-result.txt').exists())

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
