import json
import os
from pathlib import Path
import select
import shutil
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert subprocess.check_output(['adb', 'get-serialno'], text=True, timeout=10).startswith('emulator-')
root = Path(os.environ['EVIDENCE'])
frames = root / 'shell-frames'
frames.mkdir()
screenshots = root / 'catchup-screenshots'
screenshots.mkdir()
stop = threading.Event()
sampler_started = threading.Event()
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 14)
samples = []
captures = []
errors = []
result = dict(status='inconclusive', replyLatencySeconds=None)


def record_shell():
    try:
        for frame in range(125):
            began = time.monotonic()
            text = subprocess.check_output(['sudo', '-u', 'verifyssh', 'tmux', 'capture-pane', '-p', '-t', '=verify-session:'], text=True, timeout=10)
            image = Image.new('RGB', (720, 1280), '#111111')
            ImageDraw.Draw(image).text((12, 20), 'Same live session: shell\n' + text, font=font, fill='white')
            image.save(frames / f'{frame:03d}.png')
            if stop.wait(max(0, 1 - (time.monotonic() - began))):
                break
    except Exception as error:
        errors.append(error)


def sample_screens():
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            began = time.monotonic()
            wall_began = time.time()
            sampler_started.set()
            image = subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=5)
            ended = time.monotonic()
            wall_ended = time.time()
            path = screenshots / f'{len(captures):03d}.png'
            path.write_bytes(image)
            captures.append(dict(file=str(path.relative_to(root)), startMonotonic=began, endMonotonic=ended,
                                 startWall=wall_began, endWall=wall_ended))
            time.sleep(max(0, min(deadline - time.monotonic(), 0.5 - (time.monotonic() - began))))
    except Exception as error:
        errors.append(error)
        sampler_started.set()


def shell_events():
    return subprocess.check_output(['sudo', 'cat', '/home/verifyssh/shell-events.jsonl'], text=True, timeout=10)


recorder = subprocess.Popen(['adb', 'shell', 'screenrecord --time-limit 125 --bit-rate 2000000 /sdcard/catchup-live.mp4 & echo $!; wait'], stdout=subprocess.PIPE)
recorder_pid = None
thread = None
sampler = None
started = time.monotonic()
seen = False
reply = None
try:
    assert select.select([recorder.stdout], [], [], 10)[0], 'SCREENRECORD_START_TIMEOUT'
    recorder_output = os.read(recorder.stdout.fileno(), 64)
    assert b'\n' in recorder_output, 'SCREENRECORD_START_FAILED'
    recorder_pid = recorder_output.split(b'\n', 1)[0].decode().strip()
    assert recorder_pid.isdigit(), 'SCREENRECORD_START_FAILED'
    thread = threading.Thread(target=record_shell)
    thread.start()
    sampler = threading.Thread(target=sample_screens)
    sampler.start()
    assert sampler_started.wait(10), 'SCREENSHOT_SAMPLER_START_TIMEOUT'
    assert not errors, str(errors)
    started = time.monotonic()
    (root / 'catchup-monitor-ready').write_text('ready\n')
    while time.monotonic() - started < 120:
        assert not errors, str(errors)
        began = time.monotonic()
        wall_began = time.time()
        subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/catchup.xml'], check=True, stdout=subprocess.DEVNULL, timeout=15)
        xml = subprocess.check_output(['adb', 'exec-out', 'cat', '/sdcard/catchup.xml'], timeout=10)
        ended = time.monotonic()
        wall_ended = time.time()
        path = root / f'catchup-sample-{len(samples):03d}.xml'
        path.write_bytes(xml)
        texts = [node.get('text', '') for node in ET.fromstring(xml).iter('node')]
        events = shell_events()
        reply = next((event for event in map(json.loads, reversed(events.splitlines())) if event['text'] == 'Followup received.'), None)
        visible = any('Followup received.' in text for text in texts)
        samples.append(dict(file=path.name, startMonotonic=began, endMonotonic=ended,
                            startWall=wall_began, endWall=wall_ended, visible=visible, shell_reply=reply is not None))
        if visible and not seen:
            (root / 'catchup-visible.xml').write_bytes(xml)
            seen = True
            assert reply is not None, 'VISIBLE_WITHOUT_REPLY_EVENT'
            assert 'FOLLOWUP' in texts, texts
            assert 'Queued' not in texts, texts
        elif seen:
            assert visible, 'CHAT_DISAPPEARED_AFTER_VISIBLE'
        if reply is not None and began > reply['monotonic'] + 3:
            assert visible, 'CHAT_STALE: reply absent in observation started after deadline'
            assert 'Queued' not in texts, 'DELIVERED_STILL_QUEUED'
        time.sleep(max(0, 1 - (time.monotonic() - began)))
    assert seen, 'FOLLOWUP_NEVER_VISIBLE_IN_HIERARCHY'
    assert reply is not None, 'REPLY_EVENT_MISSING'
    result.update(duration=time.monotonic() - started, samples=len(samples))
finally:
    stop.set()
    if sampler is not None:
        sampler.join(timeout=15)
        if sampler.is_alive():
            errors.append(RuntimeError('SCREENSHOT_SAMPLER_TIMEOUT'))
    if thread is not None:
        thread.join(timeout=15)
        if thread.is_alive():
            errors.append(RuntimeError('SHELL_RECORDER_TIMEOUT'))
    try:
        events = shell_events()
        (root / 'shell-events.jsonl').write_text(events)
        reply = next((event for event in map(json.loads, reversed(events.splitlines())) if event['text'] == 'Followup received.'), None)
        if reply is not None:
            result['replyMonotonic'] = reply['monotonic']
        for capture in captures:
            text = subprocess.check_output(['tesseract', str(root / capture['file']), 'stdout'], text=True, stderr=subprocess.DEVNULL, timeout=15)
            capture['ocrPositive'] = 'Followup received.' in text
            if capture['ocrPositive']:
                shutil.copyfile(root / capture['file'], root / 'catchup-visible.png')
                result['capture'] = capture
                if reply is not None:
                    result['replyLatencySeconds'] = capture['endMonotonic'] - reply['monotonic']
                break
    finally:
        (root / 'catchup-samples.json').write_text(json.dumps(samples, indent=2))
        (root / 'catchup-screenshots.json').write_text(json.dumps(captures, indent=2))
        (root / 'catchup-result.json').write_text(json.dumps(result, indent=2))
        try:
            if recorder_pid:
                subprocess.run(['adb', 'shell', 'kill', '-INT', recorder_pid], check=False, timeout=10)
        finally:
            try:
                recorder.wait(timeout=20)
            except subprocess.TimeoutExpired:
                recorder.kill()
                recorder.wait(timeout=10)
                errors.append(RuntimeError('SCREENRECORD_TIMEOUT'))
        if recorder_pid:
            subprocess.run(['adb', 'pull', '/sdcard/catchup-live.mp4', str(root / 'catchup-live.mp4')], check=True, timeout=30)
            subprocess.run(['ffmpeg', '-y', '-framerate', '1', '-i', str(frames / '%03d.png'),
                            '-i', str(root / 'catchup-live.mp4'), '-filter_complex',
                            '[1:v]scale=720:1280,fps=1[phone];[0:v][phone]hstack=inputs=2',
                            '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-shortest', str(root / 'shell-and-chat.mp4')], check=True, timeout=120)
    if errors:
        raise RuntimeError(f'BACKGROUND_WORKER_FAILED: {errors}')

latency = result['replyLatencySeconds']
assert latency is not None, 'CHAT_VISIBILITY_INCONCLUSIVE: no OCR-positive screenshot or reply event'
assert latency >= 0, 'OCR_POSITIVE_BEFORE_REPLY_EVENT'
assert latency <= 3, f'CHAT_VISIBILITY_INCONCLUSIVE: upper bound {latency:.3f}s exceeds 3s'
result['status'] = 'passed'
(root / 'catchup-result.json').write_text(json.dumps(result, indent=2))
print('ANDROID_CHAT_CAUGHT_UP_WITHIN_3_SECONDS_FOR_120_SECONDS', result)
