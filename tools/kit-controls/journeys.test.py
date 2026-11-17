from pathlib import Path
import json
import importlib.util
import hashlib
import base64
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"


class ControlJourneyTest(unittest.TestCase):
    def test_changed_key_waits_for_the_sheet_before_using_underlying_controls(self):
        source = (SCRIPTS / "drive.sh").read_text()
        rotated = source.split('> "$EVIDENCE/rotated-sshd-pid.txt"', 1)[1]
        before_warning = rotated.split("wait_for_text 'SSH host key changed'", 1)[0]
        self.assertNotIn("refresh_inbox", before_warning)
        self.assertIn("wait_for_text 'Mark as unread'", source)

    def test_device_denial_returns_to_the_screen_that_started_sign_in(self):
        source = (SCRIPTS / 'device-approval-drive.sh').read_text()
        after_no = source.split('tap_text No\n', 1)[1].split('if ssh', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'current.xml').write_text((Path(__file__).parent / 'fixtures/987d3f8e-after-device-denial.xml').read_text())
            script = '''set -euo pipefail
EVIDENCE="$1"
scripts="$2"
wait_for_text() { python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text "$1"; }
wait_for_absence() { python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent "$1"; }
shot() { :; }
''' + after_no
            result = subprocess.run(['bash', '-c', script, 'device-denial', directory, str(SCRIPTS)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            state = json.loads((root / 'device-decisions-ui.json').read_text())
            self.assertIn('Session list settings', state['texts'])
            self.assertIn('Switch / renew login', state['texts'])
            self.assertNotIn('Approve this device?', state['texts'])

    def test_agent_picker_navigation_requires_an_unchanged_tmux_session_list(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        start = source.index("    sudo -u verifyssh tmux list-sessions -F '#{session_name}' > \"$EVIDENCE/sessions-before-agent-picker.txt\"")
        phase = source[start:source.index('    exit 0', start)]
        for changed in (False, True):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                script = '''set -euo pipefail
EVIDENCE="$1"
changed="$2"
tap_desc() { [[ "$1" == 'New session' ]]; }
tap_text() { [[ "$1" == 'Use this folder' || "$1" == Back ]]; }
wait_for_text() { :; }
shot() { :; }
adb() { [[ "$*" == 'shell input keyevent KEYCODE_BACK' ]]; }
sudo() {
    [[ "$*" == '-u verifyssh tmux list-sessions -F #{session_name}' ]]
    printf 'verify-session\n'
    if [[ -e "$EVIDENCE/listed" && "$changed" == True ]]; then printf 'unexpected-agent\n'; fi
    touch "$EVIDENCE/listed"
}
''' + phase
                result = subprocess.run(['bash', '-c', script, 'agent-picker', directory, str(changed)], capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, not changed, result.stderr)

    def test_device_fixture_requires_pkce_and_observes_real_authorized_key_changes(self):
        spec = importlib.util.spec_from_file_location("approval_fixture", SCRIPTS / "device-approval.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.ssh').mkdir()
            authorized = root / '.ssh/authorized_keys'
            authorized.write_text('ssh-ed25519 ORIGINAL\n')
            (root / 'pending-device.pub').write_text('ssh-ed25519 APPROVE comment')
            (root / 'denied-device.pub').write_text('ssh-ed25519 DENY comment')
            fixture = module.ApprovalFixture(root, root)
            verifier = 'v' * 43
            fixture.codes['code'] = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
            status, reply = fixture.request('POST', '/oauth/token', dict(grant_type='authorization_code', code='code', code_verifier=verifier, redirect_uri='dev.multiprompt.companion.debug://auth/callback'), None)
            self.assertEqual(status, 200)
            auth = 'Bearer ' + reply['access_token']
            approve = '/v1/device-keys/fixture-approve/approve'
            self.assertEqual(fixture.request('POST', approve, {}, auth)[0], 409)
            authorized.write_text(authorized.read_text() + 'ssh-ed25519 APPROVE\n')
            self.assertEqual(fixture.request('POST', approve, {}, auth), (200, dict(status='approved')))
            self.assertIn('ssh-ed25519 DENY', authorized.read_text())
            deny = '/v1/device-keys/fixture-deny/deny'
            self.assertEqual(fixture.request('POST', deny, {}, auth)[0], 409)
            authorized.write_text('ssh-ed25519 ORIGINAL\nssh-ed25519 APPROVE\n')
            self.assertEqual(fixture.request('POST', deny, {}, auth), (200, dict(status='denied')))
            self.assertEqual(fixture.request('GET', '/v1/device-keys', {}, None)[0], 401)
            self.assertEqual(fixture.request('POST', '/oauth/token', dict(grant_type='authorization_code', code='code', code_verifier=verifier), None)[0], 400)
            receipts = [json.loads(line) for line in (root / 'device-approval-receipts.jsonl').read_text().splitlines()]
            self.assertEqual([row['action'] for row in receipts], ['browser_pkce_exchange', 'approve', 'deny'])

    def test_reaction_fixture_consumes_the_prompt_and_delivery_marker_as_one_turn(self):
        wire = "👍 to: First reply [mp:6d8e353d25c5fe23]"
        marker = "[mp:5e5cdaa77f3dcfe4]"
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            receipts = home / "receipts.txt"
            result = subprocess.run(
                ["python3", str(SCRIPTS / "reaction-console.py"), str(receipts)],
                input=wire + "\n" + marker + "\n", text=True, capture_output=True,
                env={**os.environ, "HOME": directory},
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(receipts.read_text().splitlines(), ["VERIFY_RECEIVED:" + wire])
            transcript = next((home / ".claude/projects").rglob("*.jsonl"))
            messages = [json.loads(line)["message"] for line in transcript.read_text().splitlines()]
            self.assertEqual(messages[-2]["content"], wire + "\n" + marker)
            self.assertEqual(sum(message["content"] == "Reaction received." for message in messages), 1)

    def test_host_rotation_closes_only_the_owned_listener_and_its_connections(self):
        source = (SCRIPTS / "drive.sh").read_text()
        start = source.index("    mapfile -t old_connections")
        end = source.index("    for attempt", start)
        rotation = source[start:end]
        with tempfile.TemporaryDirectory() as directory:
            script = '''set -euo pipefail
old_pid=123
EVIDENCE="$1"
sudo() {
    if [[ "$1" == ps ]]; then printf '111\n222\n';
    elif [[ "$1" == kill ]]; then shift; printf '%s\n' "$@" > "$EVIDENCE/killed.txt";
    else return 1; fi
}
''' + rotation
            result = subprocess.run(["bash", "-c", script, "rotation", directory], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((Path(directory) / "killed.txt").read_text().splitlines(), ["123", "111", "222"])
            self.assertEqual((Path(directory) / "sshd-children-before-rotation.txt").read_text().splitlines(), ["111", "222"])


if __name__ == "__main__":
    unittest.main()
