#!/usr/bin/env bash
# Hands an INDmoney sign-in made on this Mac to the Feed add-on on the TV, once. From then on the TV renews it itself
# (INDmoney swaps the refresh token at every renewal, so only one place can hold it): this Mac's copy is retired.
#
# Usage: TV_HOST=<tv ip> my-tv/indmoney-pair.sh
#
# Reads ~/.cleantv-private/indmoney-probe/client.json and tokens.json (made by my-tv/indmoney-signin.py: a read-only
# client, scopes portfolio:read market:read). Prints no secrets.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=${CLEANTV_PRIVATE:-$HOME/.cleantv-private}/indmoney-probe
[ -s "$DIR/client.json" ] && [ -s "$DIR/tokens.json" ] || { echo "No INDmoney sign-in in $DIR: run my-tv/indmoney-signin.py first." >&2; exit 1; }
[ -n "${TV_HOST:-}" ] || { echo "Set TV_HOST to the TV's IP address." >&2; exit 2; }
read -r ID SECRET REFRESH < <(python3 -c '
import json, sys
c = json.load(open(sys.argv[1])); t = json.load(open(sys.argv[2]))
print(c["client_id"], c["client_secret"], t["refresh_token"])' "$DIR/client.json" "$DIR/tokens.json")
tools/venv/bin/python -I tools/tv.py "am start -n io.github.rohan9405.feed/.FeedActivity --es indmoney_client_id $ID --es indmoney_client_secret $SECRET --es indmoney_refresh_token $REFRESH" >/dev/null
mv "$DIR/tokens.json" "$DIR/tokens.sent-to-tv.json"
echo "Sent. The TV holds the INDmoney sign-in now and renews it itself; this Mac's copy is retired." >&2
