import argparse
import json
from pathlib import Path
import subprocess

LATEST = 'LONG_CHAT_LATEST_1156'


def verify_frame(path):
    text = subprocess.check_output(['tesseract', str(path), 'stdout'],
        stderr=subprocess.DEVNULL, text=True)
    assert LATEST in text, f'Captured frame does not show the latest reply: {path}'
    return path.name


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    names = ['smoke', 'reopen'] if args.smoke else ['initial', 'switch-back', 'sustained', 'reopen']
    files = [args.evidence / 'screens' / f'{name}.png' for name in names]
    if not args.smoke:
        soak = sorted((args.evidence / 'screens').glob('soak-*.png'))
        assert len(soak) >= 60
        files.extend(soak)
    passed = [verify_frame(path) for path in files]
    (args.evidence / 'frame-proof.json').write_text(json.dumps(dict(marker=LATEST, files=passed)) + '\n')
    print('CAPTURED_REPLY_FRAMES_VERIFIED', len(passed))
