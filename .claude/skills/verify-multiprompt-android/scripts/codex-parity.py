import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
readers = []
for index, path in enumerate(sys.argv[1:]):
    spec = importlib.util.spec_from_file_location("reader_" + str(index), path)
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    readers.append(reader)
for mode in ("hax", "codex"):
    query = dict(id=1, codex=True, sid="verify-" + mode, tmux="", cwd=str(Path.home()),
                 cached_sid="", inode=0, offset=0, start=0, no_jump=True, source_offsets=True)
    posts = []
    for reader in readers:
        wire = reader.response(query).splitlines()
        assert wire[0].split("\t")[3] == "ok", wire[0]
        rows = [json.loads(line) for line in wire[1:-1]]
        posts.append([(row["role"], row["text"]) for row in rows if row["role"] in ("user", "assistant")])
    assert posts[0] == posts[1], posts
    assert ("assistant", "CODEX_REPLY_" + mode + "_PHONE_CODEX") in posts[0], posts
    print(json.dumps(dict(session=mode, desktop=posts[0], android=posts[1])))
print("DESKTOP_ANDROID_POSTS_IDENTICAL")
