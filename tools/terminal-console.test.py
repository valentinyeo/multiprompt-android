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


if __name__ == '__main__':
    unittest.main()
