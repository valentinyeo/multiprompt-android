import json
import re
import sys

resolved, package, expected = sys.argv[1:]
activity = sys.stdin.read()


def component(value):
    app, name = value.split('/', 1)
    return app, app + name if name.startswith('.') else name


match = re.search(r'topResumedActivity=ActivityRecord\{([^ ]+)\s+u\d+\s+([^ ]+)', activity)
if not match or component(match.group(2)) != component(resolved):
    sys.exit(1)
record = re.search(r'\* Hist\s+#\d+: ActivityRecord\{' + re.escape(match.group(1)) + r'\s+[^\n]*\n(.*?)(?=\n\s*\* Hist\s+#|\Z)', activity, re.S)
if not record:
    sys.exit(1)
block = record.group(1)
if not re.search(r'launchedFromPackage=' + re.escape(package) + r'(?:\s|$)', block):
    sys.exit(1)
intent = re.search(r'Intent \{ act=android\.intent\.action\.VIEW dat=([^\s}]+)', block)
if not intent or intent.group(1) != expected or not expected.startswith('https://'):
    sys.exit(1)
print(json.dumps(dict(browser=resolved, launched_from=package, action='android.intent.action.VIEW', url=expected)))
