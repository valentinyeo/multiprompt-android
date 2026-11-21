import struct
from collections import namedtuple

from PIL import Image

BYTES_PER_PIXEL = 4
RGBA_8888 = 1
RGBX_8888 = 2
SUPPORTED_FORMATS = (RGBA_8888, RGBX_8888)
LEGACY_HEADER_BYTES = 12
CURRENT_HEADER_BYTES = 16
LATENCY_BUDGET_SECONDS = 3
Frame = namedtuple('Frame', 'width height header_bytes pixels')
Slice = namedtuple('Slice', 'first_byte rows command')


def parse_header(raw):
    if len(raw) < LEGACY_HEADER_BYTES:
        raise ValueError(f'SCREENCAP_RAW_TOO_SHORT: {len(raw)} bytes')
    width, height, pixel_format = struct.unpack('<III', raw[:LEGACY_HEADER_BYTES])
    if pixel_format not in SUPPORTED_FORMATS:
        raise ValueError(f'SCREENCAP_RAW_FORMAT_UNSUPPORTED: {pixel_format}')
    if width <= 0 or height <= 0:
        raise ValueError(f'SCREENCAP_RAW_DIMENSIONS_INVALID: {width}x{height}')
    payload = width * height * BYTES_PER_PIXEL
    for header_bytes in (CURRENT_HEADER_BYTES, LEGACY_HEADER_BYTES):
        if len(raw) - header_bytes == payload:
            return width, height, header_bytes
    raise ValueError(f'SCREENCAP_RAW_SIZE_MISMATCH: {width}x{height} in {len(raw)} bytes')


def decode_screencap(raw):
    width, height, header_bytes = parse_header(raw)
    return Frame(width, height, header_bytes, raw[header_bytes:])


def write_png(raw, path):
    frame = decode_screencap(raw)
    Image.frombytes('RGBA', (frame.width, frame.height), frame.pixels).save(path)
    return frame.width, frame.height


def bottom_slice(frame):
    first_row = frame.height // 2
    first_byte = frame.header_bytes + first_row * frame.width * BYTES_PER_PIXEL
    rows = frame.height - first_row
    return Slice(first_byte, rows, f'screencap | tail -c +{first_byte + 1}')


def write_slice_png(raw, width, rows, path):
    expected = width * rows * BYTES_PER_PIXEL
    if len(raw) != expected:
        raise ValueError(f'SCREENCAP_SLICE_SIZE_MISMATCH: {width}x{rows} in {len(raw)} bytes')
    Image.frombytes('RGBA', (width, rows), raw).save(path)
    return width, rows


def reply_latency_seconds(capture, reply_monotonic):
    return capture['endMonotonic'] - reply_monotonic


def assert_reply_latency(latency, budget=LATENCY_BUDGET_SECONDS):
    if latency is None:
        raise AssertionError('CHAT_VISIBILITY_INCONCLUSIVE: no OCR-positive screenshot or reply event')
    if latency < 0:
        raise AssertionError('OCR_POSITIVE_BEFORE_REPLY_EVENT')
    if latency > budget:
        raise AssertionError(f'CHAT_VISIBILITY_INCONCLUSIVE: upper bound {latency:.3f}s exceeds {budget}s')
    return latency
