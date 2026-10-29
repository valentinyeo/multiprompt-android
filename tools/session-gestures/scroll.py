from PIL import ImageChops


def displacement(before, after):
    assert before.size == after.size
    width, height = before.size
    before = before.convert('L').point(lambda p: 255 if p < 120 else 0)
    after = after.convert('L').point(lambda p: 255 if p < 120 else 0)
    matches = []
    for shift in range(-width + 100, width - 99):
        start = max(0, -shift)
        end = min(width, width - shift)
        a = before.crop((start, 0, end, height))
        b = after.crop((start + shift, 0, end + shift, height))
        ink = sum(ImageChops.lighter(a, b).histogram()[128:])
        if ink < 100:
            continue
        different = sum(ImageChops.difference(a, b).histogram()[128:])
        matches.append((different / ink, -end + start, shift))
    assert matches, 'No code text to track'
    error, _, shift = min(matches)
    assert error < 0.2, ('Code text correspondence lost', error, shift)
    return shift
