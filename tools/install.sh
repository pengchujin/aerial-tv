#!/usr/bin/env bash
# Install Aerial on a TV over ADB and make it the home screen.
#
#   TV=<tv-ip> tools/install.sh               # standard build (declares HOME)
#   TV=<tv-ip> tools/install.sh guard         # for firmwares that refuse to install a launcher
#   KEEP_ADB=1 TV=<tv-ip> tools/install.sh    # also switch ADB back on whenever the TV turns it off
#   TV=<tv-ip> tools/install.sh repair        # Home went back to the TV's launcher: restart Home Guard
#
# The APK comes from the latest release (aerial.qust.me's copy, else GitHub), so only adb is needed
# (and python3 on TCL TVs).
#   BUILD=1        build it from this checkout instead (JDK 17 + Android SDK)
#   APK_FILE=<apk> install this file instead
#
# Takeover, gentlest first. Nothing is uninstalled or disabled:
#   1. Ask Android: HOME role / preferred home activity. Works on stock Android TV and Google TV.
#   2. If the firmware pins Home to its own launcher anyway (Xiaomi PatchWall, TCL, most China-market
#      firmware), Aerial Home Guard takes over: an accessibility service that answers the Home key
#      and steps in front of the stock launcher. Firmwares that force-stop third-party apps on standby
#      (Xiaomi) switch accessibility services off; a notification-listener binding brings Aerial back
#      (or, where Android refuses listeners, a sync adapter about 30 s later: guard/RevivalSync.kt)
#      and Aerial switches the guard on again, which is what WRITE_SECURE_SETTINGS is granted for.
#
# Firmwares that block every ADB install (TCL's package verifier) get it through TCL's own 多屏互动,
# with one confirmation on the TV; failing that, the APK is pushed to Download and installed through
# an app store's local install (Dangbei).
#
# tools/restore.sh undoes all of it.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${TV:?set TV to the TV IP address, e.g. TV=<tv-ip> $0}"
REPO=pengchujin/aerial-tv
command -v adb >/dev/null || { echo "adb not found. macOS: brew install android-platform-tools; Linux: install android-tools / adb." >&2; exit 1; }
FLAVOR="${1:-standard}"
case "$FLAVOR" in standard|guard|repair) ;; *) echo "flavor must be standard or guard (or repair)" >&2; exit 1 ;; esac
PKG=app.orchard.tv
GUARD="$PKG/$PKG.guard.HomeGuardService"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ "${BUILD:-0}" = 1 ]; then
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
  [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

case "$TV" in *:*) SERIAL="$TV" ;; *) SERIAL="$TV:5555" ;; esac
export ANDROID_SERIAL="$SERIAL"
adb connect "$SERIAL" >/dev/null
if [ "$(adb get-state 2>/dev/null)" != device ]; then
  echo "The TV did not accept the connection. Accept \"Allow USB debugging?\" on the TV, then run again." >&2
  exit 1
fi

# What counts is the service Android actually runs, not the settings entry: after an update, Xiaomi
# MiTV-MFTP0 (Android 9) kept the entry while system_server still pointed at the old APK directory
# and never bound it (issue #72).
# Some firmwares show the class name where the label goes (Xiaomi MiTV-ASTP0, Android 9, issue #183).
guard_bound() { adb shell dumpsys accessibility 2>/dev/null | grep -q -E "label=(Aerial|app\.orchard\.tv)"; }

wait_guard() {
  for _ in $(seq 1 "$1"); do sleep 1; guard_bound && return 0; done
  return 1
}

# Stopping Aerial makes system_server load it afresh, from the APK that is really installed; the
# guard is bound again when it starts (verified on MiTV-MFTP0, issue #72).
restart_aerial() {
  adb shell am force-stop "$PKG"
  sleep 3
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
}

if [ "$FLAVOR" = repair ]; then
  adb shell pm path "$PKG" >/dev/null 2>&1 || { echo "Aerial is not installed on this TV." >&2; exit 1; }
  restart_aerial
  if wait_guard 8; then echo "Home Guard is running again. Press Home on the remote."; exit 0; fi
  echo "Aerial is open, but Home Guard is not running. Run the install command again." >&2
  exit 1
fi

# Path of the APK for a flavor: APK_FILE, a local build, or the latest release.
build() {
  local flavor=$1
  if [ -n "${APK_FILE:-}" ] && [ "$flavor" = "$FLAVOR" ]; then
    echo "$APK_FILE"
  elif [ "${BUILD:-0}" = 1 ]; then
    local task="assemble$(tr '[:lower:]' '[:upper:]' <<< "${flavor:0:1}")${flavor:1}Release"
    ./gradlew -q --console=plain "$task"
    echo "app/build/outputs/apk/$flavor/release/app-$flavor-release.apk"
  else
    local out="${TMPDIR:-/tmp}/aerial-$flavor.apk" url
    for url in "https://aerial.qust.me/download/aerial-$flavor.apk" \
               "https://github.com/$REPO/releases/latest/download/aerial-$flavor.apk"; do
      echo "Downloading $url" >&2
      if curl -fL --progress-bar --connect-timeout 10 -o "$out" "$url"; then echo "$out"; return; fi
    done
    echo "Could not download aerial-$flavor.apk" >&2
    return 1
  fi
}

version_code() {   # versionCode of the APK on disk; empty without the Android SDK
  # Several build-tools versions may be installed; a bare glob would pass the others as arguments.
  local aapt2
  aapt2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
  [ -n "$aapt2" ] || return 0
  "$aapt2" dump badging "$1" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1
}

installed_code() {
  adb shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1
}

# Installed by now: the expected versionCode when known, else any change from before.
installed_now() {
  local now
  now=$(installed_code)
  if [ -n "$WANT" ]; then [ "$now" = "$WANT" ]; else [ -n "$now" ] && [ "$now" != "$BEFORE" ]; fi
}

# TCL's 多屏互动 (system app) installs APKs that TCL's phone app pushes to it; tools/tcast-push.py
# speaks that protocol. The TV downloads the APK from this computer through an ADB reverse tunnel,
# so nothing is served on the network. The viewer confirms once on the TV (同意).
tcast_install() {
  local apk=$1 want=$2 dir srv rc=0
  nc -z -G 3 "${SERIAL%%:*}" 6553 >/dev/null 2>&1 || return 1
  dir=$(mktemp -d)
  cp "$apk" "$dir/Aerial.apk"
  python3 -m http.server 8765 --bind 127.0.0.1 --directory "$dir" >/dev/null 2>&1 &
  srv=$!
  adb reverse tcp:8765 tcp:8765 >/dev/null
  echo "Pushing Aerial through 多屏互动. On the TV, choose 同意 (Agree)."
  python3 tools/tcast-push.py "${SERIAL%%:*}" http://127.0.0.1:8765/Aerial.apk "$PKG" Aerial "${want:-0}" 180 || rc=$?
  adb reverse --remove tcp:8765 >/dev/null 2>&1 || true
  { kill "$srv" && wait "$srv"; } 2>/dev/null || true
  rm -rf "$dir"
  return $rc
}

BEFORE=$(installed_code)
APK=$(build "$FLAVOR")
echo "Installing $APK"
if ! OUT=$(adb install -r "$APK" 2>&1); then
  echo "$OUT" >&2
  case "$OUT" in
    *VERIFICATION_FAILURE*)
      # TCL: ADB installs are refused outright, and so is any APK that declares a home screen and is
      # not signed by TCL. The guard build declares none.
      [ "$FLAVOR" = guard ] || APK=$(build guard)
      WANT=$(version_code "$APK")
      echo
      TCAST_OK=0
      if tcast_install "$APK" "$WANT"; then
        TCAST_OK=1
      else
        # No 多屏互动: put the APK in Download and install it on the TV itself.
        adb shell rm -f /sdcard/Download/Aerial.apk
        adb push "$APK" /sdcard/Download/Aerial.apk >/dev/null
        adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
        echo "This TV only installs apps from the TV itself. Aerial.apk is now in Download on the TV."
        if adb shell pm path com.dangbeimarket >/dev/null 2>&1; then
          adb shell am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity >/dev/null 2>&1 || true
          echo "Dangbei's package manager is open on the TV: choose Aerial, then Install (confirm on the TV)."
        else
          echo "Open it with a file manager or an app store's local install on the TV, and confirm."
        fi
        echo -n "Waiting for the install"
        for _ in $(seq 1 120); do
          installed_now && break
          echo -n "."; sleep 3
        done
        echo
      fi
      [ "$TCAST_OK" = 1 ] || installed_now || { echo "Aerial was not installed; nothing else was changed." >&2; exit 1; }
      FLAVOR=guard
      ;;
    *)
      [ "$FLAVOR" = standard ] && echo "This firmware may refuse apps that declare a home screen. Try: TV=$TV $0 guard" >&2
      exit 1
      ;;
  esac
fi

# Grants that only ADB can give; Aerial never asks for them on screen.
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true   # Wi-Fi name
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true  # keep Home Guard (and ADB) on
adb shell pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>/dev/null || true      # apps' programs on Home's shelf
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true             # open Aerial after boot
adb shell appops set "$PKG" WRITE_SETTINGS allow 2>/dev/null || true                  # brightness
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true        # update from Control Center
adb shell cmd notification allow_listener "$PKG/$PKG.guard.RevivalListener" 2>/dev/null || true  # come back after a kill
# Android 9 on Xiaomi MiTV-MFTP0 reports success but never binds it (issue #72): low-RAM devices
# get no notification listeners at all. Aerial's sync adapter brings it back there instead, later.
if ! adb shell dumpsys notification 2>/dev/null | grep -q "$PKG/$PKG.guard.RevivalListener"; then
  echo "Note: this TV refuses notification listeners. After the TV stops Aerial (Xiaomi does at standby)," >&2
  echo "Aerial comes back through Android's sync service about 30-60 seconds later, not at once: after a" >&2
  echo "short standby the TV's launcher can show for up to a minute. Keep auto-sync on. If Home ever stays" >&2
  echo "on the TV's launcher: TV=$TV $0 repair" >&2
fi
# Compose runs much smoother ahead-of-time compiled on TV chips.
adb shell cmd package compile -m speed -f "$PKG" >/dev/null 2>&1 || true
# TVs on Android's standard screensaver (TCL's carries a membership upsell) get Aerial's instead,
# so it also starts while another app sits idle. Xiaomi uses its own; Aerial covers it from Home.
if [ "$(adb shell settings get secure screensaver_enabled | tr -d '\r')" = 1 ]; then
  adb shell settings put secure screensaver_components "$PKG/$PKG.dream.AerialDream"
  # Some firmwares (TCL) put their own back on every boot; Aerial restores it when it starts.
  adb shell am broadcast -a "$PKG.CONFIG" -n "$PKG/.guard.ConfigReceiver" --ez own_screensaver true >/dev/null
  echo "Aerial's screensaver is now the TV's screensaver."
fi
# TCL's 自启动管理 (TclAppBootManager in system_server) refuses to start or bind third-party apps
# after a reboot unless these TCL-only app ops allow it; without them the TV boots to TCL's launcher.
# Other firmwares do not know the op names and refuse them, which is fine.
adb shell appops set "$PKG" APP_AUTO_START allow >/dev/null 2>&1 || true
adb shell appops set "$PKG" APP_ASSOC_START allow >/dev/null 2>&1 || true
if [ "${KEEP_ADB:-0}" = 1 ]; then
  adb shell am broadcast -a "$PKG.CONFIG" -n "$PKG/.guard.ConfigReceiver" --ez keep_adb true >/dev/null
  echo "Aerial will switch ADB debugging back on whenever the TV turns it off."
fi

if ! adb shell am start -W -n "$PKG/.MainActivity" | grep -q "Status: ok"; then
  echo "Aerial did not start; Home was left untouched." >&2
  exit 1
fi

resolve_home() {
  adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tail -n 1 | tr -d '\r'
}

if [ "$FLAVOR" = standard ]; then
  # Hisense VIDAA (MT9653, Android 11, #40) starts the launcher named in this property by explicit
  # component, past the HOME role and unseen by Home Guard. Only firmwares that have it; the old
  # value is kept on the TV for restore.sh.
  VT_HOME=$(adb shell getprop persist.vt.use_home_pkg | tr -d '\r')
  if [ -n "$VT_HOME" ] && [ "$VT_HOME" != "$PKG" ]; then
    adb shell settings put global aerial_vt_home_pkg "$VT_HOME"
    adb shell setprop persist.vt.use_home_pkg "$PKG"
    echo "Hisense: Home now opens Aerial instead of $VT_HOME."
  fi
  adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 \
    || adb shell cmd package set-home-activity --user 0 "$PKG/.MainActivity" >/dev/null 2>&1 || true
  HOME_NOW=$(resolve_home)
  case "$HOME_NOW" in
    "$PKG"/*)
      echo "Aerial is the home screen ($HOME_NOW)."
      exit 0 ;;
  esac
  echo "The firmware still sends Home to $HOME_NOW; turning on Aerial Home Guard."
fi

# Aerial switches the guard on itself when it starts (it has WRITE_SECURE_SETTINGS by now). Do it
# here as well, appending to the list so no other service is dropped.
CURRENT=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CURRENT" in
  *"$GUARD"*) ;;
  null|"") adb shell settings put secure enabled_accessibility_services "$GUARD" ;;
  *) adb shell settings put secure enabled_accessibility_services "'$CURRENT:$GUARD'" ;;
esac
adb shell settings put secure accessibility_enabled 1

if wait_guard 6; then
  echo "Home Guard is running. Press Home on the remote."
  exit 0
fi
echo "Home Guard is enabled but Android has not started it; restarting Aerial."
restart_aerial
if wait_guard 8; then
  echo "Home Guard is running. Press Home on the remote."
  exit 0
fi
echo "Home Guard is enabled but Android has not started it." >&2
echo "Some TCL / Hisense firmwares block sideloaded accessibility services: allow Aerial under the" >&2
echo "TV's security app (自启动 / 无障碍 / 权限管理), then run this script again." >&2
exit 1
