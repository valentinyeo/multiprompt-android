import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


class SendFrameCaptureTest(unittest.TestCase):
    def capture(self, milliseconds, composer='', spinner=True, frame=True, bubble=True):
        driver = (SCRIPTS / 'drive.sh').read_text()
        start = driver.index("tap_desc 'Send prompt'\n", driver.index("fill 'Message verify-session' VERIFY_SENT_528"))
        end = driver.index("wait_receipt 'VERIFY_RECEIVED:VERIFY_SENT_528'", start)
        segment = driver[start:end]
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            nodes = [f'<node package="dev.multiprompt.companion.debug" class="android.widget.EditText" text="{composer}" bounds="[0,60][100,100]"/>']
            if spinner:
                nodes.append('<node package="dev.multiprompt.companion.debug" class="android.widget.ProgressBar" bounds="[70,70][90,90]"/>')
            if bubble:
                nodes.append('<node package="dev.multiprompt.companion.debug" class="android.widget.TextView" text="VERIFY_SENT_528"/>')
            (evidence / 'input.xml').write_text('<hierarchy>' + ''.join(nodes) + '</hierarchy>')
            Image.new('RGB', (100, 100), (20, 20, 20)).save(evidence / 'input.png')
            log = 'I SendTiming: message_added +5ms\n'
            if frame:
                log += f'I SendTiming: box_cleared_frame +{milliseconds}ms\n'
            (evidence / 'device-log.txt').write_text(log)
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
            timing = driver[driver.index('send_timing() {'):driver.index('\n}\n', driver.index('send_timing() {')) + 3]
            harness = '''set -euo pipefail
                step=0
                tap_desc() { :; }
                sudo() {
                    test "$*" = '-u verifyssh touch /home/verifyssh/send-release'
                    test -f "$EVIDENCE/frame-observed"
                    touch "$EVIDENCE/transport-released"
                    printf 'TRANSPORT_RELEASED\\n'
                }
            ''' + timing + '''
                shot() {
                    test ! -f "$EVIDENCE/transport-released"
                    if ! test -f "$EVIDENCE/frame-observed"; then
                        printf 'Screenshot started before composer draw receipt\\n' >&2
                        return 1
                    fi
                    step=$((step + 1))
                    cp "$EVIDENCE/input.xml" "$EVIDENCE/current.xml"
                    cp "$EVIDENCE/input.png" "$EVIDENCE/$(printf '%02d' "$step")-$1.png"
                }
            '''
            result = subprocess.run(['bash', '-c', harness + segment], env=env, capture_output=True, text=True)
            calls = (evidence / 'calls.txt').read_text().splitlines() if (evidence / 'calls.txt').exists() else []
            return result, calls

    def test_remote_send_waits_for_driver_release(self):
        driver = (SCRIPTS / 'drive.sh').read_text()
        wrapper = driver.split("<<'DELAY'\n", 1)[1].split('\nDELAY', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            release = Path(directory) / 'send-release'
            env = dict(os.environ, SSH_ORIGINAL_COMMAND='mp_buffer=proof; printf TRANSPORT_EXECUTED')
            process = subprocess.Popen(['bash', '-c', wrapper.replace('/home/verifyssh', directory)],
                                       env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                with self.assertRaises(subprocess.TimeoutExpired):
                    process.communicate(timeout=0.2)
                release.touch()
                stdout, stderr = process.communicate(timeout=3)
                self.assertEqual(process.returncode, 0, stdout + stderr)
                self.assertEqual(stdout, 'TRANSPORT_EXECUTED')
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, 9)
                process.communicate()

    def test_remote_send_fails_closed_without_release(self):
        driver = (SCRIPTS / 'drive.sh').read_text()
        wrapper = driver.split("<<'DELAY'\n", 1)[1].split('\nDELAY', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, SSH_ORIGINAL_COMMAND='mp_buffer=proof; printf TRANSPORT_EXECUTED')
            result = subprocess.run(['bash', '-c', wrapper.replace('/home/verifyssh', directory).replace('timeout 60s', 'timeout 0.2s')],
                                    env=env, capture_output=True, text=True, timeout=3)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('TRANSPORT_EXECUTED', result.stdout)

    def test_screenshot_waits_for_actual_draw_without_starting_a_competing_jvm(self):
        result, calls = self.capture(62)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('SEND_BEFORE_TRANSPORT_OK', result.stdout)
        self.assertIn('TRANSPORT_RELEASED', result.stdout)
        self.assertEqual(sum('-m 1' in call for call in calls), 1)

    def test_shared_emulator_delay_does_not_reject_a_draw_before_transport(self):
        result, _ = self.capture(184)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('box_cleared_frame=184ms', result.stdout)
        self.assertIn('TRANSPORT_RELEASED', result.stdout)

    def test_transport_is_not_released_without_a_drawn_empty_composer_and_bubble(self):
        for state in ({'composer': 'VERIFY_SENT_528'}, {'composer': 'other draft'},
                      {'spinner': False}, {'frame': False}, {'bubble': False}):
            with self.subTest(state=state):
                result, _ = self.capture(184, **state)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn('TRANSPORT_RELEASED', result.stdout)


if __name__ == '__main__':
    unittest.main()
