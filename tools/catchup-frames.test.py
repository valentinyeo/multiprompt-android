import importlib.util
import struct
import tempfile
from pathlib import Path
import unittest

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'

spec = importlib.util.spec_from_file_location('catchup_frames', SCRIPTS / 'catchup_frames.py')
catchup_frames = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catchup_frames)


def pixels(width, height):
    return bytes((x * 37 + y * 11 + channel * 53) % 256
                 for y in range(height) for x in range(width) for channel in range(4))


def raw_frame(width, height, pixel_format=catchup_frames.RGBA_8888, header_bytes=16, colorspace=0):
    header = struct.pack('<III', width, height, pixel_format)
    if header_bytes == 16:
        header += struct.pack('<I', colorspace)
    return header + pixels(width, height)


class ScreencapFrameTest(unittest.TestCase):
    def test_current_raw_frame_round_trips_to_png(self):
        raw = raw_frame(5, 4)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'frame.png'
            self.assertEqual(catchup_frames.write_png(raw, path), (5, 4))
            image = Image.open(path)
            self.assertEqual(image.mode, 'RGBA')
            self.assertEqual(image.size, (5, 4))
            self.assertEqual(image.tobytes(), raw[16:])

    def test_legacy_twelve_byte_header_round_trips(self):
        raw = raw_frame(3, 2, header_bytes=12)
        frame = catchup_frames.decode_screencap(raw)
        self.assertEqual((frame.width, frame.height, frame.header_bytes), (3, 2, 12))
        self.assertEqual(frame.pixels, raw[12:])

    def test_rgbx_format_decodes_as_colour_bytes(self):
        raw = raw_frame(2, 2, pixel_format=catchup_frames.RGBX_8888)
        self.assertEqual(catchup_frames.decode_screencap(raw).pixels, raw[16:])

    def test_truncated_frame_is_rejected(self):
        raw = raw_frame(5, 4)
        with self.assertRaisesRegex(ValueError, 'SCREENCAP_RAW_SIZE_MISMATCH'):
            catchup_frames.decode_screencap(raw[:-8])

    def test_short_frame_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SCREENCAP_RAW_TOO_SHORT'):
            catchup_frames.decode_screencap(b'\x01\x02')

    def test_unknown_pixel_format_is_rejected(self):
        raw = raw_frame(2, 2, pixel_format=4)
        with self.assertRaisesRegex(ValueError, 'SCREENCAP_RAW_FORMAT_UNSUPPORTED'):
            catchup_frames.decode_screencap(raw)

    def test_empty_dimensions_are_rejected(self):
        raw = struct.pack('<IIII', 0, 4, catchup_frames.RGBA_8888, 0) + bytes(16)
        with self.assertRaisesRegex(ValueError, 'SCREENCAP_RAW_DIMENSIONS_INVALID'):
            catchup_frames.decode_screencap(raw)


class BottomSliceTest(unittest.TestCase):
    def test_slice_starts_at_the_lower_half_and_streams_to_the_end(self):
        frame = catchup_frames.decode_screencap(raw_frame(4, 6))
        half = catchup_frames.bottom_slice(frame)
        self.assertEqual(half.rows, 3)
        self.assertEqual(half.first_byte, 16 + 3 * 4 * 4)
        self.assertEqual(half.command, 'screencap | tail -c +65')

    def test_odd_height_keeps_the_extra_row_in_the_lower_half(self):
        frame = catchup_frames.decode_screencap(raw_frame(2, 5))
        half = catchup_frames.bottom_slice(frame)
        self.assertEqual(half.rows, 3)
        self.assertEqual(half.first_byte, 16 + 2 * 2 * 4)

    def test_slice_matches_the_streamed_bytes(self):
        raw = raw_frame(4, 6)
        half = catchup_frames.bottom_slice(catchup_frames.decode_screencap(raw))
        self.assertEqual(raw[half.first_byte:], pixels(4, 6)[3 * 4 * 4:])

    def test_slice_round_trips_to_png(self):
        width, rows = 4, 3
        payload = pixels(width, rows)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'slice.png'
            self.assertEqual(catchup_frames.write_slice_png(payload, width, rows, path), (width, rows))
            image = Image.open(path)
            self.assertEqual(image.size, (width, rows))
            self.assertEqual(image.tobytes(), payload)

    def test_truncated_slice_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'SCREENCAP_SLICE_SIZE_MISMATCH'):
            catchup_frames.write_slice_png(pixels(4, 3)[:-4], 4, 3, Path('/tmp/unused.png'))


class ReplyLatencyTest(unittest.TestCase):
    def test_latency_is_measured_to_frame_arrival(self):
        capture = dict(startMonotonic=10.28, endMonotonic=13.14)
        self.assertAlmostEqual(catchup_frames.reply_latency_seconds(capture, 10.0), 3.14)

    def test_frame_arrival_over_budget_fails_even_when_the_frame_started_in_time(self):
        capture = dict(startMonotonic=10.28, endMonotonic=13.14)
        with self.assertRaisesRegex(AssertionError, 'CHAT_VISIBILITY_INCONCLUSIVE'):
            catchup_frames.assert_reply_latency(catchup_frames.reply_latency_seconds(capture, 10.0))

    def test_frame_arrival_within_budget_passes(self):
        capture = dict(startMonotonic=10.02, endMonotonic=12.23)
        self.assertAlmostEqual(catchup_frames.assert_reply_latency(catchup_frames.reply_latency_seconds(capture, 10.0)), 2.23)

    def test_budget_is_three_seconds(self):
        self.assertEqual(catchup_frames.assert_reply_latency(3.0), 3.0)
        with self.assertRaisesRegex(AssertionError, 'exceeds 3s'):
            catchup_frames.assert_reply_latency(3.001)

    def test_missing_positive_frame_is_inconclusive(self):
        with self.assertRaisesRegex(AssertionError, 'CHAT_VISIBILITY_INCONCLUSIVE'):
            catchup_frames.assert_reply_latency(None)

    def test_positive_frame_before_the_reply_event_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'OCR_POSITIVE_BEFORE_REPLY_EVENT'):
            catchup_frames.assert_reply_latency(-0.4)


class CatchupSamplerTest(unittest.TestCase):
    def test_sampler_streams_only_the_lower_half_of_each_frame(self):
        source = (SCRIPTS / 'catchup.py').read_text()
        self.assertIn("check_output(['adb', 'exec-out', 'sh', '-c', half.command], timeout=10)", source)
        self.assertIn('catchup_frames.write_slice_png(image, start_frame.width, half.rows, path)', source)
        self.assertIn('catchup_frames.bottom_slice(start_frame)', source)
        self.assertNotIn("'screencap', '-p'", source)
        self.assertIn('catchup_frames.reply_latency_seconds(capture, reply[', source)
        self.assertIn('catchup_frames.assert_reply_latency(', source)


if __name__ == '__main__':
    unittest.main()
