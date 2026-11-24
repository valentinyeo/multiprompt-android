#!/usr/bin/env python3
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
SESSION = "live-cl_mention-2953"
DESC = f"Robot {SESSION}"


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


bot_face = load('message_reply_bot_face', HERE / 'bot-face.py')


class BotFaceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def shot(self, name, colours, bounds=(20, 20, 100, 100), descriptions=None):
        """Write a screenshot plus a UI tree with the avatar node.

        colours is a list of (offset, fill) inner rectangles; the same drawing
        in two shots is how a chat's own bot renders twice.
        """
        image = Image.new("RGB", (140, 140), (18, 18, 18))
        draw = ImageDraw.Draw(image)
        for offset, fill in colours:
            left, top, right, bottom = bounds
            draw.rectangle((left + offset, top + offset, right - 20 - offset, bottom - 20 - offset), fill=fill)
        png = self.root / f"{name}.png"
        image.save(png)
        nodes = descriptions if descriptions is not None else [DESC]
        body = "".join(f'<node content-desc="{desc}" visible-to-user="true" bounds="[{bounds[0]},{bounds[1]}][{bounds[2]},{bounds[3]}]" />'
                       for desc in nodes)
        xml = self.root / f"{name}.xml"
        xml.write_text(f'<hierarchy rotation="0"><node bounds="[0,0][140,140]">{body}</node></hierarchy>')
        return str(xml), str(png)

    def run_face(self, before, chosen, mention):
        out = self.root / "mention-bot-face.json"
        code = bot_face.main(["--session", SESSION, "--before", *before, "--chosen", *chosen,
                              "--mention", *mention, "--out", str(out)])
        record = json.loads(out.read_text()) if out.exists() else None
        return code, record

    def test_the_chat_bot_and_its_mention_row_pass_and_record_evidence(self):
        bot = [(0, (167, 127, 224))]
        old = [(0, (143, 203, 107))]
        before = self.shot("before", old)
        chosen = self.shot("chosen", bot)
        mention = self.shot("mention", bot)
        code, record = self.run_face(before, chosen, mention)
        self.assertEqual(0, code)
        self.assertTrue(record["passed"])
        self.assertEqual([check["kind"] for check in record["checks"]], ["same-bot", "look-changed"])
        self.assertTrue(all(check["passed"] for check in record["checks"]))
        self.assertEqual([100, 100], record["avatars"]["mention"]["bounds"][2:])
        self.assertLess(record["metrics"]["mention-vs-chosen"]["mean"], 6)
        self.assertGreater(record["metrics"]["chosen-vs-before"]["mean"], 15)

    def test_a_row_showing_another_bot_fails_the_assertion(self):
        chosen = self.shot("chosen", [(0, (167, 127, 224))])
        before = self.shot("before", [(0, (143, 203, 107))])
        mention = self.shot("mention", [(0, (143, 203, 107))])
        with self.assertRaises(SystemExit) as raised:
            self.run_face(before, chosen, mention)
        self.assertIn("MENTION_BOT_FACE_FAILED", str(raised.exception))
        self.assertIn("same-bot", str(raised.exception))
        record = json.loads((self.root / "mention-bot-face.json").read_text())
        self.assertFalse(record["passed"])
        self.assertFalse(record["checks"][0]["passed"])
        self.assertTrue(record["checks"][1]["passed"])

    def test_a_chat_whose_look_never_changed_cannot_pass_vacuously(self):
        same = self.shot("same", [(0, (167, 127, 224))])
        with self.assertRaises(SystemExit) as raised:
            self.run_face(same, same, same)
        self.assertIn("look-changed", str(raised.exception))

    def test_a_missing_avatar_node_is_unreadable(self):
        bot = self.shot("bot", [(0, (167, 127, 224))])
        other = self.shot("other", [(0, (167, 127, 224))], descriptions=["Robot verify-session"])
        with self.assertRaises(SystemExit) as raised:
            self.run_face(bot, bot, other)
        self.assertIn("MENTION_BOT_FACE_UNREADABLE", str(raised.exception))
        self.assertIn("expected one visible", str(raised.exception))

    def test_an_ambiguous_avatar_node_is_unreadable(self):
        bot = self.shot("bot", [(0, (167, 127, 224))])
        twice = self.shot("twice", [(0, (167, 127, 224))], descriptions=[DESC, DESC])
        with self.assertRaises(SystemExit) as raised:
            self.run_face(bot, bot, twice)
        self.assertIn("found 2", str(raised.exception))

    def test_avatars_drawn_at_different_sizes_are_unreadable(self):
        bot = self.shot("bot", [(0, (167, 127, 224))])
        small = self.shot("small", [(0, (167, 127, 224))], bounds=(20, 20, 80, 80))
        with self.assertRaises(SystemExit) as raised:
            self.run_face(bot, bot, small)
        self.assertIn("differ in size", str(raised.exception))

    def test_one_pixel_of_anti_aliasing_still_matches(self):
        # A real 28 dp avatar is mostly dark tile, so a one pixel rendering
        # offset moves few pixels far: the same bot keeps a small mean.
        bot = [(0, (60, 60, 60))]
        shifted = [(1, (60, 60, 60))]
        before = self.shot("before", [(0, (143, 203, 107))])
        chosen = self.shot("chosen", bot)
        mention = self.shot("mention", shifted)
        code, record = self.run_face(before, chosen, mention)
        self.assertEqual(0, code)
        self.assertTrue(record["checks"][0]["passed"])
        self.assertLess(record["metrics"]["mention-vs-chosen"]["strongShare"], 0.15)


if __name__ == "__main__":
    unittest.main()
