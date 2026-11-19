#!/usr/bin/env python3
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PROFILES = '[{"id":"7a9d3f18-2781-4c1a-9f5e-2f7d0a0b0c0d","label":"Verify","hostname":"10.0.2.2","port":2222,"username":"verifyssh","keySecretId":"verify-local-key","passphraseSecretId":"","hostKeyType":"ssh-ed25519","hostKeyFingerprint":"SHA256:verify","dismissedHostKeyFingerprint":"","hostKeys":[]}]'
PREFS = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"profiles_json\">" +
         PROFILES.replace('&', '&amp;').replace('"', '&quot;').replace('<', '&lt;').replace('>', '&gt;') +
         "</string>\n</map>\n")


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


phone_cache = load('real_chat_phone_cache', HERE / 'phone-cache.py')
fixture = load('real_chat_fixture', HERE / 'fixture.py')


class PhoneCacheTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.manifest = fixture.create(self.home)
        self.prefs = self.home / 'hosts.xml'
        self.prefs.write_text(PREFS)

    def test_reads_the_trusted_host_from_the_app_preferences(self):
        record = phone_cache.host(self.prefs)
        self.assertEqual('7a9d3f18-2781-4c1a-9f5e-2f7d0a0b0c0d', record['id'])
        self.assertEqual('10.0.2.2', record['hostname'])
        self.assertEqual('2222', record['port'])
        self.assertEqual('verifyssh', record['username'])

    def test_prepare_writes_the_older_position_the_app_will_restore(self):
        payload = phone_cache.prepare(self.prefs, HERE / 'fixture.py', self.home, self.home / 'out',
                                      fixture.SESSION, 'CLAUDE')
        target = Path(payload['path'])
        self.assertTrue(target.is_file())
        self.assertEqual(payload['name'], target.name)
        self.assertEqual(fixture.FIRST, payload['sid'])
        self.assertEqual(fixture.CACHE_ROWS, payload['rows'])
        self.assertEqual(fixture.REPLY_NEWEST, payload['latest'])
        lines = target.read_text().splitlines()
        header = json.loads(lines[0])
        self.assertEqual(6, header['version'])
        self.assertEqual(fixture.FIRST, header['sid'])
        rows = [json.loads(line) for line in lines[1:]]
        self.assertEqual(fixture.STALE_FIRST, rows[-1]['text'])
        self.assertLess(header['start'], header['offset'])
        self.assertEqual(rows[-1]['end'], header['offset'])
        self.assertEqual(len(rows), fixture.CACHE_ROWS)

    def test_cli_prints_the_prepared_name(self):
        output = subprocess.run([sys.executable, '-B', str(HERE / 'phone-cache.py'), 'prepare',
                                 '--prefs', str(self.prefs), '--fixture', str(HERE / 'fixture.py'),
                                 '--home', str(self.home), '--out-dir', str(self.home / 'out')],
                                check=True, capture_output=True, text=True)
        payload = json.loads(output.stdout)
        self.assertTrue(Path(payload['path']).is_file())
        self.assertEqual('verifyssh', payload['host']['username'])

    def test_missing_hosts_preference_fails_loudly(self):
        empty = self.home / 'empty.xml'
        empty.write_text("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map></map>")
        with self.assertRaisesRegex(ValueError, 'HOSTS_MISSING'):
            phone_cache.host(empty)
        no_host = self.home / 'no-host.xml'
        no_host.write_text("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"hosts\">[]</string></map>")
        with self.assertRaisesRegex(ValueError, 'HOSTS_MISSING'):
            phone_cache.host(no_host)
        self.assertTrue(ET.parse(str(self.prefs)).getroot().tag == 'map')


if __name__ == '__main__':
    unittest.main()
