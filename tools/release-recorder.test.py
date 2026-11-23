import os
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECORDER = ROOT / 'tools/release-recorder.sh'

WEDGED_ADB = '''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$ADB_LOG"
case "$1" in
  shell)
    shift
    case "${1:-}" in
      *screenrecord*) printf '4321\\n'; trap '' INT TERM HUP; while :; do sleep 1; done ;;
    esac
    ;;
esac
exit 0
'''

RELEASING_ADB = '''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$ADB_LOG"
case "$1" in
  shell)
    shift
    case "${1:-}" in
      *screenrecord*) printf '4321\\n'; sleep 1 ;;
    esac
    ;;
  pull)
    printf '' > "$3"
    ;;
esac
exit 0
'''

PARENT_STOP = '''
set -u
adb shell 'screenrecord --time-limit 0 --bit-rate 2000000 /sdcard/release-journey.mp4 & echo $!; wait' > "$FIXTURE/recorder.pid" 2> "$EVIDENCE/recording.log" &
recorder_client=$!
for attempt in {1..30}; do
    recorder_pid=$(head -1 "$FIXTURE/recorder.pid" | tr -d '\\r')
    [[ "$recorder_pid" =~ ^[0-9]+$ ]] && break
    sleep 0.2
done
[[ "$recorder_pid" =~ ^[0-9]+$ ]]
adb shell kill -0 "$recorder_pid"
adb shell kill -2 "$recorder_pid"
wait "$recorder_client"
'''

BOUNDED_STOP = '''
set -euo pipefail
source "$RECORDER"
recorder_start
recorder_stop
recorder_pull
'''


class ReleaseRecorderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.fixture = self.home / 'fixture'
        self.evidence = self.home / 'evidence'
        self.bin = self.home / 'bin'
        for directory in (self.fixture, self.evidence, self.bin):
            directory.mkdir()
        self.adb_log = self.home / 'adb.log'

    def install_adb(self, body):
        path = self.bin / 'adb'
        path.write_text(body)
        path.chmod(0o755)

    def run_script(self, script, limit):
        environment = dict(
            os.environ,
            PATH=f'{self.bin}:{os.environ["PATH"]}',
            FIXTURE=str(self.fixture),
            EVIDENCE=str(self.evidence),
            ADB_LOG=str(self.adb_log),
            RECORDER=str(RECORDER),
            RECORDER_STOP_SECONDS='3',
        )
        started = time.monotonic()
        with (self.home / 'stderr.txt').open('wb') as errors:
            process = subprocess.Popen(['bash', '-c', script], env=environment, stderr=errors, start_new_session=True)
            self.addCleanup(self.stop_group, process)
            try:
                code = process.wait(timeout=limit)
                return code, time.monotonic() - started
            except subprocess.TimeoutExpired:
                return None, time.monotonic() - started

    def survivors(self):
        result = subprocess.run(['pgrep', '-af', str(self.bin / 'adb')], capture_output=True, text=True)
        return [line for line in result.stdout.splitlines() if str(self.bin / 'adb') in line]

    def stop_group(self, process):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        for line in self.survivors():
            try:
                os.kill(int(line.split(None, 1)[0]), signal.SIGKILL)
            except (ProcessLookupError, ValueError):
                pass
        process.wait()

    def test_parent_stop_hangs_when_the_device_never_returns(self):
        self.install_adb(WEDGED_ADB)
        code, elapsed = self.run_script(PARENT_STOP, 10)
        self.assertIsNone(code, f'parent stop returned after {elapsed:.1f}s, so this control no longer shows the hang')

    def test_stop_ends_within_the_repair_limit_when_the_device_never_returns(self):
        self.install_adb(WEDGED_ADB)
        code, elapsed = self.run_script(BOUNDED_STOP, 25)
        self.assertIsNotNone(code, 'recorder stop hung past its own limit')
        self.assertLess(elapsed, 25)
        self.assertNotEqual(code, 0, 'a wedged recorder must fail loudly')
        self.assertIn('killing it', (self.home / 'stderr.txt').read_text())

    def test_stop_leaves_no_wedged_client_behind(self):
        self.install_adb(WEDGED_ADB)
        code, elapsed = self.run_script(BOUNDED_STOP, 25)
        self.assertIsNotNone(code, f'recorder stop hung past its own limit after {elapsed:.1f}s')
        self.assertEqual(self.survivors(), [], 'a wedged adb client outlived recorder stop')

    def test_stop_leaves_a_healthy_recorder_alone(self):
        self.install_adb(RELEASING_ADB)
        code, elapsed = self.run_script(BOUNDED_STOP, 25)
        self.assertEqual(code, 0)
        self.assertLess(elapsed, 25)


if __name__ == '__main__':
    unittest.main()
