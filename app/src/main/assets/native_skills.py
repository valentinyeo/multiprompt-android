import json
import os
from pathlib import Path
import re
import subprocess
import sys


def metadata(path, fallback, use_name):
    text = path.read_text(encoding="utf-8-sig")
    values = {}
    if text.startswith("---\n"):
        front = text.split("\n---", 1)[0].splitlines()[1:]
        active = None
        for line in front:
            if line[:1].isspace() and active == "description":
                values[active] = (values.get(active, "") + " " + line.strip()).strip()
                continue
            match = re.match(r"^(name|description|user-invocable):\s*(.*)$", line)
            active = match[1] if match else None
            if match:
                value = match[2].strip().strip("\"'")
                values[active] = "" if value in ("|", ">", "|-", ">-") else value
    if values.get("user-invocable", "").lower() == "false":
        return None
    name = (values.get("name") or fallback) if use_name else fallback
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./:-]*", name):
        return None
    return dict(name=name, description=" ".join(values.get("description", "").split()))


def scan(root, namespace=""):
    result = {}
    if not root.is_dir():
        return result
    is_skill = root.name == "skills"
    paths = sorted(root.glob("*/SKILL.md")) if is_skill else sorted(root.rglob("*.md"))
    for path in paths:
        fallback = path.parent.name if is_skill else str(path.relative_to(root).with_suffix("")).replace(os.sep, ":")
        try:
            row = metadata(path, fallback, is_skill)
        except (OSError, UnicodeError):
            continue
        if row is not None:
            row["name"] = namespace + row["name"]
            result[row["name"]] = row
    return result


def read_json(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def discover(home, cwd, config=None):
    config = config or home / ".claude"
    project_roots = [path / ".claude" for path in reversed([cwd, *cwd.parents]) if path != home]
    roots = [config, *project_roots]
    enabled = {}
    for root in roots:
        for name in ("settings.json", "settings.local.json"):
            flags = read_json(root / name).get("enabledPlugins", {})
            if isinstance(flags, dict):
                enabled.update(flags)
    installed = read_json(config / "plugins/installed_plugins.json").get("plugins", {})
    result = {}
    if isinstance(installed, dict):
        for name, entries in installed.items():
            if enabled.get(name) is not True:
                continue
            entries = entries if isinstance(entries, list) else [entries]
            eligible = [entry for entry in entries if isinstance(entry, dict) and
                        (entry.get("scope", "user") == "user" or
                         entry.get("scope") in ("project", "local") and
                         entry.get("projectPath") in [str(cwd), *map(str, cwd.parents)])]
            if not eligible or not isinstance(eligible[-1].get("installPath"), str):
                continue
            path = Path(eligible[-1]["installPath"])
            namespace = name.split("@", 1)[0] + ":"
            result.update(scan(path / "commands", namespace))
            result.update(scan(path / "skills", namespace))
    for root in roots:
        result.update(scan(root / "commands"))
        result.update(scan(root / "skills"))
    return sorted(result.values(), key=lambda row: row["name"].lower())


if __name__ == "__main__":
    session = sys.argv[1]
    target = "=" + session + ":"
    cwd = Path(subprocess.check_output(["tmux", "display-message", "-p", "-t", target, "#{pane_current_path}"], text=True).strip())
    if not cwd.is_absolute() or not cwd.is_dir():
        raise SystemExit("The session project directory is unavailable")
    config = Path(os.environ["CLAUDE_CONFIG_DIR"]).expanduser() if os.environ.get("CLAUDE_CONFIG_DIR") else None
    print(json.dumps(discover(Path.home(), cwd, config), ensure_ascii=False))
