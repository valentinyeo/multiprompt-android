import importlib.util
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock


assets = Path(sys.argv.pop(1)).resolve()
sys.path.insert(0, str(assets))
spec = importlib.util.spec_from_file_location('room_bridge', assets / 'room_bridge.py')
bridge_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge_module)


def uid():
    return str(uuid.uuid4())


class FakeRuntime:
    def __init__(self):
        self.submits = []
        self.stored = {}
        self.events = {}
        self.fail_once = set()
        self.answer = True

    def request(self, query):
        op = query['op']
        if op == 'engine_info':
            return {'id': 1, 'room_message_protocols': [1]}
        if op == 'message_status':
            return {'id': 1, 'result': {'seq': len(self.events.get(query['sid'], []))}}
        if op == 'message_submit':
            key = query['message_id']
            if key in self.fail_once:
                self.fail_once.discard(key)
                bridge_module.fail('runtime_unavailable')
            if key in self.stored:
                if self.stored[key] != query['text']:
                    bridge_module.fail('runtime_unavailable')
            else:
                self.stored[key] = query['text']
                self.submits.append((query['sid'], key, query['text']))
                if self.answer:
                    self.reply(query['sid'], key, query['room_scope'], 'reply to ' + query['text'])
            return {'id': 1, 'result': {'message': {'id': key}}}
        if op == 'events_subscribe':
            rows = self.events.get(query['sid'], [])
            page = [row for row in rows if row['seq'] > query['after']]
            return {'id': 1, 'result': {'events': page, 'next_after': rows[-1]['seq'] if rows else query['after']}}
        raise AssertionError(op)

    def reply(self, sid, message_id, scope, text):
        rows = self.events.setdefault(sid, [])
        turn = 'turn-' + message_id
        for kind, payload in (('turn.started', {'message_id': message_id}), ('text.done', {'text': text}), ('turn.ended', {'status': 'completed'})):
            rows.append({'seq': len(rows) + 1, 'event': {'sid': sid, 'room_scope': scope, 'message_id': message_id, 'kind': kind, 'turn': turn, 'item': 'item-' + message_id, 'payload': payload}})


class RoutingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.runtime = FakeRuntime()
        self.enabled = {}
        self.patches = [mock.patch.object(bridge_module, 'runtime_request', self.runtime.request), mock.patch.object(bridge_module, 'host_status', self.status)]
        for patch in self.patches:
            patch.start()
        self.open()
        self.host = self.bridge.execute({'op': 'capabilities'})['host_uuid']
        self.sids = {'alpha': uid(), 'beta': uid()}
        for sid in self.sids.values():
            self.enabled[sid] = True
        members = [{'alias': alias, 'display_name': alias.title(), 'host_uuid': self.host, 'provider': 'claude', 'provider_sid': sid, 'session_identity': 'claude:' + sid} for alias, sid in self.sids.items()]
        self.room = self.bridge.execute({'op': 'create', 'host_uuid': self.host, 'command_id': uid(), 'name': 'Room', 'members': members})['event']['room_id']
        self.revision = 1

    def tearDown(self):
        self.bridge.close()
        for patch in self.patches:
            patch.stop()
        self.temp.cleanup()

    def open(self):
        self.bridge = bridge_module.RoomBridge(self.temp.name)

    def status(self, sid):
        return {'status': 'ok', 'provider': 'claude', 'sid': sid, 'enabled': self.enabled.get(sid, True), 'tmux': 'tmux-' + sid[:8]}

    def post(self, text, recipients=()):
        return self.bridge.execute({'op': 'post_human', 'host_uuid': self.host, 'command_id': uid(), 'room_id': self.room, 'expected_revision': self.revision, 'text': text, 'recipients': list(recipients)})

    def sweep(self):
        return self.bridge.execute({'op': 'events', 'host_uuid': self.host, 'room_id': self.room, 'after': 0, 'limit': 200})

    def sent_to(self, alias):
        return [text for sid, _, text in self.runtime.submits if sid == self.sids[alias]]

    def test_plain_message_reaches_every_agent_once(self):
        self.post('hello all')
        self.sweep()
        self.sweep()
        self.assertEqual(['hello all'], self.sent_to('alpha'))
        self.assertEqual(['hello all'], self.sent_to('beta'))
        replies = [e for e in self.sweep()['events'] if e['type'] == 'agent_reply']
        self.assertEqual(2, len(replies))

    def test_mention_reaches_only_that_agent(self):
        self.post('@beta hello', ['beta'])
        self.sweep()
        self.assertEqual([], self.sent_to('alpha'))
        self.assertEqual(['@beta hello'], self.sent_to('beta'))

    def test_each_agent_receives_messages_in_post_order_after_a_runtime_failure(self):
        first = self.post('one')
        self.post('two')
        self.runtime.fail_once.add(first['deliveries'][1]['id'])
        self.sweep()
        self.sweep()
        self.sweep()
        self.assertEqual(['one', 'two'], self.sent_to('alpha'))
        self.assertEqual(['one', 'two'], self.sent_to('beta'))

    def test_offline_agent_receives_message_when_it_returns(self):
        self.enabled[self.sids['beta']] = False
        self.post('hello')
        self.sweep()
        self.assertEqual(['hello'], self.sent_to('alpha'))
        self.assertEqual([], self.sent_to('beta'))
        self.enabled[self.sids['beta']] = True
        self.sweep()
        self.assertEqual(['hello'], self.sent_to('beta'))

    def test_agent_coming_back_mid_sweep_still_gets_messages_in_order(self):
        self.post('one')
        self.post('two')
        beta = self.sids['beta']
        answers = iter([False, True, True, True, True, True])
        original = self.status

        def flipping(sid):
            status = original(sid)
            if sid == beta:
                status['enabled'] = next(answers, True)
            return status

        with mock.patch.object(bridge_module, 'host_status', flipping):
            self.sweep()
            self.sweep()
        self.assertEqual(['one', 'two'], self.sent_to('beta'))

    def test_longest_message_still_reaches_the_agent_with_context(self):
        self.post('first question')
        self.sweep()
        long_text = 'x' * 65000
        self.post(long_text)
        self.sweep()
        self.assertEqual(long_text, self.sent_to('beta')[1])
        self.assertTrue(all(len(text.encode('utf-8')) <= 66000 for text in self.sent_to('alpha') + self.sent_to('beta')))

    def test_restart_neither_loses_nor_repeats(self):
        self.post('hello')
        self.sweep()
        before = list(self.runtime.submits)
        self.bridge.close()
        self.open()
        page = self.sweep()
        self.assertEqual(before, self.runtime.submits)
        self.assertEqual(2, len([e for e in page['events'] if e['type'] == 'agent_reply']))

    def test_agents_see_the_same_ordered_conversation(self):
        self.post('first question')
        self.sweep()
        self.post('@alpha private question', ['alpha'])
        self.sweep()
        self.post('third question')
        self.sweep()
        alpha, beta = self.sent_to('alpha'), self.sent_to('beta')
        self.assertEqual(3, len(alpha))
        self.assertEqual(2, len(beta))
        self.assertEqual('first question', alpha[0])
        self.assertEqual('first question', beta[0])
        self.assertIn('beta: reply to first question', alpha[1])
        self.assertNotIn('alpha: reply to first question', alpha[1])
        self.assertTrue(alpha[1].endswith('@alpha private question'))
        self.assertIn('alpha: reply to first question', beta[1])
        self.assertNotIn('private question', beta[1])
        self.assertTrue(beta[1].endswith('third question'))
        self.bridge.close()
        self.open()
        self.sweep()
        self.assertEqual(alpha, self.sent_to('alpha'))
        self.assertEqual(beta, self.sent_to('beta'))


if __name__ == '__main__':
    unittest.main()
