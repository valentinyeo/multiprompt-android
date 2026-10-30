import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('history_tests', ROOT / 'tools/chat-history-reader-test.py')
history_tests = importlib.util.module_from_spec(spec)
spec.loader.exec_module(history_tests)

before = '--before' in sys.argv
if before:
    snapshot = ROOT / 'tools/fixtures/IGSH-835/queue-replay-before.py'
    exec(compile(snapshot.read_text(encoding='utf-8'), str(snapshot), 'exec'), history_tests.reader.__dict__)
    old_queue_replay = history_tests.reader.queue_replay

    def queue_replay(source, size, with_receipts=False):
        queue, sent = old_queue_replay(source, size)
        return (queue, [(text, None) for text in sent]) if with_receipts else (queue, sent)

    history_tests.reader.queue_replay = queue_replay

names = [name for name in unittest.defaultTestLoader.getTestCaseNames(history_tests.SharedQueueDraftTest)
         if name.startswith('test_IGSH_835_')]
if before:
    names = [name for name in names if 'recorded_transcript' in name or 'identical_sends' in name]
suite = unittest.TestSuite(history_tests.SharedQueueDraftTest(name) for name in names)
result = unittest.TextTestRunner(verbosity=2).run(suite)
sys.exit(0 if result.wasSuccessful() else 1)
