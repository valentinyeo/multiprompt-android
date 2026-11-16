#!/usr/bin/env python3
import base64
import hashlib
import os
import signal
import socketserver
import sys
from pathlib import Path

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_HEADER = 65536
MAX_FRAME = 1_000_000


def accept_key(value):
    digest = hashlib.sha1((value + GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


class Reader:
    def __init__(self, conn, pending=b""):
        self.conn = conn
        self.pending = pending

    def read(self, count):
        while len(self.pending) < count:
            chunk = self.conn.recv(max(4096, count - len(self.pending)))
            if not chunk:
                return None
            self.pending += chunk
            if len(self.pending) > MAX_HEADER + MAX_FRAME:
                return None
        out = self.pending[:count]
        self.pending = self.pending[count:]
        return out

    def headers(self):
        while b"\r\n\r\n" not in self.pending:
            chunk = self.conn.recv(4096)
            if not chunk:
                return None
            self.pending += chunk
            if len(self.pending) > MAX_HEADER:
                return None
        head, self.pending = self.pending.split(b"\r\n\r\n", 1)
        text = head.decode("iso-8859-1", "replace")
        lines = text.split("\r\n")
        headers = {}
        for line in lines[1:]:
            if ":" not in line:
                continue
            name, value = line.split(":", 1)
            headers[name.strip().lower()] = value.strip()
        return lines[0], headers


def send_frame(conn, opcode, payload):
    header = bytearray([0x80 | opcode])
    size = len(payload)
    if size < 126:
        header.append(size)
    elif size < 65536:
        header.append(126)
        header += size.to_bytes(2, "big")
    else:
        header.append(127)
        header += size.to_bytes(8, "big")
    conn.sendall(header + payload)


def websocket(reader, key):
    reader.conn.sendall(
        (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_key(key)}\r\n"
            "\r\n"
        ).encode("ascii")
    )
    while True:
        head = reader.read(2)
        if head is None:
            return
        opcode = head[0] & 0x0F
        masked = head[1] & 0x80
        length = head[1] & 0x7F
        if length == 126:
            extra = reader.read(2)
            if extra is None:
                return
            length = int.from_bytes(extra, "big")
        elif length == 127:
            extra = reader.read(8)
            if extra is None:
                return
            length = int.from_bytes(extra, "big")
        if length > MAX_FRAME or (opcode in (8, 9, 10) and length > 125):
            return
        mask = b""
        if masked:
            mask = reader.read(4)
            if mask is None:
                return
        payload = reader.read(length) if length else b""
        if payload is None:
            return
        if masked:
            payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        if opcode == 8:
            send_frame(reader.conn, 8, payload[:2])
            return
        if opcode == 9:
            send_frame(reader.conn, 10, payload)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        reader = Reader(self.request)
        parsed = reader.headers()
        if parsed is None:
            return
        request, headers = parsed
        if headers.get("upgrade", "").lower() == "websocket" and request.startswith("GET "):
            key = headers.get("sec-websocket-key", "")
            if key:
                websocket(reader, key)
            return
        length = int(headers.get("content-length", "0") or "0")
        if length < 0 or length > MAX_FRAME:
            return
        if reader.read(length) is None:
            return
        self.request.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def daemonize(pid_path):
    if os.fork() > 0:
        os._exit(0)
    os.setsid()
    if os.fork() > 0:
        os._exit(0)
    devnull = os.open(os.devnull, os.O_RDWR)
    os.dup2(devnull, 0)
    os.close(devnull)
    Path(pid_path).write_text(f"{os.getpid()}\n")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    if port < 0 or port > 65535:
        raise SystemExit(2)
    pid_path = sys.argv[2] if len(sys.argv) > 2 else ""
    if pid_path and os.environ.get("DEEPGRAM_STAND_IN_FOREGROUND") != "1":
        daemonize(pid_path)
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    server = Server(("127.0.0.1", port), Handler)
    print(f"LISTENING {server.server_address[0]} {server.server_address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
