import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("projection_ui", Path(__file__).with_name("ui.py"))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class ProjectionJourneyOracleTest(unittest.TestCase):
    def receipt(self):
        accepted = [dict(phone_id=f"phone-{n}", native_id=f"native-{n}", receipt=f"fixture-receipt-phone-{n}", text=f"PHONE_{phase}_2532") for n, phase in ((1, "BEFORE"), (2, "AFTER"))]
        return dict(accepted=accepted, events=[dict(kind="turn.started", payload=dict(message_id=item["phone_id"])) for item in accepted],
                    transcript=[dict(uuid=item["native_id"], message=dict(content=item["text"])) for item in accepted] +
                    [dict(uuid=f"reply-{phase}", message=dict(content=f"REPLY_{phase}_2532")) for phase in ("BEFORE", "AFTER")])

    def xml(self, texts):
        root = ET.Element("hierarchy")
        for n, text in reversed(list(enumerate(texts))):
            ET.SubElement(root, "node", text=text, bounds=f"[0,{n * 60 + 100}][400,{n * 60 + 140}]")
        return ET.tostring(root, encoding="unicode")

    def texts(self):
        return ["PHONE_BEFORE_2532", "REPLY_BEFORE_2532", "Fresh start", "PHONE_AFTER_2532", "REPLY_AFTER_2532"]

    def test_reverse_layout_traversal_still_requires_visual_order(self):
        self.assertEqual(1, ui.verify(self.xml(self.texts()), self.receipt(), "reconnect")["occurrences"])

    def test_archived_reply_keeps_its_distinct_receipt_identity(self):
        receipt = self.receipt()
        receipt["transcript"][-1] = dict(type="multiprompt_history", row=dict(role="assistant", text="REPLY_AFTER_2532", id="history-2569"))
        self.assertEqual(1, ui.verify(self.xml(self.texts()), receipt, "catchup")["occurrences"])
        with self.assertRaisesRegex(AssertionError, "MESSAGE_PROJECTION_DUPLICATE"):
            ui.verify(self.xml(self.texts() + ["REPLY_AFTER_2532"]), receipt, "catchup")

    def test_duplicate_bubble_fails_even_with_valid_receipts(self):
        with self.assertRaisesRegex(AssertionError, "MESSAGE_PROJECTION_DUPLICATE"):
            ui.verify(self.xml(self.texts() + ["PHONE_AFTER_2532"]), self.receipt(), "reconnect")

    def test_old_reply_missing_fails(self):
        with self.assertRaisesRegex(AssertionError, "MESSAGE_PROJECTION_DUPLICATE"):
            ui.verify(self.xml(self.texts()[::2]), self.receipt(), "reconnect")

    def test_reply_moved_below_the_wrong_message_fails(self):
        texts = self.texts()
        texts[1], texts[4] = texts[4], texts[1]
        with self.assertRaisesRegex(AssertionError, "MESSAGE_PROJECTION_REPLY_LOST"):
            ui.verify(self.xml(texts), self.receipt(), "reconnect")

    def test_screenshot_without_competing_transcript_identity_is_not_proof(self):
        receipt = self.receipt()
        receipt["transcript"] = receipt["transcript"][1:]
        with self.assertRaises(AssertionError):
            ui.verify(self.xml(self.texts()), receipt, "reconnect")


if __name__ == "__main__":
    unittest.main()
