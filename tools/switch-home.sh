#!/usr/bin/env bash
# Switch which launcher answers the remote's Home button, without touching the debloat list.
#
# Usage: TV_HOST=192.168.1.50 tools/switch-home.sh stock    # the TV's own home screen becomes Home
#        TV_HOST=192.168.1.50 tools/switch-home.sh clean    # CleanYourTV becomes Home again
#        STOCK_LAUNCHER=<package> ...                       # another stock launcher than Google TV's
#
# "stock" enables the stock launcher if it was disabled and makes it Home; CleanYourTV stays installed. "clean"
# makes CleanYourTV Home and leaves the stock launcher enabled, so the Original Home card can still open it.
# To undo the whole clean-up, debloat included, use tools/restore.sh. Exit status: 0 done, 1 the TV did not
# take it, 2 bad command line.
set -euo pipefail
cd "$(dirname "$0")"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -n "${TV_HOST:-}" ] || { echo "Set TV_HOST to the TV's IP address." >&2; exit 2; }
STOCK=${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}
CLEAN=com.gathin.tvhome

case "${1:-}" in
  stock) TARGET=$STOCK ;;
  clean) TARGET=$CLEAN ;;
  *) echo "Usage: TV_HOST=<tv ip> tools/switch-home.sh stock|clean" >&2; exit 2 ;;
esac
[ $# -eq 1 ] || { echo "One argument: stock or clean." >&2; exit 2; }

if [ "$TARGET" = "$STOCK" ]; then
  "$PY" -I tv.py "pm enable --user 0 $STOCK" >/dev/null
fi
# set-home-activity needs pkg/activity on Android 9, so resolve the component on the TV first.
"$PY" -I tv.py "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $TARGET | tail -n 1)" >/dev/null
"$PY" -I tv.py 'input keyevent KEYCODE_HOME' >/dev/null
sleep 2
NOW=$("$PY" -I tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1 | tr -d '\r')
case "$NOW" in
  "$TARGET"/*) echo "Home is now $NOW" ;;
  *) echo "The TV still answers Home with $NOW; open Settings > Apps > Default apps > Home to pick it." >&2; exit 1 ;;
esac
