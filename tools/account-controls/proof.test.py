import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


HERE = Path(__file__).parent
ROOT = HERE.parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


proof = load('proof', HERE / 'proof.py')
action = load('action', SCRIPTS / 'journey_action.py')
fixture_module = load('account_fixture', SCRIPTS / 'device-approval.py')


class AccountControlsTest(unittest.TestCase):
    def test_hosted_workshop_replay_requires_the_displayed_uppercase_headings(self):
        xml = HERE / 'fixtures/3676f1f3-workshop.xml'
        png = HERE / 'fixtures/3676f1f3-workshop.png'
        expected = dict(text=['Inbox', 'ORIGINAL', 'OTHER'],
                        desc=['Robot verify-session', 'Robot pin-00'], selected=['Workshop'])
        proof.ui.check(xml, png, expected)
        with self.assertRaises(AssertionError):
            proof.ui.check(xml, png, dict(text=['Original', 'Other']))

    def test_row_label_selects_the_right_archive_and_arrows(self):
        xml = '<hierarchy><node bounds="[0,0][1080,1920]">'
        for index, name in enumerate(['Workshop', 'Studio']):
            y = 100 + index * 100
            xml += f'<node bounds="[0,{y}][1080,{y+80}]"><node text="{name}" bounds="[10,{y}][300,{y+80}]" />'
            for x, label in [(400, 'Archive'), (600, '↑'), (800, '↓')]:
                xml += f'<node text="{label}" clickable="true" bounds="[{x},{y}][{x+100},{y+80}]" />'
            xml += '</node>'
        xml += '</node></hierarchy>'
        root = ET.fromstring(xml)
        for label, x in [('Archive', 450), ('↑', 650), ('↓', 850)]:
            self.assertEqual(action.plan(root, 'text', label, row_label='Studio'), f'tap {x} 240')
            with self.assertRaises(action.Unreachable):
                action.plan(root, 'text', label)
        with self.assertRaises(action.Unreachable):
            action.plan(root, 'text', 'Archive', row_label='Missing')
        disabled = ET.fromstring(xml.replace('text="Archive" clickable="true" bounds="[400,200]', 'text="Archive" enabled="false" clickable="true" bounds="[400,200]'))
        with self.assertRaises(action.Unreachable):
            action.plan(disabled, 'text', 'Archive', row_label='Studio')

    def test_row_scope_does_not_hide_an_overlapping_action(self):
        root = ET.fromstring('<hierarchy><node bounds="[0,0][1080,1920]"><node bounds="[0,100][1080,200]"><node text="Studio" bounds="[0,100][300,200]"/><node text="Archive" clickable="true" bounds="[400,100][600,200]"/></node><node clickable="true" bounds="[400,100][600,200]"/></node></hierarchy>')
        with self.assertRaisesRegex(action.Unreachable, 'overlap'):
            action.plan(root, 'text', 'Archive', row_label='Studio')

    def test_server_records_only_authenticated_successful_layout_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            for name in ['pending-device', 'denied-device']:
                (home / f'{name}.pub').write_text('ssh-ed25519 AAAA fixture')
            fixture = fixture_module.ApprovalFixture(home, home, 'account-controls')
            self.assertEqual(fixture.keys, [])
            verifier = 'v' * 43
            fixture.codes['code'] = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
            status, tokens = fixture.request('POST', '/oauth/token', dict(grant_type='authorization_code', code='code', code_verifier=verifier, redirect_uri='dev.multiprompt.companion.debug://auth/callback'), None)
            self.assertEqual(status, 200)
            changed = json.loads(json.dumps(fixture.layout))
            changed['tabs'][0]['workspace'] = 'Studio'
            request = dict(base_revision=0, layout=changed)
            self.assertEqual(fixture.request('PUT', '/v1/layout', request, None)[0], 401)
            auth = 'Bearer ' + tokens['access_token']
            self.assertEqual(fixture.request('PUT', '/v1/layout', request, auth)[0], 200)
            self.assertEqual(fixture.request('PUT', '/v1/layout', request, auth)[0], 409)
            receipt = home / 'account-controls-layout.json'
            proof.layout(receipt, dict(tabs={'verify-session': {'workspace': 'Studio', 'group': 'Original'}}))
            for expected in [dict(tabs={'verify-session': {'workspace': 'Workshop'}}), dict(tabs={'absent': {}}), dict(no_group=['Original']), dict(order=['Studio'])]:
                with self.assertRaises(AssertionError):
                    proof.layout(receipt, expected)
            events = [json.loads(line) for line in (home / 'device-approval-receipts.jsonl').read_text().splitlines()]
            self.assertEqual([event['action'] for event in events], ['browser_pkce_exchange', 'layout_put'])
            self.assertEqual(events[-1]['layout']['tabs'][0]['workspace'], 'Studio')

    def test_ui_rejects_missing_rows_and_unchanged_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            png = home / 'screen.png'
            png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\x00' * 8 + (1080).to_bytes(4, 'big') + (1920).to_bytes(4, 'big') + b'\x00')
            xml = home / 'screen.xml'
            xml.write_text('<hierarchy><node package="dev.multiprompt.companion.debug" bounds="[0,0][1080,1920]"><node package="dev.multiprompt.companion.debug" text="Studio" clickable="true" selected="true" bounds="[0,100][300,180]" /></node></hierarchy>')
            proof.ui.check(xml, png, dict(text=['Studio'], selected=['Studio']))
            with self.assertRaises(AssertionError):
                proof.ui.check(xml, png, dict(text=['Workshop']))
            xml.write_text(xml.read_text().replace('selected="true"', 'selected="false"'))
            with self.assertRaises(AssertionError):
                proof.ui.check(xml, png, dict(selected=['Studio']))


if __name__ == '__main__':
    unittest.main()
