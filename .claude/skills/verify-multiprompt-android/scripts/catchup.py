import json
import os
from pathlib import Path
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert subprocess.check_output(['adb', 'get-serialno'], text=True).startswith('emulator-')
root = Path(os.environ['EVIDENCE'])
frames = root / 'shell-frames'
frames.mkdir()
stop = threading.Event()
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 14)


def record_shell():
    for frame in range(125):
        began = time.monotonic()
        text = subprocess.check_output(['sudo', '-u', 'verifyssh', 'tmux', 'capture-pane', '-p', '-t', '=verify-session'], text=True)
        image = Image.new('RGB', (720, 1280), '#111111')
        ImageDraw.Draw(image).text((12, 20), 'Same live session: shell\n' + text, font=font, fill='white')
        image.save(frames / f'{frame:03d}.png')
        if stop.wait(max(0, 1 - (time.monotonic() - began))):
            break


recorder = subprocess.Popen(['adb', 'shell', 'screenrecord --time-limit 125 --bit-rate 2000000 /sdcard/catchup-live.mp4 & echo $!; wait'], stdout=subprocess.PIPE, text=True)
recorder_pid = recorder.stdout.readline().strip()
assert recorder_pid.isdigit()
thread = threading.Thread(target=record_shell)
thread.start()
started = time.monotonic()
seen = None
samples = []
try:
    while time.monotonic() - started < 120:
        began = time.monotonic()
        subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/catchup.xml'], check=True, stdout=subprocess.DEVNULL)
        xml = subprocess.check_output(['adb', 'exec-out', 'cat', '/sdcard/catchup.xml'])
        now = time.time()
        texts = [node.get('text', '') for node in ET.fromstring(xml).iter('node')]
        events = subprocess.check_output(['sudo', 'cat', '/home/verifyssh/shell-events.jsonl'], text=True)
        reply = next((json.loads(line) for line in reversed(events.splitlines()) if json.loads(line)['text'] == 'Followup received.'), None)
        visible = any('Followup received.' in text for text in texts)
        if visible and seen is None:
            assert reply is not None
            seen = now - reply['timestamp']
            assert seen <= 3, f'CHAT_LATE: {seen:.3f}s'
            assert 'FOLLOWUP' in texts, texts
            assert 'Queued' not in texts, texts
            (root / 'catchup-visible.xml').write_bytes(xml)
            (root / 'catchup-visible.png').write_bytes(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p']))
        if reply is not None and now - reply['timestamp'] > 3:
            assert visible, 'CHAT_STALE: reply absent after three seconds'
            assert 'Queued' not in texts, 'DELIVERED_STILL_QUEUED'
        samples.append(dict(elapsed=round(time.monotonic() - started, 3), visible=visible, shell_reply=reply is not None))
        time.sleep(max(0, 1 - (time.monotonic() - began)))
    assert seen is not None, 'FOLLOWUP_NEVER_VISIBLE'
finally:
    monitored = time.monotonic() - started
    stop.set()
    thread.join()
    (root / 'catchup-samples.json').write_text(json.dumps(samples, indent=2))
    subprocess.run(['adb', 'shell', 'kill', '-INT', recorder_pid], check=False)
    recorder.wait(timeout=20)
    subprocess.run(['adb', 'pull', '/sdcard/catchup-live.mp4', str(root / 'catchup-live.mp4')], check=True)
    subprocess.run(['ffmpeg', '-y', '-framerate', '1', '-i', str(frames / '%03d.png'),
                    '-i', str(root / 'catchup-live.mp4'), '-filter_complex',
                    '[1:v]scale=720:1280,fps=1[phone];[0:v][phone]hstack=inputs=2',
                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-shortest', str(root / 'shell-and-chat.mp4')], check=True)

result = dict(duration=monitored, replyLatencySeconds=seen, samples=len(samples), status='passed')
(root / 'catchup-result.json').write_text(json.dumps(result, indent=2))
print('ANDROID_CHAT_CAUGHT_UP_WITHIN_3_SECONDS_FOR_120_SECONDS', result)
