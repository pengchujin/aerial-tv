#!/usr/bin/env bash
# Drive Aerial's Home from a computer without pressing keys on the remote: read what is on screen,
# change a setting, open a screen, focus a tile. The answer is printed on its own line.
#
#   TV=<tv-ip> tools/agent.sh state                       # the overlay, the focused tile, the tiles, every setting
#   TV=<tv-ip> tools/agent.sh settings                    # each setting with its kind, options or range, and value
#   TV=<tv-ip> tools/agent.sh set large_clock true
#   TV=<tv-ip> tools/agent.sh open control_center         # none | control_center | personalize | hidden_apps
#   TV=<tv-ip> tools/agent.sh focus com.example.app       # a tile's key, from state
#
# See docs/AGENT.md. Without TV, the one adb device that is connected is used.
set -euo pipefail
ADB=${ADB:-adb}
[ -n "${TV:-}" ] && { "$ADB" connect "$TV:5555" >/dev/null 2>&1 || true; ADB="$ADB -s $TV:5555"; }
cmd=${1:-help}
args=(--es agent "$cmd")
case "$cmd" in
  set)         [ $# -eq 3 ] || { echo "usage: agent.sh set <key> <value>" >&2; exit 2; }; args+=(--es key "$2" --es value "$3") ;;
  open|focus)  [ $# -eq 2 ] || { echo "usage: agent.sh $cmd <target>" >&2; exit 2; }; args+=(--es target "$2") ;;
esac
out=$($ADB shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver "${args[@]}")
# am prints: Broadcast completed: result=0, data="<answer>"
printf '%s\n' "$out" | sed -n 's/^.*data="\(.*\)"$/\1/p'
