import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / 'app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt'
DRIVE = ROOT / '.claude/skills/verify-multiprompt-android/scripts/drive.sh'
MENU = ('Unfavorite', 'Favorite', 'End tmux session')


def session_card_labels():
    source = APP.read_text()
    start = source.index('private fun SessionCard(')
    end = source.index('\n@Composable', start)
    labels = set()
    for line in source[start:end].splitlines():
        if 'label =' in line:
            labels.update(re.findall(r'"([^"]+)"', line))
    return labels


def favorites_block():
    script = DRIVE.read_text()
    start = script.rindex('if [[ "$feature" == session-favorites.md ]]; then')
    end = script.index('\nif [[ "$feature" == ', start + 1)
    return script[start:end]


class SessionRowMenuTest(unittest.TestCase):
    def test_the_journey_opens_rows_that_are_on_the_session_menu(self):
        labels = session_card_labels()
        block = favorites_block()
        for word in MENU:
            self.assertIn(word, labels, f'{word} is not on the session row menu')
            self.assertIn(word, block, f'{word} is not used by the favorites journey')

    def test_the_archive_row_is_gone_from_the_app_and_the_journey(self):
        self.assertNotIn('Archive Session', session_card_labels())
        self.assertNotIn('Archive Session', favorites_block())


if __name__ == '__main__':
    unittest.main()
