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


class ResourceFileTest(unittest.TestCase):
    def probe(self, resources, name='mipmap/ic_launcher'):
        with tempfile.TemporaryDirectory() as folder:
            table = Path(folder) / 'resources.txt'
            table.write_text(resources)
            return subprocess.run([sys.executable, str(Path(__file__).with_name('launcher-icon.py')), 'resource-file', str(table), name], capture_output=True, text=True)

    def test_signed_apk_renamed_resource_is_resolved(self):
        result = self.probe("""Package name=dev.multiprompt.companion id=7f
  type mipmap id=0e entryCount=2
    resource 0x7f0e0000 mipmap/ic_launcher
      (anydpi-v26) (file) res/BW.xml type=XML
    resource 0x7f0e0001 mipmap/ic_launcher_round
      (anydpi-v26) (file) res/other.xml type=XML
""")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'res/BW.xml')

    def test_unrenamed_debug_resource_still_resolves(self):
        result = self.probe("""    resource 0x7f0e0000 mipmap/ic_launcher
      (anydpi-v26) (file) res/mipmap-anydpi-v26/ic_launcher.xml type=XML
""")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'res/mipmap-anydpi-v26/ic_launcher.xml')

    def test_monochrome_resource_is_resolved_by_name(self):
        result = self.probe("""    resource 0x7f080000 drawable/ic_launcher_monochrome
      () (file) res/Vy.xml type=XML
""", 'drawable/ic_launcher_monochrome')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'res/Vy.xml')

    def test_neighbor_resource_cannot_satisfy_missing_icon(self):
        result = self.probe("""    resource 0x7f0e0001 mipmap/ic_launcher_round
      (anydpi-v26) (file) res/other.xml type=XML
""")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('LAUNCHER_RESOURCE_NOT_UNIQUE', result.stderr)

    def test_missing_file_does_not_consume_next_resource(self):
        result = self.probe("""    resource 0x7f0e0000 mipmap/ic_launcher
      (anydpi-v26) @0x7f0e0001
    resource 0x7f0e0001 mipmap/ic_launcher_round
      (anydpi-v26) (file) res/other.xml type=XML
""")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('LAUNCHER_RESOURCE_FILE_NOT_UNIQUE', result.stderr)


if __name__ == '__main__':
    unittest.main()
