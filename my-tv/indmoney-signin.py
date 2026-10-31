#!/usr/bin/env python3
"""Sign in to INDmoney's official MCP server once, read-only, for the TV's insight chip.

Registers a client the first time (scopes portfolio:read market:read, a loopback return address), opens INDmoney's
own sign-in page in the browser, and keeps the result in ~/.cleantv-private/indmoney-probe (folder 700, files 600).
Then my-tv/indmoney-pair.sh hands it to the TV, which renews it itself from then on. Prints no secrets.

Usage: python3 my-tv/indmoney-signin.py
"""
import base64, hashlib, http.server, json, os, secrets, subprocess, time, urllib.parse, urllib.request

DIR = os.path.join(os.environ.get('CLEANTV_PRIVATE', os.path.expanduser('~/.cleantv-private')), 'indmoney-probe')
BASE, RESOURCE = 'https://mcp.indmoney.com', 'https://mcp.indmoney.com/mcp'
REDIRECT = 'http://127.0.0.1:53682/callback'
UA = {'User-Agent': 'curl/8.7.1'}   # Cloudflare in front of INDmoney turns away Python's default agent


def post(url, data, content_type):
    req = urllib.request.Request(url, data=data, headers={**UA, 'Content-Type': content_type})
    return json.load(urllib.request.urlopen(req, timeout=20))


def save(name, obj):
    path = os.path.join(DIR, name)
    with open(path, 'w') as f:
        json.dump(obj, f)
    os.chmod(path, 0o600)


os.makedirs(DIR, exist_ok=True)
os.chmod(DIR, 0o700)
client_path = os.path.join(DIR, 'client.json')
if not os.path.exists(client_path):
    save('client.json', post(BASE + '/register', json.dumps({
        'client_name': 'BRAVIA Home', 'redirect_uris': [REDIRECT], 'grant_types': ['authorization_code', 'refresh_token'],
        'response_types': ['code'], 'token_endpoint_auth_method': 'client_secret_post',
        'scope': 'portfolio:read market:read'}).encode(), 'application/json'))
    print('Registered a read-only client.')
client = json.load(open(client_path))

verifier = secrets.token_urlsafe(64)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
state = secrets.token_urlsafe(16)
got = {}


class Callback(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        got.update({k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).items()})
        self.send_response(200)
        self.send_header('Content-Type', 'text/html')
        self.end_headers()
        self.wfile.write(b'<h2>Signed in. You can close this tab.</h2>')

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(('127.0.0.1', 53682), Callback)
server.timeout = 5
subprocess.run(['open', BASE + '/authorize?' + urllib.parse.urlencode({
    'response_type': 'code', 'client_id': client['client_id'], 'redirect_uri': REDIRECT, 'scope': 'portfolio:read market:read',
    'state': state, 'code_challenge': challenge, 'code_challenge_method': 'S256', 'resource': RESOURCE})])
print("INDmoney's sign-in page is open in your browser; waiting up to 10 minutes.")
end = time.time() + 600
while 'code' not in got and 'error' not in got and time.time() < end:
    server.handle_request()
if 'code' not in got or got.get('state') != state:
    raise SystemExit('No sign-in: ' + (got.get('error') or 'timed out'))
tokens = post(BASE + '/token', urllib.parse.urlencode({
    'grant_type': 'authorization_code', 'code': got['code'], 'redirect_uri': REDIRECT, 'client_id': client['client_id'],
    'client_secret': client['client_secret'], 'code_verifier': verifier, 'resource': RESOURCE}).encode(),
    'application/x-www-form-urlencoded')
tokens['obtained_at'] = int(time.time())
save('tokens.json', tokens)
print('Signed in (scope: %s). Next: TV_HOST=<tv ip> my-tv/indmoney-pair.sh' % tokens.get('scope'))
