import base64
import hashlib
from html import escape
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.parse import parse_qs, urlencode, urlsplit


class ApprovalFixture:
    def __init__(self, fixture, home, mode=None):
        self.home = home
        self.codes = {}
        self.access = secrets.token_urlsafe(32)
        self.refresh = secrets.token_urlsafe(32)
        self.keys = [dict(device_id="fixture-approve", name="Fixture laptop", public_key=" ".join((fixture / "pending-device.pub").read_text().split()[:2]), status="pending"),
                     dict(device_id="fixture-deny", name="Stale laptop", public_key=" ".join((fixture / "denied-device.pub").read_text().split()[:2]), status="waiting")]
        self.phone = None
        self.layout = dict(schema=1, revision=0, workspaces=[], tabs=[])
        self.controls = mode == 'account-controls'
        if self.controls:
            self.keys = []
            self.layout.update(
                workspaces=[dict(name=name) for name in ['Workshop', 'Studio', 'North', 'South', 'East', 'West', 'Archiveable']],
                tabs=[dict(kind='tmux', name=name, tmux_session=name, tmux_host=1,
                           workspace='Workshop', group=group)
                      for name, group in [('verify-session', 'Original'), ('pin-00', 'Other')]])
            self.save_layout()

    def save_layout(self):
        if self.controls:
            target = self.home / 'account-controls-layout.json'
            temporary = target.with_suffix('.tmp')
            temporary.write_text(json.dumps(self.layout))
            temporary.replace(target)

    def receipt(self, **event):
        with (self.home / "device-approval-receipts.jsonl").open("a") as output:
            output.write(json.dumps(event) + "\n")

    def request(self, method, path, body, authorization):
        if path == "/oauth/token" and method == "POST":
            if body.get("grant_type") == "authorization_code":
                challenge = self.codes.pop(body.get("code"), None)
                actual = base64.urlsafe_b64encode(hashlib.sha256(body.get("code_verifier", "").encode()).digest()).decode().rstrip("=")
                if not challenge or challenge != actual or body.get("redirect_uri") != "dev.multiprompt.companion.debug://auth/callback":
                    return 400, dict(error="invalid_grant")
                self.receipt(action="browser_pkce_exchange", verified=True)
            elif body.get("grant_type") != "refresh_token" or body.get("refresh_token") != self.refresh:
                return 400, dict(error="invalid_grant")
            return 200, dict(access_token=self.access, refresh_token=self.refresh, token_type="Bearer", expires_in=3600, account_id="fixture-account", device_id="fixture-phone")
        if authorization != "Bearer " + self.access:
            return 401, dict(error="unauthorized")
        if path == "/v1/layout":
            if method == "PUT":
                if body.get("base_revision") != self.layout["revision"]:
                    return 409, dict(layout=self.layout, revision=self.layout["revision"])
                self.layout = {**body["layout"], "revision": self.layout["revision"] + 1}
                self.save_layout()
                if self.controls:
                    self.receipt(action='layout_put', layout=self.layout)
            return 200, dict(layout=self.layout, revision=self.layout["revision"])
        if path == "/v1/device-key" and method == "PUT":
            key = body.get("public_key", "")
            if not re.fullmatch(r"ssh-ed25519 [A-Za-z0-9+/]+={0,2}", key):
                return 400, dict(error="invalid_key")
            self.phone = dict(device_id="fixture-phone", name="Approving phone", public_key=key, status="approved")
            return 200, dict(status="approved")
        if path == "/v1/device-keys" and method == "GET":
            return 200, dict(device_id="fixture-phone", keys=([self.phone] if self.phone else []) + [entry for entry in self.keys if entry["status"] != "waiting"])
        match = re.fullmatch(r"/v1/device-keys/(fixture-approve|fixture-deny)/(approve|deny)", path)
        if match and method == "POST":
            identity, action = match.groups()
            entry = next(entry for entry in self.keys if entry["device_id"] == identity)
            if entry["status"] != "pending":
                return 409, dict(error="not_pending")
            authorized = self.home / ".ssh/authorized_keys"
            present = entry["public_key"] in authorized.read_text().splitlines()
            if present != (action == "approve"):
                return 409, dict(error="ssh_key_change_missing")
            entry["status"] = "approved" if action == "approve" else "denied"
            self.receipt(action=action, device_id=identity, authorized_key_present=present)
            if identity == "fixture-approve":
                self.keys[1]["status"] = "pending"
                with authorized.open("a") as output:
                    output.write("\n" + self.keys[1]["public_key"] + "\n")
            return 200, dict(status=entry["status"])
        return 404, dict(error="not_found")


def serve(fixture, home, port, mode=None):
    state = ApprovalFixture(fixture, home, mode)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            target = urlsplit(self.path)
            if target.path == "/ready":
                self.send(200, dict(ready=True))
                return
            if target.path == "/login":
                query = parse_qs(target.query)
                redirect = query.get("redirect_uri", [""])[0]
                challenge = query.get("code_challenge", [""])[0]
                nonce = query.get("state", [""])[0]
                if redirect != "dev.multiprompt.companion.debug://auth/callback" or query.get("code_challenge_method") != ["S256"] or not all(re.fullmatch(r"[A-Za-z0-9_-]{43}", value) for value in (challenge, nonce)):
                    self.send(400, dict(error="invalid_authorization"))
                    return
                code = secrets.token_urlsafe(32)
                state.codes[code] = challenge
                callback = redirect + "?" + urlencode(dict(code=code, state=nonce))
                page = '<!doctype html><html><body><h1>Fixture account</h1><p>Local authenticated test account. No live email OTP.</p><a href="' + escape(callback, quote=True) + '">Continue as fixture account</a></body></html>'
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(page.encode())
                return
            self.route("GET")

        def do_POST(self):
            self.route("POST")

        def do_PUT(self):
            self.route("PUT")

        def route(self, method):
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
            status, reply = state.request(method, self.path, body, self.headers.get("Authorization"))
            self.send(status, reply)

        def send(self, status, reply):
            data = json.dumps(reply).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    (home / "device-approval.pid").write_text(str(os.getpid()))
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    serve(Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]), sys.argv[4] if len(sys.argv) > 4 else None)
