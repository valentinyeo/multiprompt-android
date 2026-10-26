import re
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
expected = {
    'sheet': ('Reply', 'Mark as unread', 'Copy', 'Select Text', 'Copy Request ID', 'Report'),
    'reply': ('Reply to Claude', 'First line Second line', 'Message Claude'),
    'unread': ('New', 'My question'),
}
for name, labels in expected.items():
    image = root / f'message-actions-{name}.png'
    text = subprocess.check_output(['tesseract', str(image), 'stdout'], text=True, stderr=subprocess.DEVNULL)
    words = re.sub(r'\W+', ' ', text).lower()
    for label in labels:
        assert re.sub(r'\W+', ' ', label).lower() in words, f'{name}: missing visible {label}'
    print('MESSAGE_ACTION_IMAGE_OK', name)
