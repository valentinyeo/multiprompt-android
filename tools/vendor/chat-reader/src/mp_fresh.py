#!/usr/bin/env python3
import argparse
import importlib.util
import os
import sys
from pathlib import Path

_reader = None


def reader_path():
    configured = os.environ.get("MULTIPROMPT_READER_SOURCE")
    if configured:
        return Path(configured)
    runtime = Path.home() / ".multiprompt-runtime"
    try:
        active = __import__("json").loads((runtime / "active.json").read_text(encoding="utf-8"))
        digest = active.get("sha256") if isinstance(active, dict) else None
        if isinstance(digest, str) and len(digest) == 64:
            path = runtime / (digest + ".py")
            if path.is_file():
                return path
    except (OSError, ValueError):
        pass
    installed = runtime / "reader.py"
    if installed.is_file():
        return installed.resolve()
    here = Path(__file__).resolve()
    sibling = here.with_name("mp_send.py")
    if sibling.is_file():
        loader = importlib.util.spec_from_file_location("mp_send", sibling)
        if loader and loader.loader:
            module = importlib.util.module_from_spec(loader)
            loader.loader.exec_module(module)
            return module.reader_path()
    return here.parent / "chat_history_reader.py"


def reader_module():
    global _reader
    if _reader is not None:
        return _reader
    path = reader_path()
    spec = importlib.util.spec_from_file_location("mp_reader", path)
    if spec is None or spec.loader is None:
        raise ValueError("reader not loadable: " + str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _reader = module
    return module


def main():
    parser = argparse.ArgumentParser(prog="mp-fresh")
    parser.add_argument("--handover", metavar="file", help="agent handover file replaces the host prompt")
    args = parser.parse_args()
    provider = os.environ.get("MULTIBOT_PROVIDER")
    sid = os.environ.get("MULTIBOT_SID")
    if not isinstance(provider, str) or not provider or not isinstance(sid, str) or not sid:
        print("mp-fresh: not running under agent host", file=sys.stderr)
        raise SystemExit(3)
    handover = args.handover
    if handover is not None and (not handover.strip() or "\x00" in handover):
        print("mp-fresh: invalid handover file", file=sys.stderr)
        raise SystemExit(2)
    reply = reader_module().host_reply(dict(op="fresh_start", provider=provider, sid=sid,
                                             handover_file=handover if handover else None))
    status = reply.get("status") if isinstance(reply, dict) else None
    reason = reply.get("reason") if isinstance(reply, dict) else None
    if status == "started":
        raise SystemExit(0)
    if status == "already" or reason == "fresh_start_pending":
        raise SystemExit(4)
    label = reason or status or "fresh_start_failed"
    print("mp-fresh: " + str(label), file=sys.stderr)
    raise SystemExit(1)


if __name__ == "__main__":
    main()