import os
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHOICES = ROOT / 'tools/release-choices/run.sh'
GUARD = ROOT / 'tools/recorder-guard.sh'

WEDGED_ADB = '''#!/usr/bin/env bash
case "$1" in
  shell)
    shift
    case "${1:-}" in
      *screenrecord*) trap '' INT TERM HUP; while :; do sleep 1; done ;;
      cat*) printf '4321\\n' ;;
      kill*) exit 0 ;;
    esac
    ;;
  pull)
    printf 'video' > "$3"
    ;;
esac
exit 0
'''

RELEASING_ADB = '''#!/usr/bin/env bash
case "$1" in
  shell)
    shift
    case "${1:-}" in
      *screenrecord*) printf '4321\\n'; sleep 1 ;;
      cat*) printf '4321\\n' ;;
      kill*) exit 0 ;;
    esac
    ;;
  pull)
    printf 'video' > "$3"
    ;;
esac
exit 0
'''

HARNESS = '''
set -u
source "$GUARD"
{finish}
RECORDER_STOP_SECONDS=2
sleep 60 &
printf '%s\\n' "$!" > "$FIXTURE/offline-proxy.pid"
adb shell 'screenrecord --time-limit 0 /sdcard/release-choices.mp4 & echo $! > /sdcard/choices-recording.pid; wait' > "$FIXTURE/recording.log" 2>&1 &
recording=$!
finish
'''


class ChoicesRecorderExitTest(unittest.TestCase):
    def finish_function(self):
        source = CHOICES.read_text()
        start = source.index('finish() {')
        return source[start:source.index('\n}\n', start) + 3]

    def survivors(self, binary):
        result = subprocess.run(['pgrep', '-af', str(binary)], capture_output=True, text=True)
        return [line for line in result.stdout.splitlines() if str(binary) in line]

    def stop_group(self, process, binary):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        for line in self.survivors(binary):
            try:
                os.kill(int(line.split(None, 1)[0]), signal.SIGKILL)
            except (ProcessLookupError, ValueError):
                pass
        process.wait()

    def proxy_gone(self, pid):
        for attempt in range(20):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return True
            time.sleep(0.1)
        return False

    def run_finish(self, stub):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            fixture = home / 'fixture'
            evidence = home / 'evidence'
            binary = home / 'bin' / 'adb'
            for path in (fixture, evidence, binary.parent):
                path.mkdir()
            binary.write_text(stub)
            binary.chmod(0o755)
            environment = dict(
                os.environ,
                PATH=f'{binary.parent}{os.pathsep}{os.environ["PATH"]}',
                GUARD=str(GUARD),
                FIXTURE=str(fixture),
                EVIDENCE=str(evidence),
                VERIFY_FEATURE='chat-choices.md',
            )
            started = time.monotonic()
            with (home / 'stderr.txt').open('wb') as errors:
                process = subprocess.Popen(
                    ['bash', '-c', HARNESS.format(finish=self.finish_function())],
                    env=environment,
                    stderr=errors,
                    start_new_session=True,
                )
                try:
                    code = process.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    code = None
                finally:
                    self.stop_group(process, binary)
            proxy = int((fixture / 'offline-proxy.pid').read_text().strip())
            return {
                'code': code,
                'elapsed': time.monotonic() - started,
                'survivors': self.survivors(binary),
                'proxy_gone': self.proxy_gone(proxy),
                'stderr': (home / 'stderr.txt').read_text(),
                'video': (evidence / 'release-choices.mp4').stat().st_size,
            }

    def test_journey_exit_stops_a_wedged_recorder_client(self):
        result = self.run_finish(WEDGED_ADB)
        self.assertEqual(result['code'], 1, f"wedged recorder stop returned {result['code']} after {result['elapsed']:.1f}s")
        self.assertEqual(result['survivors'], [], 'a wedged adb client outlived the journey exit')
        self.assertTrue(result['proxy_gone'], 'the offline proxy outlived the journey exit')
        self.assertIn('killing it', result['stderr'])

    def test_journey_exit_still_pulls_a_healthy_recording(self):
        result = self.run_finish(RELEASING_ADB)
        self.assertEqual(result['code'], 0, result['stderr'])
        self.assertEqual(result['survivors'], [])
        self.assertTrue(result['proxy_gone'])
        self.assertGreater(result['video'], 0)


if __name__ == '__main__':
    unittest.main()
