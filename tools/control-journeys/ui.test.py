#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('control_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class ControlUiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.png = self.root / 'state.png'
        self.png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 1080, 1280) + b'\0')
        self.xml = self.root / 'state.xml'

    def write(self, body):
        self.xml.write_text('<hierarchy><node package="dev.multiprompt.companion.debug" text="Bot look">' + body + '</node></hierarchy>')

    def choice(self, label, selected='true', bounds='[40,100][180,240]'):
        return f'<node package="dev.multiprompt.companion.debug" content-desc="{label}" clickable="true" selected="{selected}" bounds="{bounds}"/>'

    def test_retained_android_checked_choice_on_clickable_parent(self):
        fixture = Path(__file__).parent / 'fixtures/aeb9153c-face-1'
        self.assertEqual(ui.check(fixture.with_suffix('.xml'), fixture.with_suffix('.png'), 'face', '1')['selected'], ['Face 1'])
        with self.assertRaises(AssertionError):
            ui.check(fixture.with_suffix('.xml'), fixture.with_suffix('.png'), 'face', '2')
        self.xml.write_text(fixture.with_suffix('.xml').read_text().replace('checked="true"', 'checked="false"'))
        with self.assertRaises(AssertionError):
            ui.check(self.xml, fixture.with_suffix('.png'), 'face', '1')

    def test_chosen_face_is_selected_and_visible(self):
        self.write(self.choice('Face 7') + self.choice('Face 6', 'false'))
        self.assertEqual(ui.check(self.xml, self.png, 'face', '7')['selected'], ['Face 7'])
        for selected, bounds in [('false', '[40,100][180,240]'), ('true', '[40,1200][180,1400]')]:
            self.write(self.choice('Face 7', selected, bounds))
            with self.assertRaises(AssertionError):
                ui.check(self.xml, self.png, 'face', '7')

    def test_wrong_or_multiple_choices_fail(self):
        for body in [self.choice('Face 8'), self.choice('Face 7') + self.choice('Face 8')]:
            self.write(body)
            with self.assertRaises(AssertionError):
                ui.check(self.xml, self.png, 'face', '7')

    def test_auto_must_clear_explicit_colour(self):
        auto = '<node package="dev.multiprompt.companion.debug" text="Auto" clickable="true" selected="true" bounds="[40,100][180,240]"/>'
        self.write(auto + self.choice('Colour 8', 'false'))
        ui.check(self.xml, self.png, 'auto')
        self.write(auto + self.choice('Colour 8'))
        with self.assertRaises(AssertionError):
            ui.check(self.xml, self.png, 'auto')

    def test_scroll_requires_newly_revealed_rows(self):
        auto = '<node package="dev.multiprompt.companion.debug" text="Auto" bounds="[40,900][180,950]"/>'
        self.write(self.choice('Face 1', 'false'))
        ui.check(self.xml, self.png, 'scroll-top')
        self.write(auto)
        ui.check(self.xml, self.png, 'scroll-bottom')
        self.write(auto + self.choice('Face 1', 'false'))
        for phase in ('scroll-top', 'scroll-bottom'):
            with self.assertRaises(AssertionError):
                ui.check(self.xml, self.png, phase)

    def test_rename_requires_exact_editor_value(self):
        self.xml.write_text('<hierarchy><node package="dev.multiprompt.companion.debug" text="Rename session">'
                            '<node package="dev.multiprompt.companion.debug" class="android.widget.EditText" text="changed"/>'
                            '<node package="dev.multiprompt.companion.debug" text="Cancel"/>'
                            '<node package="dev.multiprompt.companion.debug" text="Rename"/></node></hierarchy>')
        ui.check(self.xml, self.png, 'rename-draft', 'changed')
        with self.assertRaises(AssertionError):
            ui.check(self.xml, self.png, 'rename-draft', 'old')

    def test_reader_rejects_open_sheet(self):
        self.xml.write_text('<hierarchy><node package="dev.multiprompt.companion.debug" text="verify-session" content-desc="Session actions"/></hierarchy>')
        ui.check(self.xml, self.png, 'reader', 'verify-session')
        self.write('<node package="dev.multiprompt.companion.debug" text="verify-session" content-desc="Session actions"/>')
        with self.assertRaises(AssertionError):
            ui.check(self.xml, self.png, 'reader', 'verify-session')


if __name__ == '__main__':
    unittest.main()
