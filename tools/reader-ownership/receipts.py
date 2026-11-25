import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def counts(path, session):
    result = dict(history=0, snapshot=0)
    for line in Path(path).read_text().splitlines():
        row = json.loads(line)
        if row["session"] == session and row["kind"] in result:
            result[row["kind"]] += 1
    return result


def compare(before, after, mode):
    if before["history"] <= 0 and before["snapshot"] <= 0:
        raise ValueError("Missing positive reader control")
    for kind in ("history", "snapshot"):
        if mode == "hidden" and before[kind] != after[kind]:
            raise ValueError(f"HIDDEN_READER_IO: {kind} increased from {before[kind]} to {after[kind]}; route it through ChatPollGate")
        if mode == "resumed" and before[kind] + after[kind] > 0 and after[kind] <= before[kind]:
            raise ValueError(f"Reader did not resume {kind} work")


def ui_result(path, expected):
    nodes = [node for node in ET.parse(path).iter("node") if expected in node.get("text", "") and node.get("package", "").startswith("dev.multiprompt.companion")]
    if len(nodes) != 1:
        raise ValueError(f"READER_UI_EVIDENCE: expected one rendered node containing {expected}, found {len(nodes)}")
    node = nodes[0]
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not bounds or int(bounds[3]) <= int(bounds[1]) or int(bounds[4]) <= int(bounds[2]):
        raise ValueError("READER_UI_EVIDENCE: rendered text has no visible bounds")
    return dict(source=Path(path).name, text=node.get("text"), bounds=node.get("bounds"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["count", "hidden", "resumed", "ui"])
    parser.add_argument("before")
    parser.add_argument("after_or_session")
    parser.add_argument("--append", type=Path)
    args = parser.parse_args()
    if args.mode == "ui":
        result = ui_result(args.before, args.after_or_session)
        if args.append:
            saved = json.loads(args.append.read_text()) if args.append.exists() else []
            saved.append(result)
            args.append.write_text(json.dumps(saved) + "\n")
        print(json.dumps(result))
    elif args.mode == "count":
        print(json.dumps(counts(args.before, args.after_or_session)))
    else:
        before = json.loads(Path(args.before).read_text())
        after = json.loads(Path(args.after_or_session).read_text())
        compare(before, after, args.mode)
        print(f"READER_{args.mode.upper()}_OK: {before} -> {after}")


if __name__ == "__main__":
    main()
