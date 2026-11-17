import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('usage_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class UsageUiTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.xml = self.root / 'usage.xml'
        self.png = self.root / 'usage.png'
        self.receipts = self.root / 'ai-usage-receipts.txt'
        self.png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 1080, 1920) + b'\0' * 100)
        status = dict(ts=123, accounts=[
            dict(provider='claude', label='Claude', week=dict(used=37.5, time_gone=50, pace=dict(empty_hours=42)), five_hour=dict(used=12.5)),
            dict(provider='codex', label='Codex', week=dict(used=62.5, time_gone=50, pace=dict(empty_hours=18)), five_hour=dict(used=25)),
        ])
        self.receipts.write_text(json.dumps(dict(command='cat ~/.local/state/token-ladder/status.json', exitCode=0, stdout=json.dumps(status))) + '\n')
        self.texts = ['AI usage', 'Claude', '37.5%', 'BEHIND', 'WEEK 37.5% | 5H 12.5% | EMPTY IN 42H', 'Codex', '62.5%', 'AHEAD', 'WEEK 62.5% | 5H 25% | EMPTY IN 18H']

    def capture(self, texts, phase='open', bounds='[20,200][1000,250]'):
        tree = ET.Element('hierarchy')
        for text in texts:
            ET.SubElement(tree, 'node', text=text, bounds=bounds)
        ET.ElementTree(tree).write(self.xml)
        return ui.check(self.xml, self.png, self.receipts, phase)

    def test_matches_both_ai_numbers_to_the_independent_receipt(self):
        result = self.capture(self.texts)
        self.assertEqual(result['plans']['Claude']['week'], '37.5%')
        self.assertEqual(result['plans']['Codex']['fiveHour'], '25%')

    def test_rejects_swapped_provider_values(self):
        texts = self.texts.copy()
        texts[1], texts[5] = texts[5], texts[1]
        with self.assertRaises(AssertionError):
            self.capture(texts)

    def test_rejects_wrong_week_and_five_hour_numbers(self):
        for old, new in [('37.5%', '0%'), ('5H 25%', '5H 0%')]:
            with self.subTest(old=old), self.assertRaises(AssertionError):
                self.capture([text.replace(old, new) for text in self.texts])

    def test_rejects_missing_or_failed_host_receipts(self):
        for content in ['', json.dumps(dict(command='cat ~/.local/state/token-ladder/status.json', exitCode=1, stdout=''))]:
            self.receipts.write_text(content)
            with self.assertRaises(AssertionError):
                self.capture(self.texts)

    def test_rejects_offscreen_numbers(self):
        with self.assertRaises(AssertionError):
            self.capture(self.texts, bounds='[20,1900][1000,2000]')

    def test_menu_and_close_require_their_visible_states(self):
        self.assertEqual(self.capture(['AI usage', 'Refresh'], 'menu')['phase'], 'menu')
        self.assertEqual(self.capture(['Inbox', 'verify-session'], 'closed')['phase'], 'closed')
        with self.assertRaises(AssertionError):
            self.capture(self.texts, 'closed')


if __name__ == '__main__':
    unittest.main()
