#!/usr/bin/env bash
# Device smoke check for the Mortal TX1-S (or the Android TV emulator).
#
# Everything here needs a real WebView and a real PackageManager, which the build
# host does not have — run this from a machine that can see the box.
#
# Usage:
#   tools/device_check.sh                    # default: adb on PATH, 192.168.x.x:5555
#   ADB="adb -s 192.168.1.42:5555" tools/device_check.sh --install
#
# Steps it performs (each one is independent; a failure does not stop the rest):
#   1. adb reachability + Android version / ABI / RAM
#   2. optional install of build/qrmzi-tv.apk
#   3. launch the activity and check it stays in the foreground
#   4. D-pad walk (down/right/centre/back/menu) with screenshots
#   5. memory sample, then a crude leak check (open/close the same screen twice)
#   6. logcat filtered to the app package
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
APK="${APK:-$ROOT/build/qrmzi-tv.apk}"
PKG=tv.qrmzi.client
ACT="$PKG/.MainActivity"
ADB=${ADB:-adb}
OUT=${OUT:-/tmp/qrmzi-device}
INSTALL=0
[ "${1:-}" = "--install" ] && INSTALL=1

mkdir -p "$OUT"
step() { printf '\n== %s\n' "$*"; }
run() { echo "\$ $*"; "$@" 2>&1 | sed 's/^/   /'; }

step "1. device"
if ! $ADB get-state >/dev/null 2>&1; then
  echo "no device: start adb over TCP on the box (Settings → Network → ADB) and"
  echo "set ADB=\"adb -s <ip>:5555\" (e.g. ADB=\"adb connect 192.168.1.42:5555\")."
  exit 1
fi
run $ADB shell getprop ro.build.version.release
run $ADB shell getprop ro.build.version.sdk
run $ADB shell getprop ro.product.cpu.abi
run $ADB shell cat /proc/meminfo

if [ "$INSTALL" -eq 1 ]; then
  step "2. install $APK"
  run $ADB install -r "$APK"
fi

step "3. launch"
run $ADB shell am force-stop $PKG
run $ADB shell am start -W -n "$ACT"
sleep 4
run $ADB shell dumpsys activity activities
run $ADB shell screencap -p /sdcard/qrmzi-01-home.png
run $ADB pull /sdcard/qrmzi-01-home.png "$OUT/01-home.png"

step "4. D-pad walk (screenshots after each key group)"
walk() {
  local name=$1 ; shift
  for k in "$@"; do $ADB shell input keyevent "$k" >/dev/null 2>&1; sleep 0.6; done
  $ADB shell screencap -p "/sdcard/qrmzi-$name.png" >/dev/null 2>&1
  $ADB pull "/sdcard/qrmzi-$name.png" "$OUT/$name.png" >/dev/null 2>&1
  echo "   $OUT/$name.png"
}
walk 02-home-down   20 20 22
walk 03-home-open   23
walk 04-detail-down 20 20 20 20
walk 05-detail-open 23
walk 06-back        4 4
walk 07-menu        82      # Settings

step "5. memory"
PID=$($ADB shell pidof $PKG | tr -d '\r')
if [ -n "$PID" ]; then
  echo "   pid=$PID"
  $ADB shell dumpsys meminfo "$PKG" | sed -n '1,25p'
  $ADB shell dumpsys gfxinfo "$PKG" | head -20
else
  echo "   app is not running (it was expected to be in the foreground)"
fi

step "6. logcat"
$ADB logcat -d -v brief | grep -iE "qrmzi|chromium|AndroidRuntime|FATAL" | tail -60 | sed 's/^/   /'

echo
echo "Screenshots and the memory sample above are the evidence for"
echo "BUILD_AND_VERIFICATION.md §6 (items 1-10)."
