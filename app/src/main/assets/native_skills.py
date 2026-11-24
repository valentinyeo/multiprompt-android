import json
import os
from functools import lru_cache
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

try:
    import pwd
except ImportError:
    pwd = None

ACCOUNT_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_-]{0,31}\Z")
ACCOUNT_HOMES = PurePosixPath("/home")
LAUNCHER = ("import sys;s=sys.stdin.buffer.read().decode();"
            'exec(compile(s,"<native-skills>","exec"),'
            '{"__name__":"__main__","NATIVE_SKILLS_SOURCE":s,"NATIVE_SKILLS_OWNER":True})')


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


def loose(value):
    if isinstance(value, Path):
        return value
    if not isinstance(value, str) or not value.strip():
        return None
    path = Path(value.strip()).expanduser()
    return path if path.is_absolute() else None


def usable(value):
    path = loose(value)
    try:
        return path if path is not None and path.is_dir() else None
    except OSError:
        return None


def owner_account(folder, home=None, lookup=None):
    lookup = lookup if lookup is not None else (pwd.getpwnam if pwd is not None else None)
    if lookup is None or folder is None:
        return None
    parts = PurePosixPath(str(folder)).parts
    if len(parts) < 3 or parts[:2] != ("/", "home") or ".." in parts:
        return None
    if not ACCOUNT_NAME.fullmatch(parts[2]):
        return None
    try:
        account = lookup(parts[2])
    except KeyError:
        return None
    folder = PurePosixPath(str(account.pw_dir))
    if account.pw_uid == 0 or folder != ACCOUNT_HOMES / parts[2]:
        return None
    if home is not None and folder == PurePosixPath(str(home)):
        return None
    return account.pw_name


def own_source():
    source = globals().get("NATIVE_SKILLS_SOURCE")
    if isinstance(source, str) and source:
        return source
    try:
        return Path(__file__).read_text(encoding="utf-8")
    except (OSError, NameError, TypeError):
        return None


def owner_skills(account, session, requested):
    source = own_source()
    if source is None:
        return None
    try:
        result = subprocess.run(
            ["sudo", "-n", "-H", "-u", account, "--", "python3", "-c", LAUNCHER, session, requested],
            input=source, capture_output=True, text=True, encoding="utf-8", timeout=8, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    try:
        rows = json.loads(result.stdout)
    except ValueError:
        return None
    return result.stdout if isinstance(rows, list) else None


@lru_cache(maxsize=None)
def tmux_folder(session):
    try:
        output = subprocess.check_output(
            ["tmux", "display-message", "-p", "-t", "=" + session + ":", "#{pane_current_path}"],
            text=True, stderr=subprocess.DEVNULL, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    return loose(output)


@lru_cache(maxsize=None)
def hosted_folder(session):
    reader = Path.home() / ".multiprompt-runtime/reader.py"
    try:
        output = subprocess.check_output(
            ["python3", str(reader), "host", "list"], text=True, stderr=subprocess.DEVNULL, timeout=15)
        chats = json.loads(output).get("chats")
    except (OSError, subprocess.SubprocessError, ValueError, AttributeError):
        return None
    for chat in chats if isinstance(chats, list) else []:
        if isinstance(chat, dict) and chat.get("name") == session:
            return loose(chat.get("cwd"))
    return None


def chat_folder(session, requested):
    return tmux_folder(session) or loose(requested) or hosted_folder(session)


def project_directory(session, requested):
    for folder in (tmux_folder(session), loose(requested), hosted_folder(session)):
        path = usable(folder)
        if path is not None:
            return path
    raise SystemExit("The session project directory is unavailable")


def owner_answer(session, requested, home=None):
    account = owner_account(chat_folder(session, requested), Path.home() if home is None else home)
    return owner_skills(account, session, requested) if account else None


def main():
    session = sys.argv[1]
    requested = sys.argv[2] if len(sys.argv) > 2 else ""
    if not globals().get("NATIVE_SKILLS_OWNER"):
        answer = owner_answer(session, requested)
        if answer is not None:
            sys.stdout.write(answer)
            return
    cwd = project_directory(session, requested)
    configured = None if globals().get("NATIVE_SKILLS_OWNER") else os.environ.get("CLAUDE_CONFIG_DIR")
    config = Path(configured).expanduser() if configured else None
    print(json.dumps(discover(Path.home(), cwd, config), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        raise SystemExit(f"{type(error).__name__}: {error}")
