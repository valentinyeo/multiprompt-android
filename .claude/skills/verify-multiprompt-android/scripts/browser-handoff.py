import hashlib
import re
import sys
from urllib.parse import parse_qs, urlsplit

resolved, package = sys.argv[1:]
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
if not intent:
    sys.exit(1)
url = urlsplit(intent.group(1))
params = parse_qs(url.query, keep_blank_values=True, strict_parsing=True)
if url.scheme != 'https' or url.netloc != 'account.multiprompt.dev' or url.path != '/login':
    sys.exit(1)
if params.get('redirect_uri') != [package + '://auth/callback'] or params.get('code_challenge_method') != ['S256']:
    sys.exit(1)
for name in ('state', 'code_challenge'):
    if len(params.get(name, [])) != 1 or not re.fullmatch(r'[A-Za-z0-9_-]{43}', params[name][0]):
        sys.exit(1)
print('resolved_foreground=' + resolved)
print('authorization_uri=https://account.multiprompt.dev/login')
print('redirect_uri=' + package + '://auth/callback')
print('pkce=S256')
print('state_sha256=' + hashlib.sha256(params['state'][0].encode()).hexdigest())
