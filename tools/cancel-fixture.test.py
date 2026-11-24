import base64
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CancelFixtureTest(unittest.TestCase):
    def test_canonical_cancel_keeps_draft_and_delivers_only_survivors(self):
        fixture = load('cancel_fixture', SCRIPTS / 'cancel-console.py')
        proof = load('cancel_proof', SCRIPTS / 'cancel-proof.py')
        reader = load('cancel_reader', ROOT / 'app/src/main/assets/chat_history_reader.py')
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            transcript = home / '.claude/projects' / str(home / 'cancel-work').replace('/', '-') / (fixture.SID + '.jsonl')
            transcript.parent.mkdir(parents=True)
            transcript.write_text('')
            receipts = home / 'receipts.txt'
            receipts.write_text('')
            ready = home / 'cancel-ready'
            fixture.reader = reader
            fixture.draft = fixture.DRAFT
            fixture.receipts = receipts
            sources = {name: base64.b64encode((ROOT / 'app/src/main/assets/chat_connectors' / (name + '.py')).read_bytes()).decode() for name in ('claude', 'codex')}
            with patch.object(fixture, 'TRANSCRIPT', transcript), patch.object(fixture, 'READY', ready), patch.object(reader, 'HOME', home), patch.object(reader, 'CONNECTOR_SOURCES', sources, create=True), patch.object(reader, 'resolve_tmux_agent', return_value=(fixture.SID, transcript)), patch.object(reader, 'send_response_preserve_draft', fixture.accept_input):
                request = dict(message_protocol=1, provider='claude', sid=fixture.SID, tmux='verify-session')
                rows = [reader.message_store_request(dict(request, op='message_submit', message_id=f'cancel_fixture_{n:04d}', text=text))['message'] for n, text in enumerate(proof.BODIES)]
                fixture.advance_queue()
                proof.host(home, 'waiting')
                target = rows[1]
                reader.message_store_request(dict(request, op='message_dismiss', message_id=target['id'], attempt=target['attempt'], epoch=target['epoch']))
                fixture.advance_queue()
                self.assertEqual(fixture.DRAFT, fixture.draft)
                proof.host(home, 'cancelled')
                ready.touch()
                fixture.draft = ''
                for _ in range(4):
                    fixture.advance_queue()
                result = proof.host(home, 'delivered')
                self.assertEqual(['started', 'cancelled', 'started'], [row['state'] for row in result['rows']])
                with closing(reader.message_store_db('verify-session')) as db, db:
                    db.execute("UPDATE shared_messages SET state='started' WHERE id=?", (target['id'],))
                with self.assertRaises(AssertionError):
                    proof.host(home, 'delivered')

    def test_ui_rejects_not_sent_missing_survivor_and_visible_cancelled_target(self):
        proof = load('cancel_ui', SCRIPTS / 'cancel-proof.py')
        state = dict(phase='cancelled', rows=[dict(text=text, state=state) for text, state in zip(proof.BODIES, proof.STATES['cancelled'])])
        root = ET.fromstring('<hierarchy><node text="CANCEL_KEEP_ONE"/><node text="CANCEL_KEEP_THREE"/></hierarchy>')
        proof.ui(root, state)
        for bad in ('Not sent', 'CANCEL_TARGET_TWO', 'CANCEL_KEEP_ONE'):
            node = ET.SubElement(root, 'node', text=bad)
            with self.assertRaises(AssertionError):
                proof.ui(root, state)
            root.remove(node)
        root.remove(root[0])
        with self.assertRaises(AssertionError):
            proof.ui(root, state)

    def test_recording_survives_the_371_second_stressed_journey_and_expiry_still_fails(self):
        driver = (SCRIPTS / 'drive.sh').read_text()
        block = driver.split('if [[ "$feature" == chat-cancel.md ]]; then', 1)[1].split('\nfi', 1)[0]
        start_at = block.index("        adb shell 'echo $$")
        start = textwrap.dedent(block[start_at:block.index('\n    fi', start_at)])
        end_at = block.index('        remote_recording=')
        end = textwrap.dedent(block[end_at:block.index('\n    fi', end_at)])
        reap_at = driver.index('reap_recorder() {')
        reap = driver[reap_at:driver.index('\n}\n', reap_at) + 3]
        with tempfile.TemporaryDirectory() as temporary:
            script = 'set -euo pipefail\nRECORDER_STOP_SECONDS=5\n' \
                f'source "{ROOT / "tools/recorder-guard.sh"}"\n{reap}' + r'''
adb() {
    case "$*" in
        *'exec screenrecord --time-limit '*) printf '%s' "$*" > "$EVIDENCE/recording-command" ;;
        'shell cat /sdcard/cancel-record.pid') printf '1234\n' ;;
        'shell kill -2 1234')
            limit=$(sed -n 's/.*--time-limit \([0-9]*\).*/\1/p' "$EVIDENCE/recording-command")
            (( limit == 0 || limit > 371 )) ;;
        'pull /sdcard/cancel-proof.mp4 '*) printf 'retained video' > "$3" ;;
        *) return 1 ;;
    esac
}
'''
            for command, passed in ((start, True), (start.replace('--time-limit 0 ', '--time-limit 180 '), False)):
                run = subprocess.run(['bash', '-c', script + command + '\nwait "$recording_client"\n' + end],
                                     env=dict(os.environ, EVIDENCE=temporary), capture_output=True, text=True)
                self.assertEqual(passed, run.returncode == 0, run.stderr)
            self.assertGreater((Path(temporary) / 'cancel-proof.mp4').stat().st_size, 0)

    def test_last_day_merges_did_not_change_cancel_contract(self):
        revisions = ('eb266155', '5f96fe62', '96faea90', '33eb2ff6', 'd03c35ab', 'dd344ad0', '962eecad')
        paths = ('app/src/main/assets/chat_history_reader.py', 'app/src/main/assets/chat_connectors/claude.py',
                 'app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt',
                 'app/src/main/java/dev/multiprompt/companion/reader/ChatHistoryConnection.kt',
                 'app/src/main/java/dev/multiprompt/companion/ui/ReplyableBubble.kt',
                 '.claude/skills/verify-multiprompt-android/scripts/cancel-console.py')
        for path in paths:
            blobs = [subprocess.check_output(['git', 'rev-parse', f'{revision}:{path}'], cwd=ROOT, text=True).strip() for revision in revisions]
            self.assertEqual(1, len(set(blobs)), path)
        blocks = []
        for revision in revisions:
            driver = subprocess.check_output(['git', 'show', revision + ':.claude/skills/verify-multiprompt-android/scripts/drive.sh'], cwd=ROOT, text=True)
            blocks.append(driver.split('if [[ "$feature" == chat-cancel.md ]]; then', 1)[1].split('\nfi', 1)[0])
        self.assertEqual(1, len(set(blocks)))


if __name__ == '__main__':
    unittest.main()
