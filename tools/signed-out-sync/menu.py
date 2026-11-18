#!/usr/bin/env python3
import json
import sys
import xml.etree.ElementTree as ET


def check(root):
    shown = [node.get("text") or "" for node in root.iter("node")]
    if "Sign in to sync" not in shown:
        raise SystemExit("Sign in to sync is missing")
    if "Sync: signed in — sign out" in shown:
        raise SystemExit("signed-in row is showing")
    return {"row": "Sign in to sync", "signedIn": False}


def main():
    json.dump(check(ET.parse(sys.argv[1]).getroot()), sys.stdout, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
