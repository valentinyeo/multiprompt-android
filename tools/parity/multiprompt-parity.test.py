import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("parity", ROOT / "tools/parity/multiprompt-parity.py")
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)


def task(number="MUBO-2000", label="Multiprompt", **kwargs):
    return {"ticketNumber": number, "title": "Show session replies", "description": "",
            "createdAt": "2026-10-30T01:00:00Z", "labels": [{"name": label}], "status": "Normal", **kwargs}


def inventory():
    return {"track_since": "2026-10-29T22:55:59Z", "features": [], "catch_up_tickets": []}


class ParityTests(unittest.TestCase):
    def test_unlinked_inventory_gap_gets_a_ticket_once(self):
        data = inventory()
        data["features"] = [{"id": "voice", "name": "Spoken replies", "desktop": {"status": "shipped"}, "android": {"status": "missing"}}]
        calls = []
        def api(*args):
            calls.append(args)
            return {"task": task("MUBO-2001", "Multiprompt Android", description=args[-1])}
        result = parity.reconcile(data, [], True, api)
        self.assertEqual(result, [{"source": "feature:voice", "target": "android", "ticket": "MUBO-2001"}])
        self.assertIn("MP-GAP:voice:android", calls[0][-1])
        twin = task("MUBO-2001", "Multiprompt Android", description=calls[0][-1])
        self.assertEqual(parity.reconcile(data, [twin], True, api), [])
        self.assertEqual(len(calls), 1)

    def test_new_desktop_feature_gets_android_twin(self):
        calls = []
        def api(*args):
            calls.append(args)
            return {"tasks": [task("MUBO-2001", "Multiprompt Android")]}
        result = parity.reconcile(inventory(), [task()], True, api)
        self.assertEqual(result, [{"source": "MUBO-2000", "target": "android", "ticket": "MUBO-2001"}])
        self.assertEqual(calls[0][calls[0].index("--labels") + 1], "Multiprompt Android")
        self.assertEqual(calls[0][calls[0].index("--project") + 1], "13734")
        self.assertIn("MP-PAIR:MUBO-2000:android", calls[0][-1])
        self.assertIn("https://app.hypertask.ai/detail/project-13734/2000", calls[0][-1])

    def test_feature_row_links_each_ticket_to_its_own_board(self):
        data = {"reviewed": "2026-10-29", "track_since": "2026-10-29T22:55:59Z", "catch_up_tickets": [], "features": [
            {"id": "codex-chat", "name": "Codex chat",
             "desktop": {"status": "partial", "source": "https://github.com/valentinyeo/multiprompt/a.zig", "ticket": "IGSH-1220"},
             "android": {"status": "partial", "source": "https://github.com/valentinyeo/multiprompt-android/b.kt", "ticket": "MUBO-1220"}}]}
        self.assertEqual(parity.validate(data), data)
        text = parity.render(data)
        self.assertIn("[gap](https://app.hypertask.ai/detail/project-2060/1220)", text)
        self.assertIn("[gap](https://app.hypertask.ai/detail/project-13734/1220)", text)

    def test_counterpart_link_follows_the_source_ticket_key(self):
        for source, link in (("IGSH-2000", "project-2060/2000"), ("MUBO-2000", "project-13734/2000")):
            with self.subTest(source=source):
                calls = []
                def recording(*args):
                    calls.append(args)
                    return {"tasks": [task("MUBO-2001", "Multiprompt Android")]}
                parity.reconcile(inventory(), [task(source)], True, recording)
                self.assertIn(f"https://app.hypertask.ai/detail/{link}", calls[0][-1])

    def test_android_request_gets_desktop_counterpart(self):
        result = parity.reconcile(inventory(), [task(label="Multiprompt Android")])
        self.assertEqual(result, [{"source": "MUBO-2000", "target": "desktop"}])

    def test_replay_after_create_response_lost_does_not_duplicate(self):
        rows = [task()]
        calls = []
        def lost(*args):
            calls.append(args)
            rows.append(task("MUBO-2001", "Multiprompt Android", description=args[-1]))
            raise TimeoutError("Response lost after server created ticket")
        with self.assertRaises(TimeoutError):
            parity.reconcile(inventory(), rows, True, lost)
        self.assertEqual(parity.reconcile(inventory(), rows, True, lost), [])
        self.assertEqual(len(calls), 1)

    def test_archived_counterpart_still_prevents_duplicate(self):
        twin = task("MUBO-2001", "Multiprompt Android", status="Archive", description="<p>MP-PAIR:MUBO-2000:android</p>")
        self.assertEqual(parity.pairs(inventory(), [task(), twin]), [])

    def test_generated_twin_never_spawns_more_twins(self):
        row = task(label="Multiprompt Android", description="<p>MP-PAIR:MUBO-1999:android</p>")
        self.assertEqual(parity.pairs(inventory(), [row]), [])

    def test_both_labels_need_no_counterpart(self):
        row = task(labels=[{"name": "Multiprompt"}, {"name": "Multiprompt Android"}])
        self.assertEqual(parity.pairs(inventory(), [row]), [])

    def test_unrelated_and_historical_requests_are_not_copied(self):
        self.assertEqual(parity.pairs(inventory(), [task(label="Pecunia"), task(createdAt="2026-10-01T00:00:00Z")]), [])

    def test_initial_catch_up_is_explicit(self):
        data = inventory()
        data["catch_up_tickets"] = ["MUBO-2000"]
        self.assertEqual(len(parity.pairs(data, [task(createdAt="2026-10-01T00:00:00Z")])), 1)

    def test_linked_gap_ticket_is_reused(self):
        data = inventory()
        data["features"] = [{"desktop": {}, "android": {"ticket": "MUBO-2000"}}]
        self.assertEqual(parity.pairs(data, [task()]), [])
        self.assertEqual(parity.pairs(data, []), [])

    def test_malformed_date_stops_before_any_writes(self):
        calls = []
        with self.assertRaises(ValueError):
            parity.reconcile(inventory(), [task(), task("MUBO-2002", createdAt="broken")], True, lambda *a: calls.append(a))
        self.assertEqual(calls, [])

    def test_dry_run_never_writes(self):
        def forbidden(*args):
            self.fail("Dry run wrote a ticket")
        self.assertEqual(len(parity.reconcile(inventory(), [task()], False, forbidden)), 1)

    def test_automatic_crash_reports_are_not_feature_requests(self):
        self.assertEqual(parity.pairs(inventory(), [task(title="Multiprompt stopped working: report 58040069933833")]), [])

    def test_archived_source_is_not_copied(self):
        self.assertEqual(parity.pairs(inventory(), [task(status="Archive")]), [])

    def test_complete_pagination_before_planning(self):
        pages = [{"tasks": [task()], "total": 2, "nextCursor": "next"},
                 {"tasks": [task("MUBO-2001")], "total": 2, "nextCursor": None}]
        calls = []
        def api(*args):
            calls.append(args)
            return pages[len(calls) - 1]
        self.assertEqual(len(parity.board(api)), 2)
        self.assertEqual(calls[0][calls[0].index("--project") + 1], "13734")
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

    def test_creation_on_the_old_board_is_rejected(self):
        with self.assertRaises(ValueError):
            parity.reconcile(inventory(), [task()], True,
                             lambda *args: {"success": True, "tasks": [task("IGSH-2001", "Multiprompt Android")]})

    def test_inventory_and_generated_reference(self):
        data = json.loads((ROOT / "tools/parity/multiprompt-features.json").read_text())
        self.assertEqual(parity.validate(data), data)
        self.assertGreaterEqual(len(data["features"]), 15)
        self.assertEqual((ROOT / "docs/multiprompt-features.md").read_text(), parity.render(data))
        broken = copy.deepcopy(data)
        gap = next(cell for row in broken["features"] for cell in (row["desktop"], row["android"]) if cell.get("ticket"))
        gap["ticket"] = "not-a-ticket"
        with self.assertRaises(ValueError):
            parity.validate(broken)

    def test_real_old_gap_cases_have_tickets(self):
        data = json.loads((ROOT / "tools/parity/multiprompt-features.json").read_text())
        rows = {row["id"]: row for row in data["features"]}
        self.assertEqual(rows["codex-chat"]["android"]["ticket"], "IGSH-1220")
        self.assertEqual(rows["robot-faces"]["desktop"]["ticket"], "IGSH-1221")
        self.assertEqual(rows["message-queue"]["android"]["ticket"], "IGSH-1223")

    def test_timer_runs_the_daily_parity_job_as_its_own_agent(self):
        service = (ROOT / "tools/parity/multiprompt-parity.service").read_text()
        timer = (ROOT / "tools/parity/multiprompt-parity.timer").read_text()
        self.assertNotIn("CEO_AGENT", service)
        self.assertIn("PATH=%h/.local/bin", service)
        self.assertIn('multiprompt-parity.py --apply', service)
        self.assertIn('OnCalendar=*-*-* 08:30:00 UTC', timer)
        self.assertIn('Persistent=true', timer)


class CliTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.home = root / "home"
        self.home.mkdir()
        self.bin = root / "bin"
        self.bin.mkdir()
        self.token = self.home / ".config/hypertask-agents/credentials/sessions/multiprompt-android-dev-agent-token"
        self.token.parent.mkdir(parents=True)
        self.token.write_text("sentinel-token-one\n")
        stub = self.bin / "hypertask"
        stub.write_text(
            '#!/usr/bin/env bash\n'
            'printf "%s\\n" "$@" > "$HOME/hypertask.argv"\n'
            'for a in "$@"; do\n'
            '  if [ "$a" = "boom" ]; then printf "failed for %s\\n" "$3" >&2; exit 4; fi\n'
            'done\n'
            'printf \'{"success": true, "tasks": [], "total": 0}\\n\'\n')
        stub.chmod(0o755)

    def call(self, *args):
        env = {"HOME": str(self.home), "PATH": str(self.bin) + os.pathsep + os.environ["PATH"]}
        with patch.dict(os.environ, env):
            return parity.call(*args)

    def argv(self):
        return (self.home / "hypertask.argv").read_text().splitlines()

    def test_call_reads_the_token_at_run_time_and_puts_the_flags_first(self):
        self.assertEqual(self.call("task", "list", "--project", "13734"),
                         {"success": True, "tasks": [], "total": 0})
        self.assertEqual(self.argv(), ["--json", "--token", "sentinel-token-one",
                                       "task", "list", "--project", "13734"])
        self.token.write_text("sentinel-token-two\n")
        self.call("task", "list", "--project", "13734")
        self.assertEqual(self.argv()[2], "sentinel-token-two")

    def test_failure_message_hides_the_token(self):
        with self.assertRaises(ValueError) as raised:
            self.call("task", "list", "boom")
        self.assertIn("failed for <token>", str(raised.exception))
        self.assertNotIn("sentinel-token-one", str(raised.exception))

    def test_missing_confirmation_is_a_failure(self):
        stub = self.bin / "hypertask"
        stub.write_text('#!/usr/bin/env bash\nprintf \'{"success": false}\\n\'\n')
        stub.chmod(0o755)
        with self.assertRaises(ValueError):
            self.call("task", "list", "--project", "13734")


if __name__ == "__main__":
    unittest.main()
