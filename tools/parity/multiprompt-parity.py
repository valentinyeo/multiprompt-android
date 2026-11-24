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
PROJECT = "13734"
PROJECTS = {"IGSH": "2060", "MUBO": PROJECT}
CLI = "hypertask"
TOKEN = Path(".config/hypertask-agents/credentials/sessions/multiprompt-android-dev-agent-token")
LABELS = {"desktop": "Multiprompt", "android": "Multiprompt Android"}
TICKET = r"(?:IGSH|MUBO)-\d+"
MARKER = re.compile(rf"\bMP-PAIR:({TICKET}):(desktop|android)\b")
GAP_MARKER = re.compile(r"\bMP-GAP:[a-z][a-z0-9-]+:(desktop|android)\b")
STATES = {"shipped", "partial", "missing", "planned"}
CLOSED_SECTIONS = {"done", "not now"}
MAX_CREATE = 5


class CapExceeded(ValueError):
    def __init__(self, cap, plan):
        super().__init__(f"Plan creates {len(plan)} tickets, above the cap of {cap}")
        self.cap = cap
        self.plan = plan


def call(*args):
    token = (Path.home() / TOKEN).read_text().strip()
    result = subprocess.run([CLI, "--json", "--token", token, *args],
                            capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        detail = result.stderr.strip().replace(token, "<token>")
        raise ValueError(detail or f"Hypertask exited {result.returncode}")
    data = json.loads(result.stdout)
    if data.get("success") is not True:
        raise ValueError("Hypertask did not confirm success")
    return data


def board(api=call, status="Normal"):
    rows, cursors, seen = [], set(), set()
    cursor = None
    while True:
        args = ["task", "list", "--project", PROJECT, "--limit", "100", "--status", status]
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


def ticket_url(ticket):
    key, number = ticket.split("-")
    return f"https://app.hypertask.ai/detail/project-{PROJECTS[key]}/{number}"


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
            if cell.get("ticket") and not re.fullmatch(TICKET, cell["ticket"]):
                raise ValueError("Invalid gap ticket reference")
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
                text += f" · [gap]({ticket_url(cell['ticket'])})"
            cells.append(text)
        lines.append(f"| {row['name']} | {' | '.join(cells)} |")
    lines += ["", "## Automatic counterparts", "",
              "Daily checks pair new single-app requests.", "Product fixes included: shared behavior stays matched.",
              "Automatic crash reports stay on their own app.",
              "The counterpart checks the same user outcome.", "Desktop-only controls need an explicit phone equivalent.",
              "Both-app requests already cover both apps.", "Existing gap tickets are reused, never copied.",
              "Unlinked inventory gaps get their own requests.",
              "Generated counterparts never create more counterparts.",
              "Archived counterparts still count as already paired.", "",
              f"New-request coverage starts {data['track_since']}.",
              "Historical gaps remain linked in this list.", "",
              "Agent operation: `python3 tools/parity/multiprompt-parity.py --apply`.",
              "Without `--apply`, only planned pairs are printed.",
              "Tickets linked on Hypertask stay paired, in either direction and any link type.",
              "Done and Not now tickets never count as pairs or as sources.",
              "A run refuses to create more than five tickets unless `--max-create` raises that cap.",
              "The daily user timer is `multiprompt-parity.timer`.",
              "Board writes use the `hypertask` CLI as the Multiprompt Android agent.",
              "Update this inventory when gap tickets ship.", ""]
    return "\n".join(lines)


def label_names(row):
    return {item["name"] if isinstance(item, dict) else item for item in row.get("labels", [])}


def platforms(row):
    names = label_names(row)
    return {key for key, label in LABELS.items() if label in names}


def closed(row):
    return row.get("sectionIsDone") is True or (row.get("section") or "").strip().lower() in CLOSED_SECTIONS


def links(ticket, api=call):
    data = api("task", "relations", ticket)
    rows = data.get("relations")
    if not isinstance(rows, list):
        raise ValueError("Incomplete relations response")
    found = []
    for row in rows:
        other = (row.get("otherTask") or {}).get("ticketNumber")
        if not isinstance(other, str) or not re.fullmatch(TICKET, other):
            continue
        found.append(other)
    return found


def ticket_platform(ticket):
    return "desktop" if ticket.split("-")[0] == "IGSH" else "android"


def ticket_index(rows):
    index = {}
    for row in rows:
        found = platforms(row)
        if len(found) == 1:
            index[row["ticketNumber"]] = {"platform": found.pop(), "closed": closed(row)}
    return index


def paired(ticket, platform, index, api=call):
    for other in links(ticket, api):
        known = index.get(other)
        if known is None:
            if ticket_platform(other) != platform:
                return True
        elif not known["closed"] and known["platform"] != platform:
            return True
    return False


def pairs(data, rows, api=call):
    in_inventory = {cell["ticket"] for row in data["features"]
                    for cell in (row["desktop"], row["android"]) if cell.get("ticket")}
    existing = set()
    for row in rows:
        if not closed(row):
            existing.update(MARKER.findall(html.unescape(row.get("description") or "")))
    candidates = []
    for row in rows:
        description = html.unescape(row.get("description") or "")
        if (closed(row) or MARKER.search(description) or GAP_MARKER.search(description)
                or row["ticketNumber"] in in_inventory or row.get("status") in ("Archive", "Deleted")):
            continue
        if (timestamp(row["createdAt"]) < timestamp(data["track_since"])
                and row["ticketNumber"] not in data.get("catch_up_tickets", [])):
            continue
        found = platforms(row)
        title = row["title"].lower()
        if re.search(r"\bstopped working: report \d+\b", title):
            continue
        if found == {"desktop"} and ("android" in title or "Mobile App" in label_names(row)):
            found = {"android"}
        if len(found) != 1:
            continue
        candidates.append((row, found.pop()))
    index = ticket_index(rows)
    result = []
    for row, platform in candidates:
        target = "android" if platform == "desktop" else "desktop"
        if (row["ticketNumber"], target) in existing:
            continue
        if paired(row["ticketNumber"], platform, index, api):
            continue
        result.append((row, target))
    return sorted(result, key=lambda pair: pair[0]["ticketNumber"])


def reconcile(data, rows, apply=False, api=call, max_create=MAX_CREATE):
    planned = pairs(data, rows, api)
    descriptions = "\n".join(html.unescape(row.get("description") or "") for row in rows if not closed(row))
    for feature in data["features"]:
        for platform in LABELS:
            cell = feature[platform]
            marker = f"MP-GAP:{feature['id']}:{platform}"
            if cell["status"] != "shipped" and not cell.get("ticket") and marker not in descriptions:
                planned.append(({"ticketNumber": "feature:" + feature["id"], "feature": feature["id"],
                                 "name": feature["name"]}, platform))
    if len(planned) > max_create:
        raise CapExceeded(max_create, [{"source": row["ticketNumber"], "target": target}
                                       for row, target in planned])
    created = []
    for source, target in planned:
        ticket = source["ticketNumber"]
        marker = f"MP-GAP:{source['feature']}:{target}" if source.get("feature") else f"MP-PAIR:{ticket}:{target}"
        name = "Android" if target == "android" else "desktop"
        url = ("https://github.com/valentinyeo/multiprompt-android/blob/main/docs/multiprompt-features.md"
               if source.get("feature") else ticket_url(ticket))
        feature_name = f"<p>{html.escape(source['name'])}</p>" if source.get("feature") else ""
        description = (f"<h2>Problem</h2><p>The other app has a matching request.</p>{feature_name}"
                       f"<p><a href='{url}'>Original app request</a></p>"
                       f"<h2>Fix</h2><p>Match that outcome on {name}.</p>"
                       "<p>Check existing support before changing anything.</p>"
                       "<h2>Done when</h2><p>Both apps support the same outcome.</p>"
                       "<p>Show proof, or explain any platform difference.</p>"
                       "<h2>Risk</h2><p>Unneeded changes. Reuse anything already working.</p>"
                       f"<h2>Decision</h2><p>None.</p><p>{marker}</p>")
        if apply:
            response = api("task", "create", "--project", PROJECT, "--section", "Todo",
                           "--labels", LABELS[target], "--title",
                           f"{name}: {source['name']}" if source.get("feature") else f"Match the linked request on {name}",
                           "--description", description)
            tasks = response.get("tasks") or ([response["task"]] if response.get("task") else [])
            if not tasks or not re.fullmatch(r"MUBO-\d+", tasks[0].get("ticketNumber", "")):
                raise ValueError("Created counterpart lacks a board ticket number; read board before retrying")
            twin = tasks[0]["ticketNumber"]
            if not source.get("feature"):
                api("task", "link", ticket, twin, "--type", "RelatedTo")
            created.append({"source": ticket, "target": target, "ticket": twin})
        else:
            created.append({"source": ticket, "target": target})
    return created


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--max-create", type=int, default=MAX_CREATE)
    args = parser.parse_args()
    if args.max_create < 0:
        parser.error("--max-create needs zero or more")
    data = validate(json.loads((ROOT / "tools/parity/multiprompt-features.json").read_text()))
    if args.render:
        print(render(data), end="")
        return
    state = Path.home() / ".local/state/multiprompt-parity"
    state.mkdir(parents=True, exist_ok=True)
    with (state / "run.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        rows = board() + board(status="Archive")
        if len({row["ticketNumber"] for row in rows}) != len(rows):
            raise ValueError("Board changed between active and archived reads; retry next run")
        try:
            result = reconcile(data, rows, args.apply, max_create=args.max_create)
        except CapExceeded as error:
            print(json.dumps({"apply": args.apply, "max_create": error.cap,
                              "refused": str(error), "pairs": error.plan}))
            raise SystemExit(2)
        receipt = {"apply": args.apply, "pairs": result}
        if args.apply:
            (state / "last-run.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))


if __name__ == "__main__":
    main()
