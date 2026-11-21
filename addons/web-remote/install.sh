#!/usr/bin/env bash
# Build Web Remote, install it on the TV, turn on its accessibility service and its server.
#   TV_HOST=<tv ip> addons/web-remote/install.sh
# The service is added to the TV's accessibility list without dropping the services already there.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=tv.webremote
SERVICE="$PKG/.RemoteService"
tv() { "$PY" -I ../../tools/tv.py "$@"; }

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/webremote.apk
OUT=$(tv 'pm install -r /data/local/tmp/webremote.apk; rm -f /data/local/tmp/webremote.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$SERVICE*) ;; null|'') settings put secure enabled_accessibility_services $SERVICE ;; *) settings put secure enabled_accessibility_services \"\$s:$SERVICE\" ;; esac; settings put secure accessibility_enabled 1; echo accessibility-service-enabled"
tv "am start -n $PKG/.MainActivity --ez server true >/dev/null; echo opened"
echo "Installed. Open Web Remote on the TV, press 'Pair a new device', then open the address it shows in a browser."
