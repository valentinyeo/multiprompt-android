import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class HomeScreenTest(unittest.TestCase):
    def probe(self, surface):
        with tempfile.TemporaryDirectory() as folder:
            xml = Path(folder) / 'screen.xml'
            xml.write_text(f'<hierarchy><node resource-id="com.google.android.apps.nexuslauncher:id/{surface}"/><node text="multiprompt" package="com.google.android.apps.nexuslauncher" bounds="[472,1461][609,1616]"/></hierarchy>')
            return subprocess.run([sys.executable, str(Path(__file__).with_name('launcher-icon.py')), 'home', str(xml)], capture_output=True, text=True)

    def test_actual_home_screen_is_accepted(self):
        result = self.probe('workspace')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_drawer_with_the_same_icon_is_rejected(self):
        result = self.probe('apps_view')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('LAUNCHER_NOT_HOME_SCREEN', result.stderr)


if __name__ == '__main__':
    unittest.main()
