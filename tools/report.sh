#!/usr/bin/env bash
# Collect what a maintainer needs to adapt Aerial to a TV they do not have, as text to paste into
# a GitHub issue (the "机型适配 / Device support" form).
#
#   TV=<tv-ip> tools/report.sh
#   curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<tv-ip> bash
#
# Writes aerial-report-<model>.md in the current folder and prints it (on macOS also copies it).
# Reads only: nothing on the TV is changed, except that Aerial is started if it was not running,
# for its own part of the report. Nothing personal is collected: no accounts, serial numbers,
# Wi-Fi names, addresses or app lists; addresses in log lines are masked. Read the file before
# posting it.
set -euo pipefail

: "${TV:?set TV to the TV IP address, e.g. TV=<tv-ip> $0}"
command -v adb >/dev/null || { echo "adb not found. macOS: brew install android-platform-tools; Linux: install android-tools / adb." >&2; exit 1; }
PKG=app.orchard.tv
case "$TV" in *:*) SERIAL="$TV" ;; *) SERIAL="$TV:5555" ;; esac
export ANDROID_SERIAL="$SERIAL"
adb connect "$SERIAL" >/dev/null
STATE=$(adb get-state 2>&1 || true)
if [ "$STATE" != device ]; then
  case "$STATE" in
    *unauthorized*) echo "The TV is asking \"Allow USB debugging?\". Choose Allow (and Always allow) on the TV, then run again." ;;
    # Android 11+ TVs with only wireless debugging use a TLS port of their own and refuse an
    # unpaired computer's certificate (TCL 85T7L Ultra, issue #480).
    *) echo "Could not connect to $SERIAL. Check that ADB debugging is on and the IP is right. A TV that offers only wireless debugging (Android 11+) needs pairing first: adb pair <IP>:<pairing port> with the code the TV shows, then run again with TV=<IP>:<port the TV shows>." ;;
  esac >&2
  exit 1
fi

sh_() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
# Addresses, MACs and the phone page's token out of anything that is not a build property.
mask() {
  sed -E \
    -e 's#:8790/[A-Za-z0-9]+#:8790/<token>#g' \
    -e 's#(^|[^0-9A-Za-z.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9A-Za-z.]|$)#\1<ip>\3#g' \
    -e 's#([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}#<mac>#g'
}

MODEL=$(sh_ getprop ro.product.model)
OUT="aerial-report-$(printf '%s' "${MODEL:-tv}" | tr -c 'A-Za-z0-9._-' '_').md"
HOME_INTENT=(-a android.intent.action.MAIN -c android.intent.category.HOME)

# From here a command the TV lacks or a grep that finds nothing is part of the report, not the end
# of it: `cmd role` (before Android 10, and XGIMI's Android 11) and an empty tv_input stopped the
# script halfway (issues #311, #454, #551).
set +e
{
  echo "### adb report"
  echo
  echo '```'
  for key in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.product.name \
             ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.display.id \
             ro.build.version.incremental ro.build.fingerprint ro.board.platform ro.hardware ro.product.cpu.abi \
             ro.config.low_ram ro.build.characteristics ro.sf.lcd_density persist.vt.use_home_pkg; do
    v=$(sh_ getprop "$key")
    [ -n "$v" ] && echo "$key = $v"
  done
  echo "wm size: $(sh_ wm size | tail -n1)  density: $(sh_ wm density | tail -n1)"
  echo '```'

  echo
  echo "**Aerial package**"
  echo '```'
  sh_ dumpsys package "$PKG" | grep -E "versionName|versionCode|installerPackageName|lastUpdateTime|enabled=" | sed 's/^ *//' | sort -u
  echo "appops:"
  sh_ appops get "$PKG" | sed 's/^/  /'
  echo '```'

  echo
  echo "**Home**"
  echo '```'
  echo "resolves to: $(sh_ cmd package resolve-activity --brief "${HOME_INTENT[@]}" | tail -n1)"
  sh_ cmd package query-activities --brief "${HOME_INTENT[@]}" | sed 's/^/  /'
  sh_ cmd role get-role-holders android.app.role.HOME | sed 's/^/role holder: /'
  echo "foreground: $(sh_ dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity|mFocusedActivity" | sed 's/^ *//')"
  echo '```'

  echo
  echo "**Settings**"
  echo '```'
  for kv in "secure enabled_accessibility_services" "secure accessibility_enabled" "secure enabled_notification_listeners" \
            "secure screensaver_enabled" "secure screensaver_components" "secure screensaver_default_component" \
            "secure install_non_market_apps" "global adb_enabled" "global picture_backlight" \
            "system screen_brightness" "system screen_brightness_mode" "system sound_effects_enabled" \
            "system boot_app_mode" "system boot_app_componentname"; do
    set -- $kv
    echo "$1 $2 = $(sh_ settings get "$1" "$2")"
  done
  echo '```'

  echo
  echo "**Bound services**"
  echo '```'
  echo "accessibility (bound):"
  sh_ dumpsys accessibility | grep -E "label=|Bound services|Enabled services" | sed -E 's/^ *//' | cut -c1-300 | head -20 | mask
  echo "notification listener: $(sh_ dumpsys notification | grep -c "$PKG/$PKG.guard.RevivalListener") mentions"
  echo '```'

  echo
  echo "<details><summary>TV inputs</summary>"
  echo
  echo '```'
  # Which HDMI port each hardware id is; Aerial's part below lists the inputs themselves.
  sh_ dumpsys tv_input | grep -E "^ *TvInputHardwareInfo" | sed -E 's/^ *//' | sort -u | head -30 | mask
  echo '```'
  echo
  echo "</details>"

  echo
  echo "<details><summary>logcat (Aerial)</summary>"
  echo
  echo '```'
  # Aerial's own tags, and the system services' lines about Aerial (kills, bindings, installs).
  # Only these tags: other apps log all sorts about a package (Xiaomi's store logs the app list).
  sh_ logcat -d -v time -t 8000 \
    | grep -E " [VDIWEF]/(Aerial(Guard|Diag|Updates|Stream|Wallpaper|KonkaSources)|AndroidRuntime)\(| [VDIWEF]/(ActivityManager|ActivityTaskManager|NotificationListeners|AccessibilityManagerService|PackageManager|PackageInstaller)\(.*$PKG" \
    | cut -c1-400 | tail -n 250 | mask
  echo '```'
  echo
  echo "</details>"
  echo

  # Aerial's own part (Diagnostics.kt): what it sees from inside the app.
  if ! sh_ pidof "$PKG" >/dev/null; then
    sh_ am start -n "$PKG/.MainActivity" >/dev/null
    sleep 4
  fi
  APP=$(sh_ dumpsys activity "$PKG/.MainActivity" report | sed -n '/^### Aerial diagnostics/,$p')
  if [ -n "$APP" ]; then echo "$APP"; else echo "(Aerial's own report is missing: Aerial is older than 1.32 or is not running.)"; fi
} > "$OUT"

cat "$OUT"
echo
command -v pbcopy >/dev/null && pbcopy < "$OUT" && echo "Copied to the clipboard." >&2
echo "Saved to $OUT. Read it, then paste it into https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml" >&2
