import sys
from pathlib import Path

TARGET = Path('app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt')
ANCHOR = '    onSetPinned: (TmuxSession, Boolean) -> Unit = { _, _ -> },\n'
PARAMETER = '    sessionListState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),\n'
DECLARATION = '    val sessionListState = rememberLazyListState()\n'


def expose(path):
    source = path.read_text()
    if PARAMETER in source:
        print(f'{path}: SessionsScreen already takes the inbox list state')
        return 0
    if source.count(ANCHOR) != 1 or source.count(DECLARATION) != 1:
        print(f'{path}: no inbox list state to expose, so the snapshot probe cannot build on this revision. '
              f'SessionsScreen changed shape', file=sys.stderr)
        return 0
    patched = source.replace(DECLARATION, '', 1).replace(ANCHOR, ANCHOR + PARAMETER, 1)
    path.write_text(patched)
    print(f'{path}: SessionsScreen now takes the inbox list state the snapshot probe measures')
    return 0


def main(argv):
    root = Path(argv[0] if argv else '.')
    path = root / TARGET
    if not path.is_file():
        print(f'{path}: missing', file=sys.stderr)
        return 1
    return expose(path)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
