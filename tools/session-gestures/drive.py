import atexit
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

out = Path(os.environ['EVIDENCE'])


def adb(*args):
    return subprocess.check_output(['adb', *args])


def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/gestures.xml')
    data = adb('exec-out', 'cat', '/sdcard/gestures.xml')
    (out / 'gestures.xml').write_bytes(data)
    root = ET.fromstring(data)
    assert any(n.get('text') == 'verify-session' for n in root.iter('node')), 'Session changed'
    return root


def find(root, text=None, desc=None):
    for node in root.iter('node'):
        if (text is not None and text in node.get('text', '')) or (desc is not None and node.get('content-desc') == desc):
            return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))
    raise AssertionError(f'Missing {text or desc}')


def swipe(bounds, right=False):
    left, top, right_edge, bottom = bounds
    y = (top + bottom) // 2
    start, end = left + 12, right_edge - 12
    if not right:
        start, end = end, start
    adb('shell', 'input', 'swipe', str(start), str(y), str(end), str(y), '400')


def screenshot(name):
    path = out / (name + '.png')
    path.write_bytes(adb('exec-out', 'screencap', '-p'))
    return Image.open(path).convert('RGB')


recording = subprocess.Popen(['adb', 'shell', 'echo $$ > /sdcard/gesture-record.pid; exec screenrecord --time-limit 180 /sdcard/session-gestures.mp4'])


def stop_recording():
    try:
        pid = adb('shell', 'cat', '/sdcard/gesture-record.pid').decode().strip()
        adb('shell', 'kill', '-INT', pid)
        recording.wait(timeout=15)
        adb('pull', '/sdcard/session-gestures.mp4', str(out / 'session-gestures.mp4'))
    except (subprocess.SubprocessError, OSError):
        recording.terminate()


atexit.register(stop_recording)
root = dump()
code = find(root, text='CODE_START')
initial = screenshot('gestures-before')
width, height = initial.size
code = (max(12, code[0]), code[1], min(width - 12, code[2]), code[3])
assert code[3] > code[1] and code[2] - code[0] > width // 2, code
swipe(code)
root = dump()
scrolled = screenshot('code-scrolled-left')
swipe(code, right=True)
root = dump()
returned = screenshot('code-scrolled-right')
a = initial.crop(code)
b = scrolled.crop(code)
c = returned.crop(code)
changed = sum(max(pixel) > 32 for pixel in ImageChops.difference(a, b).getdata())
restored = sum(max(pixel) > 32 for pixel in ImageChops.difference(a, c).getdata())
assert changed > 200, ('Code did not scroll', changed)
assert restored < changed / 2, ('Code did not scroll back', changed, restored)
assert not any(n.get('content-desc') == 'Cancel reply' for n in root.iter('node')), 'Code drag opened reply'
for direction in (False, True):
    title = find(root, text='verify-session')
    swipe((12, title[1], width - 12, title[3]), right=direction)
    root = dump()
for attempt in range(10):
    bubble = find(root, text='GESTURE_REPLY_TARGET')
    swipe((max(12, bubble[0]), bubble[1], min(width - 12, bubble[2]), bubble[3]), right=True)
    root = dump()
    cancel = find(root, desc='Cancel reply')
    if attempt == 9:
        screenshot('ten-replies')
    x = (cancel[0] + cancel[2]) // 2
    y = (cancel[1] + cancel[3]) // 2
    adb('shell', 'input', 'tap', str(x), str(y))
    root = dump()
    assert not any(n.get('content-desc') == 'Cancel reply' for n in root.iter('node'))
(back_x, back_y, back_right, back_bottom) = find(root, desc='Back')
adb('shell', 'input', 'tap', str((back_x + back_right) // 2), str((back_y + back_bottom) // 2))
adb('shell', 'uiautomator', 'dump', '/sdcard/gestures.xml')
inbox = ET.fromstring(adb('exec-out', 'cat', '/sdcard/gestures.xml'))
assert any(n.get('text') == 'Inbox' for n in inbox.iter('node')), 'Back no longer opens inbox'
assert any(n.get('text') == 'verify-other' for n in inbox.iter('node')), 'Second session missing'
screenshot('gestures-inbox')
result = dict(status='passed', replies=10, sessionChanges=0, codeChangedPixels=changed,
              codeReturnedPixels=restored, backToInbox=True, secondSession=True)
(out / 'gesture-result.json').write_text(json.dumps(result, indent=2))
print('RELEASED_SESSION_GESTURES_PROVEN', json.dumps(result))
