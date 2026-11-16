import json
from pathlib import Path
import sys
import re
import xml.etree.ElementTree as ET


def verify(xml, receipt, phase):
    root = ET.fromstring(xml)
    nodes = list(root.iter("node"))
    expected = ["PHONE_BEFORE_2532", "REPLY_BEFORE_2532"]
    if phase != "before":
        expected += ["Fresh start", "PHONE_AFTER_2532", "REPLY_AFTER_2532"]
    positions = []
    for text in expected:
        matches = [node for node in nodes if node.get("text") == text]
        assert len(matches) == 1, f"MESSAGE_PROJECTION_DUPLICATE: {text} expected once, got {len(matches)}. Reconcile it in ChatHistory; do not append an independent echo."
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", matches[0].get("bounds", ""))
        assert bounds and int(bounds[4]) > int(bounds[2]), f"MESSAGE_PROJECTION_ROW_NOT_VISIBLE: {text}"
        positions.append(int(bounds[2]))
    assert positions == sorted(positions), "MESSAGE_PROJECTION_REPLY_LOST: keep each reply directly under its phone message across Fresh start."
    accepted = receipt["accepted"]
    assert len(accepted) == (1 if phase == "before" else 2)
    assert len({item["phone_id"] for item in accepted}) == len(accepted)
    for item in accepted:
        assert item["receipt"] == "fixture-receipt-" + item["phone_id"]
        assert any(event.get("kind") == "turn.started" and event["payload"]["message_id"] == item["phone_id"] for event in receipt["events"])
        assert any(row["uuid"] == item["native_id"] and row["message"]["content"] == item["text"] for row in receipt["transcript"])
    if phase in ("catchup", "reconnect"):
        for phase_name in ("BEFORE", "AFTER"):
            assert sum(row["message"]["content"] == f"REPLY_{phase_name}_2532" for row in receipt["transcript"]) == 1
    return dict(phase=phase, phone_ids=[item["phone_id"] for item in accepted], visible=expected, occurrences=1)


if __name__ == "__main__":
    print(json.dumps(verify(Path(sys.argv[1]).read_text(), json.loads(Path(sys.argv[2]).read_text()), sys.argv[3])))
