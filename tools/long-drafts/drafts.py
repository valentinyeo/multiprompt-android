#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

FILLER = (
    'we need this checked before the release lands and the long draft keeps its place '
    'while the caret moves through the middle of the text '
)
HEAD = 'Draft check '
PREFIX_CHARS = 20
TAIL_CHARS = 4
CHUNK_CHARS = 50


def draft(length, skill='/review-native'):
    if length < len(HEAD) + len(skill) + 2:
        raise SystemExit(f'a draft of {length} characters cannot hold the skill {skill}')
    body = FILLER * (length // len(FILLER) + 1)
    text = (HEAD + skill + ' ' + body)[:length]
    if text.endswith(' '):
        text = text[:-1] + 'x'
    return text


def typed(text):
    return text.replace(' ', '%s')


def main():
    parser = argparse.ArgumentParser(description='Deterministic long chat drafts for the long-draft journey.')
    parser.add_argument('--out', help='Directory that receives the draft and its typed segments')
    parser.add_argument('--lengths', nargs='+', type=int, default=[100, 1000, 5000])
    parser.add_argument('--skill', default='/review-native')
    parser.add_argument('--chunk', type=int, default=CHUNK_CHARS)
    args = parser.parse_args()

    lengths = args.lengths
    if not args.out:
        for length in lengths:
            text = draft(length, args.skill)
            print(json.dumps({'length': len(text), 'skill': args.skill, 'skill_offset': text.index(args.skill)}))
        return

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    summary = []
    for length in lengths:
        text = draft(length, args.skill)
        if len(text) != length:
            raise SystemExit(f'draft {length} is {len(text)} characters')
        (out / f'draft-{length}.txt').write_text(text)
        (out / f'draft-{length}-prefix.txt').write_text(text[:PREFIX_CHARS])
        bulk = text[PREFIX_CHARS:-TAIL_CHARS]
        chunks = [bulk[start:start + args.chunk] for start in range(0, len(bulk), args.chunk)]
        for index, chunk in enumerate(chunks, start=1):
            (out / f'draft-{length}-bulk-{index:03d}.txt').write_text(typed(chunk))
        (out / f'draft-{length}-tail.txt').write_text(typed(text[-TAIL_CHARS:]))
        summary.append({'length': len(text), 'skill': args.skill, 'skill_offset': text.index(args.skill),
                        'prefix_chars': PREFIX_CHARS, 'bulk_chars': len(bulk), 'bulk_chunks': len(chunks),
                        'chunk_chars': args.chunk, 'tail_chars': TAIL_CHARS})
    (out / 'drafts.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'ok': True, 'drafts': summary}))


if __name__ == '__main__':
    main()
