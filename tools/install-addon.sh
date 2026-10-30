#!/usr/bin/env bash
# Build an add-on (addons/<name>) and install it on the TV, keeping its data.
#
# Usage: TV_HOST=192.168.1.50 tools/install-addon.sh tv-feed
#        SKIP_BUILD=1 ...            install the APK already built (tools/docker-build.sh addons/<name>)
#
# Needs JDK 17 and the Android SDK (JAVA_HOME, ANDROID_HOME) unless SKIP_BUILD=1, and tools/venv (see the README).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME=${1:-}
[ -n "$NAME" ] && [ -d "$ROOT/addons/$NAME" ] || { echo "Usage: TV_HOST=<tv ip> tools/install-addon.sh <$(ls "$ROOT/addons" | tr '\n' '|' | sed 's/|$//')>" >&2; exit 2; }
[ -n "${TV_HOST:-}" ] || { echo "Set TV_HOST to the TV's IP address." >&2; exit 2; }
PY="$ROOT/tools/venv/bin/python"
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
APK="$ROOT/addons/$NAME/app/build/outputs/apk/release/app-release.apk"
if [ "${SKIP_BUILD:-}" != 1 ]; then
  (cd "$ROOT/addons/$NAME" && ./gradlew --no-daemon -q testReleaseUnitTest assembleRelease)
fi
[ -f "$APK" ] || { echo "No APK at $APK: build it first." >&2; exit 1; }
"$PY" -I "$ROOT/tools/tv.py" --push "$APK" /data/local/tmp/addon.apk
"$PY" -I "$ROOT/tools/tv.py" 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
