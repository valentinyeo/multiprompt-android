import hashlib
import importlib.util
import unittest
from pathlib import Path


TOOLS = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("day_theme_images", TOOLS / "check-day-theme-images.py")
images = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(images)


class ScreenIdentityTest(unittest.TestCase):
    def test_run_40261077762_wrong_inbox_is_rejected(self):
        originals = {
            "before": "b1b571619a411ee9b65c16ef0cf46877bd6a1511f5bc327d3347843945868bf1",
            "after": "58f5cf6a71824ee3bac9642d5ece6d26786e77b35d04ff1aa50b24f2cfb1cc4c",
        }
        for stage, digest in originals.items():
            with self.subTest(stage=stage):
                image = TOOLS / f"fixtures/day-theme/40261077762-{stage}-dark-inbox.png"
                self.assertEqual(hashlib.sha256(image.read_bytes()).hexdigest(), digest)
                images.verify_image(image, "sign-in")
                with self.assertRaisesRegex(ValueError, "missing expected inbox text"):
                    images.verify_image(image, "inbox")
        print("ORIGINAL_WRONG_INBOX_REJECTED_OK")


if __name__ == "__main__":
    unittest.main()
