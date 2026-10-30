import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class FieldReadbackTest(unittest.TestCase):
    script = Path(os.environ.get('CHOICES_UI', Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/ui.py'))

    def select(self, mode, actual, focused='true', hint='Hostname or IP', expected='10.0.2.2'):
        child = f'<node text="{hint}" />' if hint else ''
        xml = f'<hierarchy><node class="android.widget.EditText" text="{actual}" focused="{focused}" bounds="[0,0][100,100]">{child}</node></hierarchy>'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ui.xml'
            path.write_text(xml)
            return subprocess.run(['python3', str(self.script), str(path), mode, 'Hostname or IP', expected], capture_output=True, text=True)

    def test_missing_first_character_rejected(self):
        self.assertNotEqual(self.select('field-value', '0.0.2.2').returncode, 0)

    def test_exact_hostname_accepted(self):
        result = self.select('field-value', '10.0.2.2')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('FIELD_VALUE_VERIFIED', result.stdout)

    def test_filled_composer_without_placeholder_accepted(self):
        result = self.select('field-value', 'Continue', hint=None, expected='Continue')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('FIELD_VALUE_VERIFIED', result.stdout)

    def test_unfocused_field_rejected_before_typing(self):
        self.assertNotEqual(self.select('focused-field', '', focused='false').returncode, 0)

    def test_focused_field_accepted_before_typing(self):
        result = self.select('focused-field', '')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '50 50')


class SignedPackageAbsenceTest(unittest.TestCase):
    def probe(self, actual, expected, text='Inbox'):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ui.xml'
            path.write_text(f'<hierarchy><node package="{actual}" text="{text}" /></hierarchy>')
            return subprocess.run(['python3', str(FieldReadbackTest.script), str(path), 'absent', 'Report'],
                env={**os.environ, 'VERIFY_PACKAGE': expected}, capture_output=True, text=True)

    def test_signed_app_absence_accepts_the_signed_app(self):
        result = self.probe('dev.multiprompt.companion', 'dev.multiprompt.companion')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_another_package_cannot_prove_absence(self):
        self.assertNotEqual(self.probe('unrelated.app', 'dev.multiprompt.companion').returncode, 0)

    def test_visible_sheet_still_rejects_absence(self):
        self.assertNotEqual(self.probe('dev.multiprompt.companion', 'dev.multiprompt.companion', 'Report').returncode, 0)


class SshReadinessTest(unittest.TestCase):
    def probe(self, response):
        script = Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/ssh-ready.sh'
        with tempfile.TemporaryDirectory() as folder:
            adb = Path(folder) / 'adb'
            adb.write_text('#!/bin/sh\nprintf "%s\\n" "$RESPONSE"\n')
            adb.chmod(0o700)
            return subprocess.run(['bash', str(script)], env={**os.environ, 'EVIDENCE': folder,
                'PATH': folder + os.pathsep + os.environ['PATH'], 'RESPONSE': response}, capture_output=True, text=True)

    def test_real_ssh_banner_is_required(self):
        result = self.probe('SSH-2.0-OpenSSH_9.9')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('SSH-2.0-', result.stdout)

    def test_http_success_is_not_ssh_reachability(self):
        self.assertNotEqual(self.probe('HTTP/1.1 200 OK').returncode, 0)

    def test_no_banner_is_not_ready(self):
        self.assertNotEqual(self.probe('').returncode, 0)


class RecoveryUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/resync-console.py'
        spec = importlib.util.spec_from_file_location('recovery_fixture', path)
        cls.fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.fixture)

    def row(self, identity, label=''):
        text = self.fixture.TEXTS[identity].rstrip(' ')
        badge = f'<node text="{label}" />' if label else ''
        return f'<node>{badge}<node text="{text}" /></node>'

    def probe(self, rows, identity, phase):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ui.xml'
            path.write_text(f'<hierarchy><node scrollable="true">{rows}</node></hierarchy>')
            return self.fixture.ui_row(path, identity, phase)

    def test_submitted_head_has_sending_before_receipt(self):
        result = self.probe(self.row('recovery-head', 'Sending'), 'recovery-head', 'before')
        self.assertEqual(result['labels'], ['Sending'])
        self.assertEqual(len(self.fixture.TEXTS['recovery-head']), 201)
        self.assertTrue(self.fixture.TEXTS['recovery-head'].endswith(' '))

    def test_settled_row_does_not_require_delivered_badge(self):
        result = self.probe(self.row('accepted-fourth'), 'accepted-fourth', 'after')
        self.assertEqual(result['labels'], [])

    def test_pending_sibling_does_not_mark_receipt_row_pending(self):
        rows = self.row('accepted-fourth') + self.row('pending-one', 'Queued')
        self.assertEqual(self.probe(rows, 'accepted-fourth', 'after')['labels'], [])
        self.assertEqual(self.probe(rows, 'pending-one', 'after')['labels'], ['Queued'])

    def test_stale_receipt_pending_label_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'RESYNC_UI_RECEIPT_STILL_PENDING'):
            self.probe(self.row('accepted-fourth', 'Queued'), 'accepted-fourth', 'after')

    def test_lost_pending_badge_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'RESYNC_UI_PENDING_STATE_WRONG'):
            self.probe(self.row('pending-two'), 'pending-two', 'after')

    def test_duplicate_visible_receipt_body_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'RESYNC_UI_ROW_MISSING_OR_DUPLICATED'):
            self.probe(self.row('accepted-fourth') * 2, 'accepted-fourth', 'after')

    def test_one_lazy_viewport_can_prove_one_row(self):
        result = self.probe(self.row('pending-two', 'Queued'), 'pending-two', 'repeat')
        self.assertEqual(result['id'], 'pending-two')

    def test_draft_readback_preserves_exact_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ui.xml'
            path.write_text('<hierarchy><node class="android.widget.EditText" text="draft stays here" /></hierarchy>')
            self.assertEqual(self.fixture.draft_bytes(path), b'draft stays here')
            path.write_text('<hierarchy><node class="android.widget.EditText" text="draft stays here " /></hierarchy>')
            with self.assertRaisesRegex(ValueError, 'RESYNC_DRAFT_CHANGED'):
                self.fixture.draft_bytes(path)


if __name__ == '__main__':
    unittest.main()
