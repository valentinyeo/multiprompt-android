import subprocess
import tempfile
import unittest
from pathlib import Path

import historical_control as control

RUN_HEAD = 'ee0cacbb67cdc39e76e5ed5273fdda232727c6a3'
MERGED = '50eb80bbcc560ee2813d0eb869ab2364663275a8'
EXPECTED = 'PINNED_STRIP_MUST_NOT_SHOW'


def case(name, failure=None):
    body = f'<testcase classname="dev.multiprompt.companion.ui.HistoricalPinsOracle" name="{name}">'
    if failure:
        body += f'<failure message="{failure}">{failure}</failure>'
    return body + '</testcase>'


def xml_for(failures):
    rows = []
    for name in ('readerNormal', 'readerReversed', 'material3Normal', 'material3Reversed'):
        rows.append(case(name, failures.get(name)))
    return '<testsuite>' + ''.join(rows) + '</testsuite>'


class HistoricalControlTest(unittest.TestCase):
    def test_current_tree_matches_the_baseline(self):
        self.assertEqual(control.baseline_problems(control.scan(), control.load_baseline()), [])

    def test_igsh_2306_pins_swap_is_rejected(self):
        text = subprocess.check_output(
            ['git', 'show', f'{MERGED}:tools/check-session-pins.sh'],
            text=True,
        )
        swaps = control.find_swaps(text, 'tools/check-session-pins.sh')
        problems = control.baseline_problems(swaps, control.load_baseline())
        joined = '\n'.join(problems)
        self.assertIn('93f137d8adc6bd8993758e8564a8430612c06c4d:app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt', joined)
        self.assertIn('bd3c1b86:app/src/androidTest/java/dev/multiprompt/companion/ui/MessageActionsTest.kt', joined)
        self.assertIn('Build that complete revision in its own worktree', joined)
        self.assertNotIn('tools/check-session-pins.sh', '\n'.join(control.swap_problem(item) for item in control.scan() if item[0] == 'tools/check-session-pins.sh'))

    def test_igsh_2306_compile_mismatch_is_invalid_control(self):
        log = Path('tools/session-pins/igsh-2306-compile.txt').read_text(encoding='utf-8')
        self.assertIn("ComposableFunction1<String, Unit>", log)
        self.assertIn("ComposableFunction2<String, Int, Unit>", log)
        self.assertIn('run 43716979907', log)
        self.assertIn(RUN_HEAD, log)
        text, code = control.classify_log(log, EXPECTED)
        self.assertEqual(code, 0)
        self.assertIn('buildable=false launched=false assertionObserved=false', text)
        self.assertIn(
            'CONTROL_INVALID: baseline did not build or launch; '
            f'expected {EXPECTED} was never evaluated. '
            'Do not add a production compatibility overload.',
            text,
        )
        mixed = log + f'\n{EXPECTED}'
        text, code = control.classify_run('before', EXPECTED, mixed, Path('missing-results'), 1)
        self.assertEqual(code, 1)
        self.assertIn('CONTROL_INVALID:', text)
        self.assertIn('buildable=false', text)

    def test_complete_old_app_red_is_distinct_from_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'TEST-pins.xml').write_text(xml_for({name: f'{EXPECTED}: favourite chats appear as a pinned strip' for name in control.PIN_CASES}), encoding='utf-8')
            text, code = control.classify_run('before', EXPECTED, 'BUILD SUCCESSFUL', root, 1)
            self.assertEqual(code, 0, text)
            self.assertIn('buildable=true launched=true assertionObserved=true', text)
            (root / 'TEST-pins.xml').write_text(xml_for({}), encoding='utf-8')
            text, code = control.classify_run('before', EXPECTED, 'BUILD SUCCESSFUL', root, 0)
            self.assertEqual(code, 1)
            self.assertIn('HISTORICAL_RED_MISSING', text)
            text, code = control.classify_run('after', EXPECTED, 'BUILD SUCCESSFUL', root, 0)
            self.assertEqual(code, 0, text)
            (root / 'TEST-pins.xml').write_text(xml_for({name: EXPECTED for name in control.PIN_CASES}), encoding='utf-8')
            text, code = control.classify_run('after', EXPECTED, 'BUILD SUCCESSFUL', root, 1)
            self.assertEqual(code, 1)
            self.assertIn(f'{EXPECTED} returned on the current app', text)


if __name__ == '__main__':
    unittest.main()
