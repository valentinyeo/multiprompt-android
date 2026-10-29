import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("parity", ROOT / "tools/parity/multiprompt-parity.py")
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)


def task(number="IGSH-2000", label="Multiprompt", **kwargs):
    return {"ticketNumber": number, "title": "Show session replies", "description": "",
            "createdAt": "2026-10-30T01:00:00Z", "labels": [{"name": label}], "status": "Normal", **kwargs}


def inventory():
    return {"track_since": "2026-10-29T22:55:59Z", "features": [], "catch_up_tickets": []}


class ParityTests(unittest.TestCase):
    def test_new_desktop_feature_gets_android_twin(self):
        calls = []
        def api(*args):
            calls.append(args)
            return {"tasks": [task("IGSH-2001", "Multiprompt Android")]}
        result = parity.reconcile(inventory(), [task()], True, api)
        self.assertEqual(result, [{"source": "IGSH-2000", "target": "android", "ticket": "IGSH-2001"}])
        self.assertEqual(calls[0][calls[0].index("--labels") + 1], "Multiprompt Android")
        self.assertIn("MP-PAIR:IGSH-2000:android", calls[0][-1])
        self.assertIn("https://app.hypertask.ai/detail/project-2060/2000", calls[0][-1])

    def test_android_request_gets_desktop_counterpart(self):
        result = parity.reconcile(inventory(), [task(label="Multiprompt Android")])
        self.assertEqual(result, [{"source": "IGSH-2000", "target": "desktop"}])

    def test_replay_after_create_response_lost_does_not_duplicate(self):
        rows = [task()]
        calls = []
        def lost(*args):
            calls.append(args)
            rows.append(task("IGSH-2001", "Multiprompt Android", description=args[-1]))
            raise TimeoutError("Response lost after server created ticket")
        with self.assertRaises(TimeoutError):
            parity.reconcile(inventory(), rows, True, lost)
        self.assertEqual(parity.reconcile(inventory(), rows, True, lost), [])
        self.assertEqual(len(calls), 1)

    def test_generated_twin_never_spawns_more_twins(self):
        row = task(label="Multiprompt Android", description="<p>MP-PAIR:IGSH-1999:android</p>")
        self.assertEqual(parity.pairs(inventory(), [row]), [])

    def test_both_labels_need_no_counterpart(self):
        row = task(labels=[{"name": "Multiprompt"}, {"name": "Multiprompt Android"}])
        self.assertEqual(parity.pairs(inventory(), [row]), [])

    def test_unrelated_and_historical_requests_are_not_copied(self):
        self.assertEqual(parity.pairs(inventory(), [task(label="Pecunia"), task(createdAt="2026-10-01T00:00:00Z")]), [])

    def test_initial_catch_up_is_explicit(self):
        data = inventory()
        data["catch_up_tickets"] = ["IGSH-2000"]
        self.assertEqual(len(parity.pairs(data, [task(createdAt="2026-10-01T00:00:00Z")])), 1)

    def test_linked_gap_ticket_is_reused(self):
        data = inventory()
        data["features"] = [{"desktop": {}, "android": {"ticket": "IGSH-2000"}}]
        self.assertEqual(parity.pairs(data, [task()]), [])
        with self.assertRaisesRegex(ValueError, "Gap ticket missing"):
            parity.pairs(data, [])

    def test_malformed_date_stops_before_any_writes(self):
        calls = []
        with self.assertRaises(ValueError):
            parity.reconcile(inventory(), [task(), task("IGSH-2002", createdAt="broken")], True, lambda *a: calls.append(a))
        self.assertEqual(calls, [])

    def test_dry_run_never_writes(self):
        def forbidden(*args):
            self.fail("Dry run wrote a ticket")
        self.assertEqual(len(parity.reconcile(inventory(), [task()], False, forbidden)), 1)

    def test_archived_source_is_not_copied(self):
        self.assertEqual(parity.pairs(inventory(), [task(status="Archive")]), [])

    def test_complete_pagination_before_planning(self):
        pages = [{"tasks": [task()], "total": 2, "nextCursor": "next"},
                 {"tasks": [task("IGSH-2001")], "total": 2, "nextCursor": None}]
        calls = []
        def api(*args):
            calls.append(args)
            return pages[len(calls) - 1]
        self.assertEqual(len(parity.board(api)), 2)
        self.assertEqual(calls[1][-2:], ("--cursor", "next"))

    def test_incomplete_or_repeated_snapshot_is_rejected(self):
        for page in ({"tasks": [task()], "total": 2},
                     {"tasks": [task()], "total": 2, "nextCursor": "same"},
                     {"tasks": [], "total": 1, "nextCursor": "next"}):
            with self.subTest(page=page), self.assertRaises(ValueError):
                parity.board(lambda *args: page)

    def test_creation_response_needs_real_ticket(self):
        with self.assertRaises(ValueError):
            parity.reconcile(inventory(), [task()], True, lambda *args: {"success": True})

    def test_inventory_and_generated_reference(self):
        data = json.loads((ROOT / "tools/parity/multiprompt-features.json").read_text())
        self.assertEqual(parity.validate(data), data)
        self.assertGreaterEqual(len(data["features"]), 15)
        self.assertEqual((ROOT / "docs/multiprompt-features.md").read_text(), parity.render(data))
        broken = copy.deepcopy(data)
        gap = next(cell for row in broken["features"] for cell in (row["desktop"], row["android"]) if cell.get("ticket"))
        del gap["ticket"]
        with self.assertRaises(ValueError):
            parity.validate(broken)

    def test_real_old_gap_cases_have_tickets(self):
        data = json.loads((ROOT / "tools/parity/multiprompt-features.json").read_text())
        rows = {row["id"]: row for row in data["features"]}
        self.assertEqual(rows["codex-chat"]["android"]["ticket"], "IGSH-1220")
        self.assertEqual(rows["robot-faces"]["desktop"]["ticket"], "IGSH-1221")
        self.assertEqual(rows["message-queue"]["android"]["ticket"], "IGSH-1223")

    def test_timer_uses_runner_identity_and_daily_schedule(self):
        service = (ROOT / "tools/parity/multiprompt-parity.service").read_text()
        timer = (ROOT / "tools/parity/multiprompt-parity.timer").read_text()
        self.assertIn('CEO_AGENT=Hax Runner 14', service)
        self.assertIn('multiprompt-parity.py --apply', service)
        self.assertIn('OnCalendar=*-*-* 08:30:00 UTC', timer)
        self.assertIn('Persistent=true', timer)


if __name__ == "__main__":
    unittest.main()
