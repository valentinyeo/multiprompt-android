#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scale-timing'))

import measure
from runtime import adb, mpa, MeasurementError

DIRECTORY = Path(__file__).resolve().parent


def main():
    evidence = Path(os.environ['EVIDENCE'])
    measure.PACKAGE = os.environ.get('VERIFY_PACKAGE', 'dev.multiprompt.companion.debug')
    fingerprint = (evidence / 'host-fingerprint.txt').read_text().strip()
    measure.sign_in(os.environ['FIXTURE'], evidence, fingerprint, os.environ['EXPECTED_VERSION'])
    largest = json.loads((evidence / 'timing-targets.json').read_text())['largest']
    (evidence / 'typing-decision.txt').write_text(
        'Five trials, 60 real single-character key events followed by 10 middle insertions. '
        'Main CPU, composition counts, list relayouts and the IME inset decide which work to remove first. '
        'An effect smaller than the trial range is inconclusive. FrameMetrics is secondary '
        'on this software-rendered emulator. Normal frame clock, no Compose test synchronization. '
        'The separate sampled method trace is excluded from timing results.\n')
    subprocess.run(['uptime'], check=True)
    subprocess.run(['nproc'], check=True)
    recording = subprocess.Popen(['adb', 'shell', 'screenrecord', '--time-limit', '180', '/sdcard/typing.mp4'])
    try:
        result = adb('shell', 'am', 'instrument', '-w', '-e', 'class',
                     'dev.multiprompt.companion.ui.TypingPerformanceTest',
                     '-e', 'typing_session', largest['session'], '-e', 'typing_marker', largest['marker'],
                     f'{measure.PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner',
                     timeout=1800, check=False)
        (evidence / 'typing-instrumentation.txt').write_text(result.stdout + result.stderr)
    finally:
        recording.terminate()
        recording.wait(timeout=30)
        adb('pull', '/sdcard/typing.mp4', str(evidence / 'typing.mp4'), check=False)
        adb('pull', f'/sdcard/Android/data/{measure.PACKAGE}/files/typing', str(evidence / 'typing'), check=False)
        mpa('screenshot', 'typing-final', timeout=180, check=False)
        measure.save_logcat(evidence)
    if 'OK (1 test)' not in result.stdout:
        raise MeasurementError('TYPING_MEASUREMENT_FAILED: ' + result.stdout[-1200:])
    table = subprocess.run([sys.executable, str(DIRECTORY / 'typing-report.py'), str(evidence / 'typing/keys.json')],
                           capture_output=True, text=True, check=True)
    (evidence / 'typing-table.md').write_text(table.stdout)
    print(table.stdout, end='')
    with (evidence / 'typing-profile.txt').open('w') as profile:
        subprocess.run([sys.executable, str(DIRECTORY / 'typing-profile.py'), str(evidence / 'typing/typing-profile.trace')],
                       stdout=profile, check=True)


if __name__ == '__main__':
    main()
