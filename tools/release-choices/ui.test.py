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


if __name__ == '__main__':
    unittest.main()
