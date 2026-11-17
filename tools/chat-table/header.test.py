#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import header


def screen(subtitle, extra=""):
    return ET.fromstring(f"""
        <hierarchy>
          <node text="verify-session"/>
          <node text="{subtitle}"/>
          {extra}
        </hierarchy>
    """)


class TranscriptHeaderTest(unittest.TestCase):
    def test_subtitle_without_a_conversation_header(self):
        result = header.check(screen("waiting for input"))
        self.assertEqual(False, result["conversationHeader"])
        self.assertEqual("waiting for input", result["subtitle"])

    def test_conversation_header_fails(self):
        with self.assertRaisesRegex(SystemExit, "Conversation header"):
            header.check(screen("Model / effort", '<node text="Conversation"/>'))


if __name__ == "__main__":
    unittest.main()
