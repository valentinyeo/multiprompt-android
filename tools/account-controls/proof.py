import importlib.util
import json
from pathlib import Path
import sys


spec = importlib.util.spec_from_file_location('inbox_ui', Path(__file__).parents[1] / 'inbox-controls/ui.py')
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


def layout(path, expected):
    actual = json.loads(Path(path).read_text())
    names = [w['name'] for w in actual['workspaces']]
    tabs = {t['tmux_session']: t for t in actual['tabs']}
    for name in expected.get('contains', []):
        assert name in names, f'Missing workspace: {name}'
    for name in expected.get('absent', []):
        assert name not in names, f'Unexpected workspace: {name}'
    if 'order' in expected:
        assert names == expected['order'], f'Wrong workspace order: {names}'
    for session, fields in expected.get('tabs', {}).items():
        assert session in tabs, f'Missing session: {session}'
        for key, value in fields.items():
            assert tabs[session].get(key) == value, f'Wrong {key} for {session}: {tabs[session]}'
    for name in expected.get('no_group', []):
        assert not any(t.get('group') == name for t in tabs.values()), f'Group still assigned: {name}'
    return dict(receipt=Path(path).name, asserted=expected, layout=actual)


if __name__ == '__main__':
    if sys.argv[1] == 'layout':
        result = layout(sys.argv[2], json.loads(sys.argv[3]))
    else:
        result = ui.check(sys.argv[1], sys.argv[2], json.loads(sys.argv[3]))
    print(json.dumps(result, sort_keys=True))
