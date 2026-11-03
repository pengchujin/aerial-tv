#!/usr/bin/env bash
# Try Aerial on a TV the maintainer does not have, and write down what happened, for a GitHub
# "机型适配 / Device support" issue. tools/report.sh says how the TV is set up; this says how Aerial
# behaves on it: Home from Aerial and from another app, and on request standby, a restart and
# each input. Each check prints PASS / FAIL / SKIP with what it saw, and the end has a JSON block.
#
#   TV=<tv-ip> tools/selftest.sh
#   curl -fsSL https://aerial.qust.me/tools/selftest.sh | TV=<tv-ip> bash
#
# Optional, each asks nothing and only runs when set:
#   STANDBY=1  put the TV in standby for 70 s and wake it (Xiaomi turns ADB off in standby; the
#              script then asks for the remote's power key and waits for ADB to come back)
#   REBOOT=1   restart the TV and follow what is in front for the first minute (ask the owner first)
#   INPUTS=1   open each of Aerial's inputs as its tile does, take a screenshot of each into
#              aerial-selftest-<model>/, and come back to Aerial in between
#
# What it changes: it presses Home, opens the TV's settings, opens inputs, and with the options
# above sleeps or restarts the TV. Nothing is installed, removed or written to settings. Read
# site/adapt.md for what to do with a failed check.
set -uo pipefail

: "${TV:?set TV to the TV IP address, e.g. TV=<tv-ip> $0}"
command -v adb >/dev/null || { echo "adb not found. macOS: brew install android-platform-tools; Linux: install android-tools / adb." >&2; exit 1; }
# The timings and timeouts below are perl's: without it every wait ended at once, and a Home that
# worked was reported FAIL (issue #1208, the reporter's agent).
command -v perl >/dev/null || { echo "perl not found; this script times each check with it. Linux: install perl; Windows: run it in Git Bash, which has perl." >&2; exit 1; }
PKG=app.orchard.tv
case "$TV" in *:*) SERIAL="$TV" ;; *) SERIAL="$TV:5555" ;; esac
export ANDROID_SERIAL="$SERIAL"

# A TV whose network sleeps keeps `adb connect` waiting for a minute or more; 8 s is plenty awake.
connect() {
  perl -e 'alarm shift; exec @ARGV' 8 adb connect "$SERIAL" </dev/null >/dev/null 2>&1
  [ "$(perl -e 'alarm shift; exec @ARGV' 5 adb get-state </dev/null 2>/dev/null)" = device ]
}
connect || { echo "Could not connect to $SERIAL. Check that ADB debugging is on, the IP is right, and the TV's \"Allow USB debugging?\" was answered with Allow." >&2; exit 1; }

# </dev/null: adb forwards stdin, which under `curl | bash` is the rest of this script.
sh_() { adb shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }
now() { perl -MTime::HiRes=time -e 'printf "%.1f", time'; }
config() { sh_ am broadcast -a app.orchard.tv.CONFIG -n "$PKG/.guard.ConfigReceiver" "$@" | sed -n 's/.*data="\(.*\)"$/\1/p'; }

# The package of the window with focus: mCurrentFocus, else the focused app (Android 5-9 differ).
fg() {
  local d w
  d=$(sh_ dumpsys window)
  w=$(printf '%s\n' "$d" | grep -m1 "mCurrentFocus=" | grep -oE ' [A-Za-z0-9_.]+/' | head -n1 | tr -d ' /')
  [ -n "$w" ] || w=$(printf '%s\n' "$d" | grep -m1 "mFocusedApp=" | grep -oE ' [A-Za-z0-9_.]+/' | head -n1 | tr -d ' /')
  printf '%s' "$w"
}

# After a PASS: whether Aerial is still in front $1 seconds later. Some firmwares stop third-party
# apps on Home, Aerial too, a second after the guard brought it back (Dangbei H3S, issue #849).
stays() { sleep "$1"; [ "$(fg)" = "$PKG" ]; }

# Polls the foreground for up to $1 seconds; prints "<seconds> <package>" for the first time Aerial
# is in front, or "- <package>" when it never is.
wait_aerial() {
  local start end p
  start=$(now); end=$(perl -e "print $start + $1")
  while :; do
    p=$(fg)
    if [ "$p" = "$PKG" ]; then perl -e "printf '%.1f', $(now) - $start"; echo " $p"; return 0; fi
    perl -e "exit($(now) < $end ? 0 : 1)" || { echo "- $p"; return 1; }
    sleep 0.5
  done
}

MODEL=$(sh_ getprop ro.product.model)
VERSION=$(sh_ dumpsys package "$PKG" | grep -m1 versionName | sed 's/.*versionName=//')
[ -n "$VERSION" ] || { echo "Aerial ($PKG) is not installed on this TV." >&2; exit 1; }
SLUG=$(printf '%s' "${MODEL:-tv}" | tr -c 'A-Za-z0-9._-' '_')
OUT="aerial-selftest-$SLUG.md"
RESULTS=()
result() { # name, PASS|FAIL|SKIP, detail
  printf '%-18s %-4s %s\n' "$1" "$2" "$3"
  RESULTS+=("$(printf '"%s":{"result":"%s","detail":"%s"}' "$1" "$(printf '%s' "$2" | tr 'A-Z' 'a-z')" "$(printf '%s' "$3" | sed 's/["\\]/ /g')")")
}

{
echo "### Aerial self-test"
echo
echo "$MODEL, Android $(sh_ getprop ro.build.version.release), Aerial $VERSION, $(date '+%Y-%m-%d')"
echo
echo '```'

# 1. Home Guard bound: everything below depends on it where Aerial is not the default home.
ACC=$(sh_ settings get secure enabled_accessibility_services)
BOUND=$(sh_ dumpsys accessibility | grep -c -E "label=(Aerial|$PKG)")
# Android 6 and older have neither `cmd` nor `pm resolve-activity`; their "not found" and usage
# text are not a Home (issue #818).
HOME_TO=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT | grep -E '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$' | tail -n1)
[ -n "$HOME_TO" ] || HOME_TO=$(sh_ pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT | grep -E '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$' | tail -n1)
[ -n "$HOME_TO" ] || HOME_TO="unknown (no cmd on this Android)"
if [ "${HOME_TO%%/*}" = "$PKG" ]; then result guard PASS "Aerial is the default home ($HOME_TO)"
elif [ "${BOUND:-0}" -gt 0 ]; then result guard PASS "Home Guard bound; default home is $HOME_TO"
elif printf '%s' "$ACC" | grep -q "$PKG/"; then result guard FAIL "Home Guard enabled but not bound; default home is $HOME_TO"
else result guard FAIL "Home Guard not enabled ($ACC); default home is $HOME_TO"
fi
# What Aerial needs to switch Home Guard on itself; an APK installed without install.sh has neither
# (issue #853), and Android 13+ restricts one installed from a file (issues #811 #829).
if [ "${HOME_TO%%/*}" != "$PKG" ] && [ "${BOUND:-0}" -eq 0 ]; then
  sh_ dumpsys package "$PKG" | grep -q "WRITE_SECURE_SETTINGS: granted=true" ||
    echo "  (WRITE_SECURE_SETTINGS is not granted: run install.sh repair, or the pm grant line of the install steps)"
  sh_ appops get "$PKG" ACCESS_RESTRICTED_SETTINGS | grep -q -E "deny|ignore" &&
    echo "  (ACCESS_RESTRICTED_SETTINGS is denied: adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow)"
fi

# 2. Home on Aerial itself.
sh_ am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 2
sh_ input keyevent KEYCODE_HOME
read -r secs pkg <<<"$(wait_aerial 8)"
if [ "$secs" = "-" ]; then result home_on_aerial FAIL "in front after 8 s: $pkg"
elif stays 4; then result home_on_aerial PASS "Aerial in front"
else result home_on_aerial FAIL "Aerial in front, then $(fg) 4 s later"
fi
# Home Guard's own answer to the key can come a second or two after Aerial is already in front,
# and it would cover the next app (TCL MT5879).
sleep 3

# 3. Home from another app: the TV's settings, which every TV has.
sh_ am start -a android.settings.SETTINGS >/dev/null; sleep 3
OTHER=$(fg)
# Some TVs' settings are a panel over the current app rather than an activity of their own: an
# app from the launcher list instead, neither Aerial nor the TV's own launcher.
if [ "$OTHER" = "$PKG" ]; then
  APP=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER |
    grep "/" | grep -v -e "^ *$PKG/" -e "^ *${HOME_TO%%/*}/" | head -n1 | tr -d ' ')
  [ -n "$APP" ] && { sh_ am start -n "$APP" >/dev/null; sleep 4; OTHER=$(fg); }
fi
sh_ input keyevent KEYCODE_HOME
read -r secs pkg <<<"$(wait_aerial 12)"
if [ "$OTHER" = "$PKG" ]; then result home_from_app SKIP "neither the settings nor another app came to front"
elif [ "$secs" != "-" ] && stays 4; then result home_from_app PASS "from $OTHER, Aerial in front after ${secs} s"
elif [ "$secs" != "-" ]; then result home_from_app FAIL "from $OTHER, Aerial in front after ${secs} s, then $(fg) 4 s later"
else result home_from_app FAIL "from $OTHER, in front after 12 s: $pkg"
fi
sleep 3
# input keyevent does not go through Home Guard's key filter; on some TVs (Sony, Android 9) the
# remote's Home key behaves otherwise. A FAIL here is worth a try with the remote. Android also holds
# back app starts for 5 s after a Home key it handles itself, so about 5 s here is that, not the
# guard (TCL MT5879: the guard asks at once, Aerial starts 5 s later; the remote's key is not held).

# 4. Standby and wake.
if [ "${STANDBY:-0}" = 1 ]; then
  sh_ input keyevent KEYCODE_POWER; sleep 5
  WAKE=$(sh_ dumpsys power | grep -m1 -E "mWakefulness=" | sed 's/.*=//')
  echo "(standby: $WAKE; waiting 70 s)" >&2
  sleep 70
  if connect; then sh_ input keyevent KEYCODE_POWER; else
    # Xiaomi switches ADB off in standby; TCL MT5879's network sleeps.
    echo ">>> ADB does not answer in standby. Turn the TV on with the remote's power key; waiting up to 3 minutes." >&2
    for _ in $(seq 1 18); do connect && break; sleep 2; done
  fi
  if connect; then
    sleep 3
    read -r secs pkg <<<"$(wait_aerial 60)"
    [ "$secs" != "-" ] && result standby PASS "asleep=$WAKE, Aerial in front ${secs} s after wake" || result standby FAIL "asleep=$WAKE, in front 60 s after wake: $pkg"
  else result standby FAIL "ADB did not come back after wake; on Xiaomi, install with KEEP_ADB=1"
  fi
else result standby SKIP "STANDBY=1 to try"
fi

# 5. Restart: what is in front, second by second, for the first minute after boot completes.
if [ "${REBOOT:-0}" = 1 ]; then
  adb reboot </dev/null >/dev/null 2>&1
  echo "(restarting; waiting up to 4 minutes for ADB)" >&2
  sleep 20
  for _ in $(seq 1 18); do connect && [ "$(sh_ getprop sys.boot_completed)" = 1 ] && break; sleep 2; done
  if [ "$(sh_ getprop sys.boot_completed)" = 1 ]; then
    TIMELINE="" LAST="" START=$(now)
    for _ in $(seq 1 60); do
      p=$(fg); t=$(perl -e "printf '%.0f', $(now) - $START")
      [ "$p" != "$LAST" ] && TIMELINE="$TIMELINE ${t}s:${p:-none}" && LAST="$p"
      sleep 1
    done
    [ "$LAST" = "$PKG" ] && result reboot PASS "after boot:$TIMELINE" || result reboot FAIL "after boot:$TIMELINE"
  else result reboot FAIL "ADB did not come back within 4 minutes"
  fi
else result reboot SKIP "REBOOT=1 to try (ask the owner first)"
fi

# 6. Inputs, as Aerial lists them, with the TV's profile.
SOURCES=$(config --ez list_sources true)
if [ -z "$SOURCES" ]; then result inputs SKIP "Aerial older than 1.59 lists no inputs here"
elif [ "$SOURCES" = "[]" ]; then result inputs SKIP "Aerial finds no inputs on this TV"
else
  N=$(printf '%s' "$SOURCES" | grep -o '"id"' | wc -l | tr -d ' ')
  if [ "${INPUTS:-0}" = 1 ]; then
    DIR="aerial-selftest-$SLUG"; mkdir -p "$DIR"
    i=0
    printf '%s' "$SOURCES" | perl -MJSON::PP -e 'binmode STDOUT, ":utf8"; my $a = decode_json(join "", <STDIN>); print "$_->{id}\t$_->{label}\t", ($_->{shown} ? 1 : 0), "\n" for @$a' |
    while IFS="$(printf '\t')" read -r id label shown; do
      [ "$shown" = 1 ] || continue
      i=$((i + 1))
      opened=$(config --es open_source "$id")
      sleep 7
      adb exec-out screencap -p </dev/null > "$DIR/input-$i.png" 2>/dev/null
      echo "  input $i: $label ($id) -> ${opened#open_source: }; in front $(fg); screenshot $DIR/input-$i.png"
      sh_ am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
    done
    result inputs PASS "$N listed; each opened, see the screenshots and tell which port each showed"
  else result inputs SKIP "$N listed; INPUTS=1 to open each"
  fi
fi
echo '```'

echo
echo "**Inputs as Aerial lists them**"
echo '```json'
echo "${SOURCES:-[]}"
echo '```'
echo
echo "**Self-test result**"
echo '```json'
printf '{"model":"%s","aerial":"%s","checks":{%s}}\n' "$MODEL" "$VERSION" "$(IFS=,; echo "${RESULTS[*]}")"
echo '```'
} 2>&1 | tee "$OUT"

# Back on Aerial, as the TV was before.
sh_ am start -n "$PKG/.MainActivity" >/dev/null
echo >&2
echo "Saved to $OUT. Paste it with tools/report.sh's output into https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml" >&2
