#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state


def screen(replying):
    extra = """
        <node text="Replying"/>
        <node text="REPLY_BUBBLE_418"/>
        <node content-desc="Cancel reply"/>
    """ if replying else "<node text=\"Message verify-session\"/>"
    return ET.fromstring(f"<hierarchy>{extra}</hierarchy>")


class ReplyPointTest(unittest.TestCase):
    def test_point_uses_the_text_node_itself(self):
        root = ET.fromstring(
            '<hierarchy><node clickable="true" bounds="[0,0][1000,2000]">'
            '<node text="REPLY_BUBBLE_418" bounds="[40,100][240,160]"/></node></hierarchy>'
        )
        self.assertEqual((140, 130), state.point(root, "REPLY_BUBBLE_418"))


class ReplyStateTest(unittest.TestCase):
    def test_open_bar_names_the_bubble_and_cancel(self):
        result = state.check(screen(True), "open")
        self.assertEqual("Cancel reply", result["cancel"])
        self.assertEqual("REPLY_BUBBLE_418", result["preview"])

    def test_cancelled_bar_is_gone(self):
        self.assertEqual(False, state.check(screen(False), "cancelled")["replying"])


if __name__ == "__main__":
    unittest.main()
