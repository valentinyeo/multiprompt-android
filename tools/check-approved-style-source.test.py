import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(os.environ.get("APPROVED_STYLE_SCRIPT", Path(__file__).with_name("check-approved-style.sh")))


class ApprovedStyleSourceTest(unittest.TestCase):
    def test_original_graph_is_complete_and_current_graph_restored_on_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("tools", "bin", "app/src/main/java/dev/multiprompt/companion/ui", "app/src/androidTest/java/dev/multiprompt/companion/ui"):
                (root / name).mkdir(parents=True)
            shutil.copy(SOURCE, root / "tools/check-approved-style.sh")
            ui = Path("app/src/main/java/dev/multiprompt/companion/ui")
            tests = Path("app/src/androidTest/java/dev/multiprompt/companion/ui")
            for file in ("Theme.kt", "MultipromptApp.kt", "ChatBubble.kt", "ChatHistoryList.kt", "AppMenu.kt"):
                (root / ui / file).write_text("current " + file)
            (root / "app/src/main/NativeSkills.kt").write_text("native")
            (root / tests / "Fixture.kt").write_text("current fixture")
            (root / tests / "ApprovedStyleTest.kt").write_text("unchanged style assertions")
            baseline = root / "baseline"
            for path in (ui, tests):
                (baseline / path).mkdir(parents=True)
            for file in ("Theme.kt", "MultipromptApp.kt", "ChatBubble.kt", "ChatHistoryList.kt", "AppMenu.kt"):
                (baseline / ui / file).write_text("original " + file)
            (baseline / "app/src/main/SkillStore.kt").write_text("original dependency")
            (baseline / tests / "Fixture.kt").write_text("original fixture")
            git = root / "bin/git"
            git.write_text('''#!/usr/bin/env python3
import io
from pathlib import Path
import sys
import tarfile
root = Path.cwd()
if sys.argv[1] == 'archive':
    with tarfile.open(fileobj=sys.stdout.buffer, mode='w|') as tar:
        for name in sys.argv[3:]:
            tar.add(root / 'baseline' / name, arcname=name)
else:
    name = sys.argv[2].split(':', 1)[1]
    sys.stdout.write((root / 'baseline' / name).read_text())
''')
            git.chmod(0o700)
            gradle = root / "bin/gradle-stub"
            gradle.write_text('''#!/usr/bin/env python3
from pathlib import Path
import sys
if 'connectedDebugAndroidTest' in sys.argv:
    raise SystemExit(1)
assert Path('app/src/main/SkillStore.kt').exists(), 'baseline omitted legacy dependency'
assert not Path('app/src/main/NativeSkills.kt').exists(), 'baseline mixed current source'
root = Path('app/src/androidTest/java/dev/multiprompt/companion/ui')
assert (root / 'Fixture.kt').read_text() == 'original fixture'
assert (root / 'ApprovedStyleTest.kt').read_text() == 'unchanged style assertions'
''')
            gradle.chmod(0o700)
            env = dict(os.environ, STYLE_PHASE="before", GRADLE=str(gradle), PATH=str(root / "bin") + os.pathsep + os.environ["PATH"])
            prepared = subprocess.run(["bash", "tools/check-approved-style.sh", "prepare"], cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(prepared.returncode, 0, prepared.stdout + prepared.stderr)
            failed = subprocess.run(["bash", "tools/check-approved-style.sh"], cwd=root, env=env, capture_output=True, text=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual((root / "app/src/main/NativeSkills.kt").read_text(), "native")
            self.assertFalse((root / "app/src/main/SkillStore.kt").exists())
            self.assertEqual((root / ui / "MultipromptApp.kt").read_text(), "current MultipromptApp.kt")
            self.assertEqual((root / tests / "Fixture.kt").read_text(), "current fixture")


if __name__ == "__main__":
    unittest.main()
