import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


class SendFrameCaptureTest(unittest.TestCase):
    def capture(self, milliseconds):
        driver = (SCRIPTS / 'drive.sh').read_text()
        start = driver.index("tap_desc 'Send prompt'\n", driver.index("fill 'Message verify-session' VERIFY_SENT_528"))
        end = driver.index("wait_receipt 'VERIFY_RECEIVED:VERIFY_SENT_528'", start)
        segment = driver[start:end]
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            (evidence / 'input.xml').write_text('<hierarchy><node class="android.widget.EditText" text=""/><node class="android.widget.TextView" text="VERIFY_SENT_528"/></hierarchy>')
            (evidence / 'device-log.txt').write_text(f'I SendTiming: message_added +5ms\nI SendTiming: box_cleared_frame +{milliseconds}ms\n')
            adb = evidence / 'adb'
            adb.write_text(textwrap.dedent('''\
                #!/usr/bin/env python3
                import os
                from pathlib import Path
                import sys
                root = Path(os.environ['EVIDENCE'])
                args = sys.argv[1:]
                assert args[0] == 'logcat', args
                with (root / 'calls.txt').open('a') as output:
                    output.write(' '.join(args) + '\\n')
                if '-m' in args:
                    assert args[args.index('-m') + 1] == '1', args
                    assert args[args.index('-e') + 1] == 'box_cleared_frame', args
                    assert 'SendTiming:I' in args, args
                    (root / 'frame-observed').touch()
                print((root / 'device-log.txt').read_text())
            '''))
            adb.chmod(0o700)
            env = dict(os.environ, EVIDENCE=str(evidence), scripts=str(SCRIPTS), PATH=f'{evidence}{os.pathsep}{os.environ["PATH"]}')
            harness = '''set -euo pipefail
                tap_desc() { :; }
                shot() {
                    if ! test -f "$EVIDENCE/frame-observed"; then
                        printf 'Screenshot started before composer draw receipt\\n' >&2
                        return 1
                    fi
                    cp "$EVIDENCE/input.xml" "$EVIDENCE/current.xml"
                }
            '''
            result = subprocess.run(['bash', '-c', harness + segment], env=env, capture_output=True, text=True)
            calls = (evidence / 'calls.txt').read_text().splitlines() if (evidence / 'calls.txt').exists() else []
            return result, calls

    def test_screenshot_waits_for_actual_draw_without_starting_a_competing_jvm(self):
        result, calls = self.capture(62)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('SEND_AT_TAP_OK', result.stdout)
        self.assertEqual(sum('-m 1' in call for call in calls), 1)

    def test_waiting_for_the_receipt_does_not_accept_a_late_frame(self):
        result, _ = self.capture(136)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('box cleared 136 ms after tap, limit 100', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
