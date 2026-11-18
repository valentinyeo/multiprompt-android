#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import menu


class SignedOutSyncMenuTest(unittest.TestCase):
    def test_reader_more_menu_offers_sign_in_to_sync(self):
        root = ET.fromstring('<hierarchy><node text="Sign in to sync"/><node text="More"/></hierarchy>')
        self.assertEqual("Sign in to sync", menu.check(root)["row"])
        self.assertEqual(False, menu.check(root)["signedIn"])

    def test_signed_in_row_fails(self):
        root = ET.fromstring(
            '<hierarchy><node text="Sign in to sync"/><node text="Sync: signed in — sign out"/></hierarchy>'
        )
        with self.assertRaisesRegex(SystemExit, "signed-in row"):
            menu.check(root)


if __name__ == "__main__":
    unittest.main()
