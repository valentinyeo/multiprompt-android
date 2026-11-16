#!/usr/bin/env python3
import importlib.util
import os
import selectors
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("deepgram-stand-in.py")
ROOT = Path(__file__).resolve().parents[4]
TOKEN = "stand-in-probe-token-not-a-credential"
RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
RFC_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="


def read_exact(conn, count):
    data = b""
    while len(data) < count:
        chunk = conn.recv(count - len(data))
        if not chunk:
            raise AssertionError("socket closed")
        data += chunk
    return data


def masked(opcode, payload):
    mask = b"\x11\x22\x33\x44"
    body = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + body


class DeepgramStandInTest(unittest.TestCase):
    def setUp(self):
        self.proc = subprocess.Popen(
            [sys.executable, str(SCRIPT), "0"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env={**os.environ, "DEEPGRAM_STAND_IN_FOREGROUND": "1"},
        )
        ready = selectors.DefaultSelector()
        ready.register(self.proc.stdout, selectors.EVENT_READ)
        if not ready.select(5):
            self.fail(self.proc.stderr.read())
        line = self.proc.stdout.readline()
        self.assertTrue(line.startswith("LISTENING 127.0.0.1 "), line)
        self.port = int(line.split()[-1])

    def tearDown(self):
        if self.proc.poll() is None:
            self.proc.terminate()
        try:
            stdout, stderr = self.proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            stdout, stderr = self.proc.communicate(timeout=5)
        self.assertNotIn(TOKEN, stdout or "")
        self.assertNotIn(TOKEN, stderr or "")

    def connect(self):
        return socket.create_connection(("127.0.0.1", self.port), 2)

    def test_post_accepts_without_echoing_the_token(self):
        with self.connect() as conn:
            conn.sendall(
                (
                    "POST /v1/listen?model=nova-3 HTTP/1.1\r\n"
                    "Host: 127.0.0.1\r\n"
                    f"Authorization: Token {TOKEN}\r\n"
                    "Content-Type: audio/wav\r\n"
                    "Content-Length: 0\r\n"
                    "Connection: close\r\n"
                    "\r\n"
                ).encode()
            )
            data = b""
            while chunk := conn.recv(4096):
                data += chunk
        self.assertTrue(data.startswith(b"HTTP/1.1 400 "), data)
        self.assertNotIn(TOKEN.encode(), data)

    def test_websocket_stays_open_and_answers_ping(self):
        spec = importlib.util.spec_from_file_location("deepgram_stand_in", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.accept_key(RFC_KEY), RFC_ACCEPT)
        with self.connect() as conn:
            conn.sendall(
                (
                    "GET /v1/listen HTTP/1.1\r\n"
                    "Host: 127.0.0.1\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {RFC_KEY}\r\n"
                    f"Authorization: Token {TOKEN}\r\n"
                    "Sec-WebSocket-Version: 13\r\n"
                    "\r\n"
                ).encode()
            )
            data = b""
            while b"\r\n\r\n" not in data:
                data += conn.recv(4096)
            self.assertIn(b"HTTP/1.1 101 ", data)
            self.assertIn(RFC_ACCEPT.encode(), data)
            self.assertNotIn(TOKEN.encode(), data)
            conn.sendall(masked(2, b"\x00\x00"))
            conn.sendall(masked(9, b"ping"))
            head = read_exact(conn, 2)
            self.assertEqual(head[0] & 0x0F, 0xA)
            self.assertEqual(head[1] & 0x7F, 4)
            self.assertEqual(read_exact(conn, 4), b"ping")
            conn.sendall(masked(9, b"more"))
            head = read_exact(conn, 2)
            self.assertEqual(head[0] & 0x0F, 0xA)
            self.assertEqual(read_exact(conn, head[1] & 0x7F), b"more")

    def test_daemon_listens_after_the_parent_returns(self):
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        with tempfile.TemporaryDirectory() as directory:
            pid_path = Path(directory) / "deepgram-stand-in.pid"
            log_path = Path(directory) / "log.txt"
            with log_path.open("w") as log:
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), str(port), str(pid_path)],
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=5,
                    env={key: value for key, value in os.environ.items() if key != "DEEPGRAM_STAND_IN_FOREGROUND"},
                )
            self.assertEqual(result.returncode, 0, log_path.read_text())
            deadline = time.time() + 5
            while time.time() < deadline:
                try:
                    with socket.create_connection(("127.0.0.1", port), 1):
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                self.fail(log_path.read_text())
            pid = int(pid_path.read_text().strip())
            self.assertGreater(pid, 0)
            self.assertNotIn(TOKEN, log_path.read_text())
            try:
                os.kill(pid, 15)
            except ProcessLookupError:
                self.fail("stand-in exited before cleanup")


class DictationOverrideSourceTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_release_does_not_read_an_override(self):
        release = self.read("app/src/release/java/dev/multiprompt/companion/dictation/DictationLinks.kt")
        m3 = self.read("app/src/m3/java/dev/multiprompt/companion/dictation/DictationLinks.kt")
        self.assertEqual(release, m3)
        for source in (release, m3):
            self.assertNotIn("BuildConfig", source)
            self.assertNotIn("DICTATION_LISTEN_URL", source)
            self.assertNotIn("10.0.2.2", source)
            self.assertIn("DeepgramEndpoint.url", source)
            self.assertIn("DeepgramEndpoint.HOSTS.last()", source)
        debug = self.read("app/src/debug/java/dev/multiprompt/companion/dictation/DictationLinks.kt")
        self.assertIn("BuildConfig.DICTATION_LISTEN_URL", debug)
        self.assertIn("ws://10.0.2.2:", debug)
        self.assertIn("wss://10.0.2.2:", debug)
        gradle = self.read("app/build.gradle.kts")
        debug_block = gradle.split("debug {", 1)[1].split("release {", 1)[0]
        self.assertIn("DICTATION_LISTEN_URL", debug_block)
        self.assertNotIn("DICTATION_LISTEN_URL", gradle.split("release {", 1)[1])
        main = self.read("app/src/main/res/xml/network_security_config.xml")
        debug_net = self.read("app/src/debug/res/xml/network_security_config.xml")
        self.assertNotIn("10.0.2.2", main)
        self.assertIn("10.0.2.2", debug_net)
        self.assertIn('cleartextTrafficPermitted="false"', main)
        self.assertIn('cleartextTrafficPermitted="false"', debug_net)

    def test_fixture_starts_the_stand_in_for_this_journey_only(self):
        fixture = self.read(".claude/skills/verify-multiprompt-android/scripts/fixture.sh")
        self.assertIn('[[ "${VERIFY_FEATURE:-}" == dictation-cancel.md ]]', fixture)
        self.assertIn("deepgram-stand-in.py", fixture)
        self.assertIn('"$FIXTURE/deepgram-stand-in.pid"', fixture)
        workflow = self.read(".github/workflows/verify.yml")
        self.assertIn("-PdictationListenUrl=ws://10.0.2.2:8765/v1/listen", workflow)
        self.assertIn("dictation-cancel.md", workflow)
        cleanup = self.read(".claude/skills/verify-multiprompt-android/scripts/cleanup.sh")
        self.assertIn("deepgram-stand-in", cleanup)
        drive = self.read(".claude/skills/verify-multiprompt-android/scripts/drive.sh")
        self.assertIn("adb emu avd hostmicon", drive)


if __name__ == "__main__":
    unittest.main()
