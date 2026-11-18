#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from xml.sax.saxutils import escape

spec = importlib.util.spec_from_file_location('inbox_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class InboxControlsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.xml = self.root / 'screen.xml'
        self.png = self.root / 'screen.png'
        self.png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 1080, 1280) + b'\0')
        self.preferences = self.root / 'preferences.xml'

    def node(self, text, attrs='', bounds='[20,200][900,260]'):
        return f'<node package="dev.multiprompt.companion.debug" text="{escape(text)}" bounds="{bounds}" {attrs}/>'

    def screen(self, body):
        self.xml.write_text('<hierarchy>' + body + '</hierarchy>')

    def check(self, expected):
        return ui.check(self.xml, self.png, expected)

    def test_selected_choices_require_actual_selection(self):
        self.screen('<node clickable="true" selected="true">' + self.node('Large') + '</node>')
        self.check({'selected': ['Large']})
        self.screen('<node clickable="true" selected="false">' + self.node('Large') + '</node>')
        with self.assertRaisesRegex(AssertionError, 'not selected'):
            self.check({'selected': ['Large']})
        with self.assertRaisesRegex(AssertionError, 'Missing'):
            self.check({'selected': ['Small']})

    def test_retained_phone_selection_does_not_require_clickability(self):
        fixture = Path(__file__).parent / 'fixtures/9b263b15-sunlight'
        ui.check(fixture.with_suffix('.xml'), fixture.with_suffix('.png'), {'selected': ['Sunlight', 'Normal']})
        for label in ['System', 'Dark', 'Small', 'Large']:
            with self.assertRaisesRegex(AssertionError, 'not selected'):
                ui.check(fixture.with_suffix('.xml'), fixture.with_suffix('.png'), {'selected': [label]})
        self.xml.write_text(fixture.with_suffix('.xml').read_text().replace('checked="true"', 'checked="false"'))
        with self.assertRaisesRegex(AssertionError, 'not selected'):
            ui.check(self.xml, fixture.with_suffix('.png'), {'selected': ['Sunlight']})

    def test_rows_must_be_fully_inside_the_screenshot_and_scroll_viewport(self):
        for bounds in ['[20,1200][900,1400]', '[20,-20][900,40]', '[0,0][0,0]']:
            self.screen(self.node('Custom time', bounds=bounds))
            with self.assertRaisesRegex(AssertionError, 'Missing visible'):
                self.check({'text': ['Custom time']})
        self.screen('<node scrollable="true" bounds="[0,300][1080,900]">' + self.node('Custom time') + '</node>')
        with self.assertRaisesRegex(AssertionError, 'Missing visible'):
            self.check({'text': ['Custom time']})

    def test_scroll_requires_both_revealed_and_departed_rows(self):
        expected = {'text': ['Custom time'], 'absent': ['In 30 minutes']}
        self.screen(self.node('Custom time'))
        self.check(expected)
        self.screen(self.node('Custom time') + self.node('In 30 minutes'))
        with self.assertRaisesRegex(AssertionError, 'Unexpected'):
            self.check(expected)
        self.screen(self.node('In 30 minutes'))
        with self.assertRaisesRegex(AssertionError, 'Missing'):
            self.check(expected)

    def test_dismissal_requires_the_destination_and_no_sheet(self):
        expected = {'text': ['Inbox'], 'absent': ['Remind me']}
        self.screen(self.node('Inbox'))
        self.check(expected)
        for body in [self.node('Remind me'), self.node('Inbox') + self.node('Remind me')]:
            self.screen(body)
            with self.assertRaises(AssertionError):
                self.check(expected)

    def test_screenshot_and_app_identity_are_required(self):
        self.screen(self.node('Inbox'))
        self.png.write_bytes(b'not a screenshot')
        with self.assertRaisesRegex(AssertionError, 'Missing screenshot'):
            self.check({'text': ['Inbox']})
        self.setUp()
        self.screen(self.node('Inbox').replace('dev.multiprompt.companion.debug', 'com.android.launcher'))
        with self.assertRaisesRegex(AssertionError, 'Missing app'):
            self.check({'text': ['Inbox']})

    def saved(self, due, session='verify-session'):
        self.preferences.write_text(f'<map><long name="archive_until::host::{session}" value="{due}"/></map>')

    def test_both_new_journeys_install_the_existing_host_command_logger(self):
        import os
        import subprocess
        source = (Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/fixture.sh').read_text()
        start = source.index('if [[', source.index('authorized_keys'))
        block = source[start:source.index('\nfi', start) + 3]
        (self.root / 'user-key.pub').write_text('disposable-public-key\n')
        logger = self.root / 'ssh-command'
        for feature in ['settings-controls.md', 'inbox-reminders.md', 'session-routing.md', 'hosts.md']:
            logger.unlink(missing_ok=True)
            shell = 'sudo() { if [[ "$1" == tee ]]; then cat >/dev/null; fi; };\n' + block
            result = subprocess.run(['bash', '-ec', shell], env={**os.environ, 'VERIFY_FEATURE': feature, 'FIXTURE': str(self.root)}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(logger.exists(), feature != 'hosts.md', feature)
            if logger.exists():
                self.assertIn('SSH_ORIGINAL_COMMAND', logger.read_text())
                self.assertIn('>> /home/verifyssh/commands.log', logger.read_text())

    def test_mapping_navigation_reopens_settings_after_activity_resize(self):
        import re
        import subprocess
        source = (Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/inbox-controls-drive.sh').read_text()
        for size, target in [('1080x720', "'Host mapping'"), ('reset', 'Update')]:
            fragment = re.search(r'adb shell wm size ' + size + r'\n.*?tap_text ' + re.escape(target), source, re.S).group()
            shell = '''screen=Settings
adb() { screen=Inbox; }
open_inbox_settings() { [[ "$screen" == Inbox ]]; screen=Settings; }
tap_text() { [[ "$screen" == Settings ]]; }
''' + fragment
            result = subprocess.run(['bash', '-ec', shell], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_reminder_search_reveals_the_title_before_long_pressing(self):
        import subprocess
        import xml.etree.ElementTree as ET
        fixture = Path(__file__).parent / 'fixtures/7b7f76f1-clipped-session.xml'
        nodes = list(ET.parse(fixture).getroot().iter('node'))
        self.assertTrue(any(n.get('content-desc') == 'Robot verify-session' for n in nodes))
        self.assertFalse(any(n.get('text') == 'verify-session' for n in nodes))
        source = (Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/inbox-controls-drive.sh').read_text()
        helper = 'open_inbox_reminder() {' + source.split('open_inbox_reminder() {', 1)[1].split('\n}', 1)[0] + '\n}'
        shell = '''search_open=false
searched=false
select_node() { [[ "$search_open" == true && "$2" == 'Close session search' ]]; }
tap_desc() { [[ "$1" == 'Search sessions' ]]; search_open=true; }
fill() { [[ "$1" == 'Search all sessions' && "$2" == verify-session ]]; searched=true; }
wait_for_text() { [[ "$1" == 'Remind me' || "$searched" == true && "$1" == '1 result across all splits' ]]; }
tap_node() { :; }
hold_text() { [[ "$searched" == true && "$1" == verify-session ]]; }
tap_text() { [[ "$1" == 'Remind Me…' ]]; }
''' + helper + '\nopen_inbox_reminder\nopen_inbox_reminder\n'
        result = subprocess.run(['bash', '-ec', shell], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_relative_deadlines_reject_wrong_duration_and_session(self):
        for duration in [1800, 3600, 7200, 10800, 14400, 18000]:
            self.saved(100000 + duration)
            ui.reminder(self.preferences, '100000', '100030', 'UTC', str(duration))
            self.saved(100000 + duration - 1)
            with self.assertRaisesRegex(AssertionError, 'Wrong relative'):
                ui.reminder(self.preferences, '100000', '100030', 'UTC', str(duration))
        self.saved(101800, 'other-session')
        with self.assertRaisesRegex(AssertionError, 'Missing'):
            ui.reminder(self.preferences, '100000', '100030', 'UTC', '1800')

    def test_calendar_deadlines_reject_wrong_day_and_hour(self):
        from datetime import datetime, timezone
        before = int(datetime(2026, 11, 20, 10, tzinfo=timezone.utc).timestamp())
        for preset, due in [('later', before + 7 * 3600), ('day1', before + 23 * 3600), ('day2', before + 47 * 3600)]:
            self.saved(due)
            ui.reminder(self.preferences, str(before), str(before + 30), 'UTC', preset)
            self.saved(due + 3600)
            with self.assertRaisesRegex(AssertionError, 'Wrong calendar'):
                ui.reminder(self.preferences, str(before), str(before + 30), 'UTC', preset)

    def test_later_today_uses_evening_then_next_morning(self):
        from datetime import datetime, timezone
        for hour, offset in [(18, 3), (22, 11)]:
            before = int(datetime(2026, 11, 20, hour, tzinfo=timezone.utc).timestamp())
            self.saved(before + offset * 3600)
            ui.reminder(self.preferences, str(before), str(before + 30), 'UTC', 'later')


if __name__ == '__main__':
    unittest.main()
