#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET

DOT = "Session favorite dot"
STARRED = "verify-session"
PLAIN = "verify-keep"
PACKAGE = "dev.multiprompt.companion"


def parents_of(root):
    return {child: parent for parent in root.iter() for child in parent}


def clickable_row(node, parents):
    row = node
    while row.get("clickable") != "true" and row in parents:
        row = parents[row]
    if row.get("clickable") != "true" or not (row.get("package") or "").startswith(PACKAGE):
        raise SystemExit("session row is not clickable")
    return row


def is_editor(node):
    return (node.get("class") or "").endswith("EditText")


def named_rows(root, name):
    parents = parents_of(root)
    rows = []
    seen = set()
    for node in root.iter("node"):
        if node.get("text") != name or not (node.get("package") or "").startswith(PACKAGE) or is_editor(node):
            continue
        row = clickable_row(node, parents)
        if is_editor(row) or id(row) in seen:
            continue
        seen.add(id(row))
        rows.append(row)
    return rows


def has_dot(row):
    return any(DOT in (node.get("content-desc") or "") for node in row.iter("node"))


def dot_nodes(root):
    return [
        node for node in root.iter("node")
        if DOT in (node.get("content-desc") or "") and (node.get("package") or "").startswith(PACKAGE)
    ]


def inside(row, node, parents):
    current = node
    while current is not None:
        if current is row:
            return True
        current = parents.get(current)
    return False


def require_pair(starred, plain):
    if len(starred) != 1 or len(plain) != 1:
        raise SystemExit(f"expected both chats, found starred={len(starred)} plain={len(plain)}")


def require_only_starred(root, starred):
    if len(starred) != 1:
        raise SystemExit(f"expected one {STARRED} row, found {len(starred)}")
    if not has_dot(starred[0]):
        raise SystemExit("starred chat has no yellow dot")
    parents = parents_of(root)
    outside = [node for node in dot_nodes(root) if not inside(starred[0], node, parents)]
    if outside:
        raise SystemExit("yellow dot is outside the starred chat")


def emit(result, present):
    print(json.dumps({
        "result": result,
        "marker": DOT if present else "",
        "session": STARRED,
    }, separators=(",", ":")))


def main():
    mode, path = sys.argv[1], sys.argv[2]
    if mode not in ("starred", "search", "plain", "favorites"):
        raise SystemExit("favorite dot mode must be starred, search, plain or favorites")
    root = ET.parse(path).getroot()
    starred = named_rows(root, STARRED)
    plain = named_rows(root, PLAIN)
    if mode == "starred":
        require_pair(starred, plain)
        if has_dot(plain[0]):
            raise SystemExit("unstarring chat still shows a yellow dot")
        require_only_starred(root, starred)
        emit("FAVORITE_DOT_ON_STARRED", True)
        return
    if mode == "search":
        if plain:
            raise SystemExit("search still shows the unstarred chat")
        if not any(node.get("text") == "1 result across all splits" for node in root.iter("node")):
            raise SystemExit("search did not report one result")
        require_only_starred(root, starred)
        emit("FAVORITE_DOT_IN_SEARCH", True)
        return
    if dot_nodes(root):
        raise SystemExit("yellow dot still visible")
    if mode == "plain":
        require_pair(starred, plain)
        emit("FAVORITE_DOT_GONE", False)
        return
    if len(starred) != 1 or plain:
        raise SystemExit(f"expected only the starred chat in Favorites, found starred={len(starred)} plain={len(plain)}")
    emit("FAVORITE_DOT_HIDDEN_IN_FAVORITES", False)


if __name__ == "__main__":
    main()
