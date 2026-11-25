#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import threading
import time

LOG = os.environ.get('SCALE_RELAY_LOG') or '/home/verifyssh/scale-relay.jsonl'
COMMAND_LABELS = (('--serve', 'history'), ('tmux', 'tmux'), ('python3', 'reader'))
CHUNK = 65536


def stream_label(command):
    for needle, label in COMMAND_LABELS:
        if needle in command:
            return label
    return 'exec'


def open_log():
    try:
        return open(LOG, 'a', encoding='utf-8', buffering=1)
    except OSError:
        return None


class Recorder:
    def __init__(self, log, label):
        self.log = log
        self.label = label
        self.lock = threading.Lock()
        self.requests = 0
        self.request_bytes = 0
        self.request_at = time.monotonic()
        self.calls = 0
        self.total = 0
        self.consumer_closed = False

    def request(self, size):
        with self.lock:
            self.requests += 1
            self.request_bytes = size
            self.request_at = time.monotonic()

    def reply(self, size):
        with self.lock:
            self.calls += 1
            self.total += size
            self.write(dict(event='call', stream=self.label, call=self.calls, request=self.requests, bytes=size,
                            request_bytes=self.request_bytes,
                            ms=round((time.monotonic() - self.request_at) * 1000, 1), t=round(time.time(), 3)))
            self.request_at = time.monotonic()

    def write(self, entry):
        if self.log is not None:
            self.log.write(json.dumps(entry, separators=(',', ':')) + '\n')


def forward_input(child, recorder):
    pending = 0
    try:
        while True:
            try:
                chunk = os.read(sys.stdin.fileno(), CHUNK)
            except OSError:
                break
            if not chunk:
                break
            try:
                child.stdin.write(chunk)
                child.stdin.flush()
            except (BrokenPipeError, ValueError, OSError):
                break
            offset = 0
            while True:
                newline = chunk.find(b'\n', offset)
                if newline < 0:
                    break
                recorder.request(pending + newline + 1 - offset)
                pending = 0
                offset = newline + 1
            pending += len(chunk) - offset
    finally:
        try:
            child.stdin.close()
        except OSError:
            pass


def forward_output(child, recorder):
    pending = 0
    try:
        while True:
            try:
                chunk = os.read(child.stdout.fileno(), CHUNK)
            except OSError:
                break
            if not chunk:
                break
            try:
                os.write(sys.stdout.fileno(), chunk)
            except OSError:
                recorder.consumer_closed = True
                break
            offset = 0
            while True:
                newline = chunk.find(b'\n', offset)
                if newline < 0:
                    break
                recorder.reply(pending + newline + 1 - offset)
                pending = 0
                offset = newline + 1
            pending += len(chunk) - offset
    finally:
        if pending:
            recorder.reply(pending)


def main():
    command = os.environ.get('SSH_ORIGINAL_COMMAND') or ''
    if not command.strip():
        os.execv('/bin/bash', ['/bin/bash'])
    label = stream_label(command)
    recorder = Recorder(open_log(), label)
    started = time.monotonic()
    recorder.write(dict(event='open', stream=label, list_sessions='__MP_TMUX_BEGIN__' in command,
                        command=command[:120], t=round(time.time(), 3)))
    child = subprocess.Popen(['/bin/bash', '-c', command], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    output = threading.Thread(target=forward_output, args=(child, recorder), daemon=True)
    feeding = threading.Thread(target=forward_input, args=(child, recorder), daemon=True)
    output.start()
    feeding.start()
    output.join()
    try:
        child.stdin.close()
    except OSError:
        pass
    try:
        code = child.wait(timeout=10)
    except subprocess.TimeoutExpired:
        child.kill()
        code = child.wait()
    recorder.write(dict(event='close', stream=label, calls=recorder.calls, bytes=recorder.total,
                        ms=round((time.monotonic() - started) * 1000, 1), code=code,
                        consumer_closed=recorder.consumer_closed, t=round(time.time(), 3)))
    return code


if __name__ == '__main__':
    sys.exit(main())
