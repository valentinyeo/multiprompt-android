#!/usr/bin/env python3
import base64
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'scale-timing'))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'long-drafts'))

import drafts
import measure
from runtime import adb, mpa, MeasurementError

DIRECTORY = Path(__file__).resolve().parent
LENGTHS = [100, 1000, 5000]
TRIALS = 5


def main():
    evidence = Path(os.environ['EVIDENCE'])
    measure.PACKAGE = os.environ.get('VERIFY_PACKAGE', 'dev.multiprompt.companion.debug')
    fingerprint = (evidence / 'host-fingerprint.txt').read_text().strip()
    measure.sign_in(os.environ['FIXTURE'], evidence, fingerprint, os.environ['EXPECTED_VERSION'])
    arguments = ['-e', 'typing_session', 'verify-session', '-e', 'typing_marker', 'VERIFY_READY_528',
                 '-e', 'draft_lengths', ','.join(str(length) for length in LENGTHS), '-e', 'trials', str(TRIALS)]
    for length in LENGTHS:
        arguments.extend(['-e', f'draft_{length}', base64.b64encode(drafts.draft(length).encode()).decode()])
    (evidence / 'long-draft-decision.txt').write_text(
        f'{TRIALS} trials per draft length. Each trial sets the draft, types 10 real single-character '
        'key events, moves the caret to the middle and types 8 more. Every key is checked against its '
        'exact expected text outside the timing window. Layout per key comes from the same '
        'Debug.threadCpuTimeNanos probe PR #618 uses, wrapping one Compose TextMeasurer layout of the '
        'draft at the composer field width, its 16/24 sp style and its six-line cap. Main ms per key is '
        'the Main thread CPU to the first frame that composes the composer. Medians of five trials decide; '
        'an effect below the trial range is inconclusive. Frame time is secondary on this '
        'software-rendered emulator and is not representative of a phone.\n')
    subprocess.run(['uptime'], check=True)
    subprocess.run(['nproc'], check=True)
    recording = subprocess.Popen(['adb', 'shell', 'screenrecord', '--time-limit', '300', '/sdcard/long-drafts.mp4'])
    try:
        result = adb('shell', 'am', 'instrument', '-w', '-e', 'class',
                     'dev.multiprompt.companion.ui.LongDraftTypingTest', *arguments,
                     f'{measure.PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner',
                     timeout=2400, check=False)
        (evidence / 'long-draft-instrumentation.txt').write_text(result.stdout + result.stderr)
    finally:
        recording.terminate()
        recording.wait(timeout=30)
        adb('pull', '/sdcard/long-drafts.mp4', str(evidence / 'long-drafts.mp4'), check=False)
        adb('pull', f'/sdcard/Android/data/{measure.PACKAGE}/files/long-drafts', str(evidence / 'long-drafts'), check=False)
        mpa('screenshot', 'long-draft-final', timeout=180, check=False)
        measure.save_logcat(evidence)
    if 'OK (1 test)' not in result.stdout:
        raise MeasurementError('LONG_DRAFT_MEASUREMENT_FAILED: ' + result.stdout[-1200:])
    table = subprocess.run([sys.executable, str(DIRECTORY / 'long-drafts-report.py'),
                            str(evidence / 'long-drafts' / 'keys.json')], capture_output=True, text=True, check=True)
    (evidence / 'long-draft-table.md').write_text(table.stdout)
    print(table.stdout, end='')


if __name__ == '__main__':
    main()
