import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET


def check(xml, png, receipts, phase):
    image = Path(png).read_bytes()
    assert image[:8] == b'\x89PNG\r\n\x1a\n' and len(image) > 100
    width, height = struct.unpack('>II', image[16:24])
    nodes = list(ET.parse(xml).getroot().iter('node'))
    texts = [node.get('text', '') for node in nodes if node.get('text')]
    assert phase in ('menu', 'open', 'closed')
    if phase == 'menu':
        assert 'AI usage' in texts and 'Refresh' in texts, texts
    elif phase == 'closed':
        assert 'AI usage' not in texts and 'Claude' not in texts and 'Codex' not in texts, texts
        assert 'Inbox' in texts and 'verify-session' in texts, texts
    else:
        assert 'AI usage' in texts, texts
        assert not any(text in texts for text in ('Loading usage', 'No usage yet', 'Usage is out of date', 'Could not read AI usage'))
        rows = [json.loads(line) for line in Path(receipts).read_text().splitlines()]
        assert rows and all(row['command'] == 'cat ~/.local/state/token-ladder/status.json' and row['exitCode'] == 0 for row in rows), rows
        snapshots = [json.loads(row['stdout']) for row in rows]
        assert all(snapshot == snapshots[0] for snapshot in snapshots), snapshots
        accounts = snapshots[0]['accounts']
        assert len(accounts) >= 2 and {account['provider'] for account in accounts} >= {'claude', 'codex'}
        labels = [account['label'] for account in accounts]
        plans = {}
        for account in accounts:
            name = account['label']
            assert texts.count(name) == 1, texts
            start = texts.index(name)
            end = next((i for i in range(start + 1, len(texts)) if texts[i] in labels), len(texts))
            block = texts[start:end]
            week = f"{account['week']['used']:g}%"
            five = f"{account['five_hour']['used']:g}%"
            assert week in block, (name, week, block)
            detail = next(text for text in block if text.startswith('WEEK '))
            assert detail == f"WEEK {week} | 5H {five} | EMPTY IN {account['week']['pace']['empty_hours']:g}H", (name, detail)
            difference = account['week']['time_gone'] - account['week']['used']
            pace = 'BEHIND' if difference > 5 else 'AHEAD' if difference < -5 else 'ON PACE'
            assert pace in block, (name, block)
            for text in (name, week, detail, pace):
                matching = [node for node in nodes if node.get('text') == text]
                assert matching, text
                for node in matching:
                    x1, y1, x2, y2 = map(int, re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', '')).groups())
                    assert 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, (text, node.attrib, width, height)
            plans[name] = dict(week=week, fiveHour=five, detail=detail, pace=pace)
        return dict(phase=phase, xml=Path(xml).name, screenshot=Path(png).name, plans=plans, receipt=Path(receipts).name)
    return dict(phase=phase, xml=Path(xml).name, screenshot=Path(png).name, texts=texts)


if __name__ == '__main__':
    print(json.dumps(check(*sys.argv[1:]), indent=2))
