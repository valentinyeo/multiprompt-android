#!/usr/bin/env python3
import argparse
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from xml.sax.saxutils import escape

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ui = load('long_drafts_ui', HERE / 'ui.py')
drafts = load('long_drafts_drafts', HERE / 'drafts.py')

SHORT = (40, 600, 300, 660)
TALL = (40, 560, 300, 660)


def dump(directory, name, text, field=SHORT):
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n<hierarchy rotation="0">'
        '<node index="0" package="dev.multiprompt.companion.debug" visible-to-user="true" bounds="[0,0][400,800]">'
        '<node package="dev.multiprompt.companion.debug" class="android.widget.Button" content-desc="Attach" bounds="[10,600][30,660]"/>'
        f'<node package="dev.multiprompt.companion.debug" class="android.widget.EditText" focused="true" bounds="[{field[0]},{field[1]}][{field[2]},{field[3]}]" text="{escape(text)}"/>'
        '<node package="dev.multiprompt.companion.debug" class="android.widget.Button" content-desc="Send prompt" bounds="[320,600][380,660]"/>'
        '</node></hierarchy>\n'
    )
    path = Path(directory) / f'{name}.xml'
    path.write_text(xml)
    return path


def shot(directory, name, field=SHORT, rows=3, pattern='a', pill=False, caret=8):
    image = Image.new('RGB', (400, 800), (20, 20, 20))
    draw = ImageDraw.Draw(image)
    draw.rectangle([field[0] + caret, field[1] + 6, field[0] + caret + 2, field[1] + 20], fill=(230, 230, 230))
    start, width = {'a': (8, 60), 'b': (120, 60), 'c': (8, 200)}[pattern]
    for row in range(rows):
        top = field[1] + 8 + row * 9
        draw.rectangle([field[0] + start, top, field[0] + start + width, top + 6], fill=(230, 230, 230))
    if pill:
        draw.rectangle([field[0] + 8, field[1] + 8, field[0] + 88, field[1] + 9], fill=(46, 46, 46))
        draw.rectangle([field[0] + 8, field[1] + 24, field[0] + 88, field[1] + 25], fill=(46, 46, 46))
    path = Path(directory) / f'{name}.png'
    image.save(path)
    return path


class Case:
    def __init__(self):
        self.directory = Path(tempfile.mkdtemp(prefix='long-drafts-'))
        self.state = self.directory / 'state.json'

    def capture(self, length, phase, text, expected, pill='no', insert='', field=SHORT,
                rows=3, pattern='a', drawn=None, caret=8):
        xml = dump(self.directory, f'{length}-{phase}', text, field)
        png = shot(self.directory, f'{length}-{phase}', field, rows, pattern,
                   pill == 'yes' if drawn is None else drawn, caret)
        expected_path = self.directory / f'expected-{length}.txt'
        expected_path.write_text(expected)
        args = argparse.Namespace(xml=str(xml), png=str(png), length=length, phase=phase,
                                  expected=str(expected_path), state=str(self.state), pill=pill, insert=insert)
        return ui.capture(args)

    def verify(self):
        return ui.verify(argparse.Namespace(state=str(self.state)))


def sequence(case, length, field, offset, rows, pill='no', patterns=None):
    patterns = patterns or {'end': 'b', 'top': 'a', 'top-again': 'a', 'bottom': 'b'}
    body = drafts.draft(length)
    edited = body[:offset] + 'MIDEDIT' + body[offset:]
    case.capture(length, 'end', body, body, rows=rows, pattern=patterns['end'], field=field, pill=pill, caret=200)
    case.capture(length, 'top', body, body, rows=rows, pattern=patterns['top'], field=field, pill=pill, caret=8)
    case.capture(length, 'bottom', body, body, rows=rows, pattern=patterns['bottom'], field=field, pill=pill, caret=200)
    case.capture(length, 'top-again', body, body, rows=rows, pattern=patterns['top-again'], field=field, pill=pill, caret=8)
    case.capture(length, 'middle', edited, body, rows=rows, pattern='c', field=field, pill=pill, insert='MIDEDIT', caret=120)
    case.capture(length, 'reopened', edited, edited, rows=rows, pattern=patterns['bottom'], field=field, pill=pill, caret=200)


def rejects(action, message):
    try:
        action()
    except (SystemExit, AssertionError):
        return
    raise SystemExit(f'the check accepted {message}')


def main():
    text = drafts.draft(100)
    assert len(text) == 100 and '/review-native' in text
    for length in (100, 1000, 5000):
        assert len(drafts.draft(length)) == length
    assert '%s' in drafts.typed('a b')

    good = Case()
    sequence(good, 100, SHORT, 50, 3, pill='yes', patterns={'end': 'a', 'top': 'a', 'top-again': 'a', 'bottom': 'a'})
    sequence(good, 1000, TALL, 500, 6)
    sequence(good, 5000, TALL, 2500, 6)
    good.verify()
    result = json.loads((good.directory / 'long-drafts-result.json').read_text())
    assert result['ok'] is True and result['lengths'] == [100, 1000, 5000], result
    assert result['widths'] == [260, 260, 260] and result['heights'] == [60, 100, 100], result

    wrong = Case()
    rejects(lambda: wrong.capture(100, 'end', text[:-1] + 'z', text, pill='yes'), 'a composer holding different text')
    rejects(lambda: wrong.capture(100, 'end', text, text, pill='yes', drawn=False), 'a draft that draws no skill pill')
    rejects(lambda: wrong.capture(100, 'end', text, text, pill='yes', field=(40, 600, 360, 660)),
            'a field that runs under the send button')

    scroll_kept = Case()
    sequence(scroll_kept, 1000, TALL, 500, 6, patterns={'end': 'a', 'top': 'a', 'top-again': 'a', 'bottom': 'a'})
    rejects(scroll_kept.verify, 'a composer that never scrolls its draft')

    edge = Case()
    sequence(edge, 1000, TALL, 0, 6)
    rejects(edge.verify, 'a middle edit at the start of the draft')

    grown = Case()
    sequence(grown, 100, SHORT, 50, 3, pill='yes', patterns={'end': 'a', 'top': 'a', 'top-again': 'a', 'bottom': 'a'})
    sequence(grown, 1000, (40, 560, 320, 660), 500, 6)
    rejects(grown.verify, 'a field that widens with the draft')

    filling = Path(tempfile.mkdtemp(prefix='long-drafts-step-'))
    expected = drafts.draft(100)
    expected_path = filling / 'expected.txt'
    expected_path.write_text(expected)
    args = argparse.Namespace(expected=str(expected_path), want=100, xml=str(dump(filling, 'full', expected)), dump='unused')
    assert ui.step(args)['ok'] is True, 'a complete draft needs no more typing'
    args.xml = str(dump(filling, 'partial', expected[:40]))
    partial = ui.step(args)
    assert partial['field_chars'] == 40 and partial['keep'] == 40 and partial['delete'] == 0, partial
    assert partial['retype'] == drafts.typed(expected[40:]), partial
    args.xml = str(dump(filling, 'dropped', expected[:40] + expected[44:80]))
    dropped = ui.step(args)
    assert dropped['ok'] is False and dropped['keep'] == 40 and dropped['delete'] == 36, dropped
    assert dropped['retype'] == drafts.typed(expected[40:]), dropped
    args.xml = str(dump(filling, 'replaced', 'something else entirely'))
    replaced = ui.step(args)
    assert replaced['ok'] is False and replaced['keep'] == 0, replaced
    assert replaced['delete'] == len('something else entirely') and replaced['retype'] == drafts.typed(expected), replaced

    print('LONG_DRAFTS_UI_OK: 6 rejected states, 1 accepted sequence, 4 fill states')


if __name__ == '__main__':
    main()
