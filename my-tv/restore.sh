#!/usr/bin/env bash
# Undo everything done to this TV, in one command: the debloat list (tools/restore.sh, Google TV's launcher back as
# Home), the setup wizard and Google TV launcher that were turned off by hand before the list ran (so the list's
# record has them as already off), the ambient screensaver, and the ad-blocking Private DNS.
# The launcher and the add-ons stay installed; uninstall them from Settings > Apps if you want them gone.
#
# Usage: TV_HOST=<tv ip> my-tv/restore.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -n "${TV_HOST:-}" ] || { echo "Set TV_HOST to the TV's IP address." >&2; exit 2; }
T() { tools/venv/bin/python -I tools/tv.py "$1"; }

tools/restore.sh my-tv/debloat.txt com.google.android.apps.tv.launcherx
T 'pm enable --user 0 com.google.android.tungsten.setupwraith' >/dev/null
T 'pm enable --user 0 com.google.android.apps.tv.launcherx' >/dev/null
T 'cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME com.google.android.apps.tv.launcherx | tail -n 1)' >/dev/null
T 'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop' >/dev/null
# Private DNS was unset (automatic) before; deleting both keys puts that back.
T 'settings delete global private_dns_specifier; settings delete global private_dns_mode' >/dev/null
T 'input keyevent KEYCODE_HOME' >/dev/null
echo "Restored. Home: $(T 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1)"
