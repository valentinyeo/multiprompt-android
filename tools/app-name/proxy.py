#!/usr/bin/env python3
import select
import socket
import socketserver
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

ALLOWED = {
    "github.com:443": ("github.com", 443),
    "release-assets.githubusercontent.com:443": ("release-assets.githubusercontent.com", 443),
}
IDLE_TIMEOUT = 60
MAX_DURATION = 300


class Handler(BaseHTTPRequestHandler):
    rbufsize = 0
    wbufsize = 0
    timeout = 15

    def log_message(self, format, *args):
        pass

    def record(self, outcome):
        method = self.command if self.command and self.command.isascii() and self.command.isalpha() else "INVALID"
        authority = self.path if self.path in ALLOWED else "DENIED"
        print(f"{method} {authority} {outcome}", flush=True)

    def deny(self):
        self.record("DENIED")
        self.send_response_only(501, "Not Implemented")
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def handle_one_request(self):
        try:
            self.raw_requestline = self.rfile.readline(65537)
            if len(self.raw_requestline) > 65536:
                self.close_connection = True
                return
            if not self.raw_requestline:
                self.close_connection = True
                return
            if not self.parse_request():
                return
            if self.command == "CONNECT":
                self.connect_tunnel()
            else:
                self.deny()
            self.wfile.flush()
        except (TimeoutError, OSError):
            self.close_connection = True

    def connect_tunnel(self):
        destination = ALLOWED.get(self.path)
        if destination is None:
            self.deny()
            return

        self.close_connection = True
        try:
            upstream = socket.create_connection(destination, timeout=15)
        except OSError:
            self.record("CONNECT_FAILED")
            self.send_response_only(502, "Bad Gateway")
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
            return

        with upstream:
            self.record("ALLOWED")
            self.send_response_only(200, "Connection Established")
            self.end_headers()
            self.wfile.flush()

            client = self.connection
            sockets = (client, upstream)
            started = time.monotonic()
            last_activity = started

            while True:
                now = time.monotonic()
                remaining = min(
                    MAX_DURATION - (now - started),
                    IDLE_TIMEOUT - (now - last_activity),
                )
                if remaining <= 0:
                    return

                try:
                    readable, _, exceptional = select.select(
                        sockets, [], sockets, remaining
                    )
                except (OSError, ValueError):
                    return
                if exceptional or not readable:
                    return

                for source in readable:
                    now = time.monotonic()
                    remaining = min(
                        MAX_DURATION - (now - started),
                        IDLE_TIMEOUT - (now - last_activity),
                    )
                    if remaining <= 0:
                        return
                    destination = upstream if source is client else client
                    try:
                        source.settimeout(remaining)
                        data = source.recv(65536)
                        if not data:
                            return
                        now = time.monotonic()
                        remaining = min(
                            MAX_DURATION - (now - started),
                            IDLE_TIMEOUT - (now - last_activity),
                        )
                        if remaining <= 0:
                            return
                        destination.settimeout(remaining)
                        destination.sendall(data)
                    except OSError:
                        return
                    last_activity = time.monotonic()


class Server(socketserver.ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    with Server(("127.0.0.1", 8788), Handler) as server:
        server.serve_forever()
