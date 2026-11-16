import argparse
import json
from pathlib import Path


def counts(path, session):
    result = dict(history=0, snapshot=0)
    for line in Path(path).read_text().splitlines():
        row = json.loads(line)
        if row["session"] == session and row["kind"] in result:
            result[row["kind"]] += 1
    return result


def compare(before, after, mode):
    for kind in ("history", "snapshot"):
        if before[kind] <= 0:
            raise ValueError(f"Missing positive {kind} control")
        if mode == "hidden" and before[kind] != after[kind]:
            raise ValueError(f"HIDDEN_READER_IO: {kind} increased from {before[kind]} to {after[kind]}; route it through ChatPollGate")
        if mode == "resumed" and after[kind] <= before[kind]:
            raise ValueError(f"Reader did not resume {kind} work")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["count", "hidden", "resumed"])
    parser.add_argument("before")
    parser.add_argument("after_or_session")
    args = parser.parse_args()
    if args.mode == "count":
        print(json.dumps(counts(args.before, args.after_or_session)))
    else:
        before = json.loads(Path(args.before).read_text())
        after = json.loads(Path(args.after_or_session).read_text())
        compare(before, after, args.mode)
        print(f"READER_{args.mode.upper()}_OK: {before} -> {after}")


if __name__ == "__main__":
    main()
