import hashlib
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[1]).resolve()
evidence = Path(sys.argv[2]).resolve()
assert subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() == '75c43b90864ef8479ba1dda52a1f04d4d0e3d968'
file = source / 'app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt'
old = '''    var promptField by remember(connection) {
        mutableStateOf(TextFieldValue(draftStore.composerDraft(session)))
    }'''
new = '''    val breakItLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var promptField by remember(connection, breakItLandscape) {
        mutableStateOf(TextFieldValue(if (breakItLandscape) "" else draftStore.composerDraft(session)))
    }'''
text = file.read_text()
assert text.count(old) == 1, 'Rotation control target changed'
file.write_text(text.replace(old, new))
evidence.mkdir(parents=True, exist_ok=True)
patch = subprocess.check_output(['git', '-C', str(source), 'diff', '--', str(file)])
(evidence / 'planted-rotation-loss.patch').write_bytes(patch)
(evidence / 'planted-rotation-loss-sha256.txt').write_text(hashlib.sha256(patch).hexdigest() + '\n')
print('PLANTED_ROTATION_DRAFT_LOSS')
