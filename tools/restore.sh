#!/usr/bin/env bash
# Give Home back to the TV's own launcher.
#
#   TV=<tv-ip> tools/restore.sh              # stop taking over Home, keep Aerial installed
#   TV=<tv-ip> tools/restore.sh --uninstall  # and remove Aerial (and Aerial Lite)
# If you disabled the stock launcher by hand, also: adb shell pm enable <its package>
set -euo pipefail
: "${TV:?set TV to the TV IP address}"
PKG=app.orchard.tv
GUARD="$PKG/$PKG.guard.HomeGuardService"
case "$TV" in *:*) SERIAL="$TV" ;; *) SERIAL="$TV:5555" ;; esac
export ANDROID_SERIAL="$SERIAL"
adb connect "$SERIAL" >/dev/null

# First take away what lets Aerial switch the guard back on, or it would simply do so.
adb shell am broadcast -a "$PKG.CONFIG" -n "$PKG/.guard.ConfigReceiver" --ez keep_adb false --ez own_screensaver false >/dev/null 2>&1 || true
adb shell cmd notification disallow_listener "$PKG/$PKG.guard.RevivalListener" >/dev/null 2>&1 || true
adb shell pm revoke "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW default >/dev/null 2>&1 || true
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true
adb shell appops set "$PKG" GET_USAGE_STATS default >/dev/null 2>&1 || true
adb shell appops set "$PKG" android:get_usage_stats default >/dev/null 2>&1 || true
adb shell appops set "$PKG" APP_AUTO_START default >/dev/null 2>&1 || true   # TCL only
adb shell appops set "$PKG" APP_ASSOC_START default >/dev/null 2>&1 || true
adb shell appops set "$PKG" AUTO_START default >/dev/null 2>&1 || true   # FFALCON T966D5's names
adb shell appops set "$PKG" ASSOC_START default >/dev/null 2>&1 || true
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS default >/dev/null 2>&1 || true   # Android 13+
adb shell am force-stop "$PKG"
case "$(adb shell settings get secure screensaver_components | tr -d '\r')" in
  "$PKG"/*) adb shell settings put secure screensaver_components "$(adb shell settings get secure screensaver_default_component | tr -d '\r')" ;;
esac

CURRENT=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
REST=$(printf '%s' "$CURRENT" | tr ':' '\n' | grep -v -x "$GUARD" | grep -v -x "$PKG/.guard.HomeGuardService" | paste -sd: - || true)
if [ -z "$REST" ] || [ "$REST" = null ]; then
  adb shell settings delete secure enabled_accessibility_services >/dev/null
else
  adb shell settings put secure enabled_accessibility_services "'$REST'"
fi
# Hisense VIDAA: put back the launcher install.sh replaced in persist.vt.use_home_pkg.
VT_HOME=$(adb shell settings get global aerial_vt_home_pkg | tr -d '\r')
if [ -n "$VT_HOME" ] && [ "$VT_HOME" != null ]; then
  adb shell setprop persist.vt.use_home_pkg "$VT_HOME"
  adb shell settings delete global aerial_vt_home_pkg >/dev/null
elif [ "$(adb shell getprop persist.vt.use_home_pkg | tr -d '\r')" = "$PKG" ] &&
     adb shell pm path com.jamdeo.tv.vod 2>/dev/null | grep -q package:; then
  # Set by hand before install.sh kept the old value (1.15 and earlier, issue #40): VIDAA's
  # launcher is 聚好看 on every VIDAA build reported so far.
  adb shell setprop persist.vt.use_home_pkg com.jamdeo.tv.vod
fi
adb shell cmd role remove-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
# Xiaomi where an older install made Aerial the default Home and the firmware took it: PatchWall then
# runs outside the home stack and the short press of the power key no longer sleeps the TV (#848).
# Home goes back to PatchWall; the fix takes a reboot.
if adb shell pm path com.mitv.tvhome 2>/dev/null | grep -q package: &&
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT 2>/dev/null | tail -n 1 | grep -q "^$PKG/"; then
  adb shell cmd package set-home-activity --user 0 com.mitv.tvhome/.MainActivityUserMode >/dev/null 2>&1 &&
    echo "Xiaomi: Home is PatchWall again; restart the TV once."
fi

# Aerial Lite (Android 4.x) takes over only as the home app the owner chose; nothing else to undo.
LITE=app.orchard.tv.lite
LITE_HERE=$(adb shell pm path "$LITE" 2>/dev/null | grep -c package: || true)
if [ "${1:-}" = --uninstall ]; then
  adb shell pm path "$PKG" 2>/dev/null | grep -q package: && adb uninstall "$PKG"
  [ "$LITE_HERE" = 0 ] || adb uninstall "$LITE"
elif [ "$LITE_HERE" != 0 ]; then
  echo "Aerial Lite stays the home screen while it is installed and chosen. Run with --uninstall, or on the TV: Settings > Apps > Aerial Lite > Clear defaults, then press Home and pick the TV's launcher."
fi
adb shell input keyevent KEYCODE_HOME
echo "Home now: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT | tail -n 1 | tr -d '\r')"
