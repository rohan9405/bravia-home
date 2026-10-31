#!/usr/bin/env bash
# The API keys the Feed add-on needs live on this Mac, never in the repo, and are sent to the TV over ADB, so nothing
# is typed with the remote:
#   omdb  OMDb key, for IMDb ratings (through the StreamRatings shared cache)
#   tmdb  TMDB API Read Access Token, for the header's new releases and the top 10 of apps that publish none
#   news  TheNewsAPI token (free at thenewsapi.com), for the Ambient News add-on's stories over the screensaver
#
# Usage: my-tv/api-keys.sh save omdb|tmdb|news        # paste it once (hidden); it is checked, then kept
#        TV_HOST=<tv ip> my-tv/api-keys.sh send       # give every kept key to the add-on that uses it, on the TV
#        my-tv/api-keys.sh send --emulator            # the same for the bravia_preview emulator
#        my-tv/api-keys.sh forget omdb|tmdb|news      # delete one from this Mac
#
# Files: ~/.cleantv-private/omdb_key, tmdb_token and news_token (folder 700, files 600). The script never prints a
# key. The TV keeps its own copies inside each add-on's private storage, since the TV makes the lookups.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=${CLEANTV_PRIVATE:-$HOME/.cleantv-private}
ACTIVITY=io.github.rohan9405.feed/.FeedActivity
NEWS_ACTIVITY=io.github.rohan9405.ambient/.SettingsActivity

file_for() {
  case "$1" in
    omdb) echo "$DIR/omdb_key" ;;
    tmdb) echo "$DIR/tmdb_token" ;;
    news) echo "$DIR/news_token" ;;
    *) echo "Which key: omdb, tmdb or news" >&2; exit 2 ;;
  esac
}

check() {
  local out
  if [ "$1" = omdb ]; then
    out=$(curl -sS --max-time 10 --get "https://www.omdbapi.com/" --data-urlencode "apikey=$2" --data-urlencode "t=Panchayat" 2>/dev/null || true)
    case "$out" in *'"Response":"True"'*) return 0 ;; *'Invalid API key'*) echo "OMDb says this key is not valid." >&2; return 1 ;; esac
  elif [ "$1" = news ]; then
    out=$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' --get "https://api.thenewsapi.com/v1/news/top" --data-urlencode "api_token=$2" --data-urlencode "locale=in" --data-urlencode "limit=1" 2>/dev/null || true)
    case "$out" in 200) return 0 ;; 401|403) echo "TheNewsAPI says this token is not valid." >&2; return 1 ;; esac
  else
    out=$(curl -sS --max-time 15 --retry 2 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $2" "https://api.tmdb.org/3/configuration" 2>/dev/null || true)
    case "$out" in 200) return 0 ;; 401) echo "TMDB says this token is not valid (use the API Read Access Token)." >&2; return 1 ;; esac
  fi
  echo "Could not reach the service to check it; saving it anyway." >&2
}

case "${1:-}" in
  save)
    F=$(file_for "${2:-}")
    printf 'Paste the %s key (input hidden): ' "$2" >&2
    read -rs KEY
    echo >&2
    KEY=$(printf '%s' "$KEY" | tr -d '[:space:]')
    [ -n "$KEY" ] || { echo "Nothing pasted." >&2; exit 2; }
    check "$2" "$KEY" || exit 1
    mkdir -p "$DIR"
    chmod 700 "$DIR"
    ( umask 077; printf '%s\n' "$KEY" > "$F" )
    echo "Saved to $F. Next: TV_HOST=<tv ip> my-tv/api-keys.sh send" >&2
    ;;
  send)
    EXTRAS=""
    [ -s "$DIR/omdb_key" ] && EXTRAS="$EXTRAS --es omdb_key $(tr -d '[:space:]' < "$DIR/omdb_key")"
    [ -s "$DIR/tmdb_token" ] && EXTRAS="$EXTRAS --es tmdb_token $(tr -d '[:space:]' < "$DIR/tmdb_token")"
    CMDS=()
    [ -n "$EXTRAS" ] && CMDS+=("am start -n $ACTIVITY$EXTRAS")
    [ -s "$DIR/news_token" ] && CMDS+=("am start -n $NEWS_ACTIVITY --es news_token $(tr -d '[:space:]' < "$DIR/news_token")")
    [ ${#CMDS[@]} -gt 0 ] || { echo "No keys saved yet: run my-tv/api-keys.sh save omdb|tmdb|news first." >&2; exit 1; }
    for CMD in "${CMDS[@]}"; do
      if [ "${2:-}" = "--emulator" ]; then
        ADB=${ANDROID_HOME:-$HOME/.cleantv-toolchain/sdk}/platform-tools/adb
        "$ADB" -s "${ADB_SERIAL:-emulator-5554}" shell "$CMD" >/dev/null
      else
        [ -n "${TV_HOST:-}" ] || { echo "Set TV_HOST to the TV's IP address, or pass --emulator." >&2; exit 2; }
        tools/venv/bin/python -I tools/tv.py "$CMD" >/dev/null
      fi
    done
    echo "Keys sent; the add-ons are refreshing." >&2
    ;;
  forget)
    F=$(file_for "${2:-}")
    rm -f "$F"
    echo "Deleted $F. The TV keeps its copy until the Feed add-on gets another." >&2
    ;;
  *)
    echo "Usage: my-tv/api-keys.sh save omdb|tmdb|news | send [--emulator] | forget omdb|tmdb|news" >&2
    exit 2
    ;;
esac
