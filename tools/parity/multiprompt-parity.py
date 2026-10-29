#!/usr/bin/env python3
import argparse
import fcntl
import html
import json
from pathlib import Path
import re
import subprocess
from datetime import datetime

ROOT = Path(__file__).resolve().parents[2]
LABELS = {"desktop": "Multiprompt", "android": "Multiprompt Android"}
MARKER = re.compile(r"\bMP-PAIR:(IGSH-\d+):(desktop|android)\b")
STATES = {"shipped", "partial", "missing", "planned"}


def call(*args):
    result = subprocess.run([str(Path.home() / "projects/ceo/bin/ceo-ht"), *args, "--json"],
                            check=True, capture_output=True, text=True, timeout=120)
    data = json.loads(result.stdout)
    if data.get("success") is not True:
        raise ValueError("Hypertask did not confirm success")
    return data


def board(api=call):
    rows, cursors, seen = [], set(), set()
    cursor = None
    while True:
        args = ["task", "list", "--project", "2060", "--limit", "100"]
        if cursor:
            args += ["--cursor", cursor]
        page = api(*args)
        if not isinstance(page.get("tasks"), list) or not isinstance(page.get("total"), int):
            raise ValueError("Incomplete board response")
        for row in page["tasks"]:
            ticket = row["ticketNumber"]
            if ticket in seen:
                raise ValueError("Board changed during pagination; retry next run")
            seen.add(ticket)
            rows.append(row)
        cursor = page.get("nextCursor")
        if not cursor:
            if len(rows) != page["total"]:
                raise ValueError("Incomplete board snapshot")
            return rows
        if cursor in cursors or not page["tasks"]:
            raise ValueError("Invalid board cursor")
        cursors.add(cursor)


def timestamp(value):
    stamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if stamp.tzinfo is None:
        raise ValueError("Timestamp needs a timezone")
    return stamp


def validate(data):
    timestamp(data["track_since"])
    seen = set()
    for row in data["features"]:
        if row["id"] in seen or not re.fullmatch(r"[a-z][a-z0-9-]+", row["id"]):
            raise ValueError("Feature IDs must be unique")
        seen.add(row["id"])
        for platform in LABELS:
            cell = row[platform]
            if cell["status"] not in STATES or not cell["source"].startswith("https://github.com/valentinyeo/"):
                raise ValueError("Each status needs a source reference")
            if cell["status"] != "shipped" and not re.fullmatch(r"IGSH-\d+", cell.get("ticket", "")):
                raise ValueError("Every gap needs its own ticket")
    if not seen:
        raise ValueError("Empty inventory")
    return data


def render(data):
    lines = ["# Multiprompt desktop and Android", "", "> can you keep Android and desktop in sync somehow", "",
             "One list for both apps. Layout differences allowed.", "",
             "Source audit, not a claim of device testing.", "Partial means shipped, with a reported gap.", "",
             f"Reviewed: {data['reviewed']}", "", "| Feature | Desktop | Android |", "|---|---|---|"]
    for row in data["features"]:
        cells = []
        for platform in LABELS:
            cell = row[platform]
            text = f"[{cell['status']}]({cell['source']})"
            if cell.get("ticket"):
                number = cell["ticket"].split("-")[1]
                text += f" · [gap](https://app.hypertask.ai/detail/project-2060/{number})"
            cells.append(text)
        lines.append(f"| {row['name']} | {' | '.join(cells)} |")
    lines += ["", "## Automatic counterparts", "",
              "Daily checks pair new single-app requests.", "Bugs included: no feature requests get missed.",
              "The counterpart checks the same user outcome.", "Desktop-only controls need an explicit phone equivalent.",
              "Both-app requests already cover both apps.", "Existing gap tickets are reused, never copied.",
              "Generated counterparts never create more counterparts.", "",
              f"New-request coverage starts {data['track_since']}.",
              "Historical gaps remain linked in this list.", "",
              "Agent operation: `python3 tools/parity/multiprompt-parity.py --apply`.",
              "Without `--apply`, only planned pairs are printed.",
              "The daily user timer is `multiprompt-parity.timer`.",
              "Board writes use `bin/ceo-ht` as Hax Runner 14.",
              "Update this inventory when gap tickets ship.", ""]
    return "\n".join(lines)


def pairs(data, rows):
    by_ticket = {row["ticketNumber"]: row for row in rows}
    linked = {cell["ticket"] for row in data["features"] for cell in (row["desktop"], row["android"]) if cell.get("ticket")}
    for ticket in linked:
        if ticket not in by_ticket:
            raise ValueError(f"Gap ticket missing from board: {ticket}")
    existing = set()
    for row in rows:
        existing.update(MARKER.findall(html.unescape(row.get("description") or "")))
    result = []
    for row in rows:
        description = html.unescape(row.get("description") or "")
        if MARKER.search(description) or row["ticketNumber"] in linked or row.get("status") in ("Archive", "Deleted"):
            continue
        if (timestamp(row["createdAt"]) < timestamp(data["track_since"])
                and row["ticketNumber"] not in data.get("catch_up_tickets", [])):
            continue
        labels = {item["name"] if isinstance(item, dict) else item for item in row.get("labels", [])}
        platforms = {key for key, label in LABELS.items() if label in labels}
        title = row["title"].lower()
        if platforms == {"desktop"} and ("android" in title or "Mobile App" in labels):
            platforms = {"android"}
        if len(platforms) != 1:
            continue
        target = "android" if "desktop" in platforms else "desktop"
        if (row["ticketNumber"], target) not in existing:
            result.append((row, target))
    return sorted(result, key=lambda pair: pair[0]["ticketNumber"])


def reconcile(data, rows, apply=False, api=call):
    planned = pairs(data, rows)
    created = []
    for source, target in planned:
        ticket = source["ticketNumber"]
        marker = f"MP-PAIR:{ticket}:{target}"
        name = "Android" if target == "android" else "desktop"
        url = f"https://app.hypertask.ai/detail/project-2060/{ticket.split('-')[1]}"
        description = (f"<h2>Problem</h2><p>The other app has a matching request.</p>"
                       f"<p><a href='{url}'>Original app request</a></p>"
                       f"<h2>Fix</h2><p>Match that outcome on {name}.</p>"
                       "<p>Check existing support before changing anything.</p>"
                       "<h2>Done when</h2><p>Both apps support the same outcome.</p>"
                       "<p>Show proof, or explain any platform difference.</p>"
                       "<h2>Risk</h2><p>Unneeded changes. Reuse anything already working.</p>"
                       f"<h2>Decision</h2><p>None.</p><p>{marker}</p>")
        if apply:
            response = api("task", "create", "--project", "2060", "--section", "Todo",
                           "--labels", LABELS[target], "--title", f"Match the linked request on {name}",
                           "--description", description)
            tasks = response.get("tasks") or ([response["task"]] if response.get("task") else [])
            if not tasks or not re.fullmatch(r"IGSH-\d+", tasks[0].get("ticketNumber", "")):
                raise ValueError("Created counterpart lacks a ticket number; read board before retrying")
            created.append({"source": ticket, "target": target, "ticket": tasks[0]["ticketNumber"]})
        else:
            created.append({"source": ticket, "target": target})
    return created


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--render", action="store_true")
    args = parser.parse_args()
    data = validate(json.loads((ROOT / "tools/parity/multiprompt-features.json").read_text()))
    if args.render:
        print(render(data), end="")
        return
    state = Path.home() / ".local/state/multiprompt-parity"
    state.mkdir(parents=True, exist_ok=True)
    with (state / "run.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        result = reconcile(data, board(), args.apply)
        receipt = {"apply": args.apply, "pairs": result}
        if args.apply:
            (state / "last-run.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))


if __name__ == "__main__":
    main()
