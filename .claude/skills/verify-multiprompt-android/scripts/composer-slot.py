#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "dev.multiprompt.companion"
STOP = "Stop"
SEND = "Send prompt"
INTERRUPT = "VERIFY_INTERRUPTED_528"
RECEIVED = "VERIFY_RECEIVED:"


def parents_of(root):
    return {child: parent for parent in root.iter() for child in parent}


def described(root, description):
    return [
        node for node in root.iter("node")
        if node.get("content-desc") == description and (node.get("package") or "").startswith(PACKAGE)
    ]


def actionable(node, parents):
    current = node
    while current is not None:
        if current.get("clickable") == "true" and current.get("enabled") != "false":
            return True
        if current.get("enabled") == "false":
            return False
        current = parents.get(current)
    return False


def require_one(nodes, label):
    if len(nodes) != 1:
        raise SystemExit(f"expected one {label}, found {len(nodes)}")
    return nodes[0]


def check_ui(mode, path):
    root = ET.parse(path).getroot()
    parents = parents_of(root)
    stops = described(root, STOP)
    sends = described(root, SEND)
    if mode == "stop":
        button = require_one(stops, "Stop")
        if sends:
            raise SystemExit("Send prompt is still visible")
        if not actionable(button, parents):
            raise SystemExit("Stop is not enabled")
        print("COMPOSER_STOP")
        return
    if mode in ("send", "send-ready"):
        button = require_one(sends, "Send prompt")
        if stops:
            raise SystemExit("Stop is still visible")
        if mode == "send-ready" and not actionable(button, parents):
            raise SystemExit("Send prompt is not enabled")
        print("COMPOSER_SEND_READY" if mode == "send-ready" else "COMPOSER_SEND")
        return
    raise SystemExit(f"unknown composer mode {mode}")


def receipt_lines(path):
    file = Path(path)
    if not file.exists():
        return None
    return file.read_text(encoding="utf-8").splitlines()


def host_saw_input(rows):
    return any(row == INTERRUPT or row.startswith(RECEIVED) or row.startswith("VERIFY_ENTER_") for row in rows)


def check_receipt(mode, path):
    rows = receipt_lines(path)
    if mode == "quiet":
        if rows is not None and host_saw_input(rows):
            raise SystemExit("host already received input")
        print("COMPOSER_HOST_QUIET")
        return
    if mode == "interrupted":
        if rows is None or INTERRUPT not in rows:
            raise SystemExit("interrupt receipt missing")
        if any(row.startswith(RECEIVED) for row in rows):
            raise SystemExit("host received a prompt")
        print("COMPOSER_HOST_INTERRUPTED")
        return
    raise SystemExit(f"unknown receipt mode {mode}")


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: composer-slot.py <mode> <path>")
    mode, path = sys.argv[1], sys.argv[2]
    if mode in ("stop", "send", "send-ready"):
        check_ui(mode, path)
        return
    if mode in ("quiet", "interrupted"):
        check_receipt(mode, path)
        return
    raise SystemExit("composer slot mode must be stop, send, send-ready, quiet or interrupted")


if __name__ == "__main__":
    main()
