#!/usr/bin/env bash
# restore.sh — put the TV back exactly as the backup found it.
#
#   ./restore.sh [backup_dir] [--keep-launcher] [--dry-run]
#
# 1. Undoes every line of <backup>/changes.log in reverse order
#    (re-enables disabled apps, restores changed settings, restores the original home screen).
# 2. Safety net: re-enables ANY package that was enabled at backup time but is disabled now.
# 3. Removes Gallery Home (unless --keep-launcher). Nothing else is ever uninstalled.
#
# Env: TV=<ip:5555> to pick a device when several are connected.
set -uo pipefail
cd "$(dirname "$0")" || exit 1
if [ -z "${ADB:-}" ]; then
  if [ -x ./platform-tools/adb ]; then ADB=./platform-tools/adb; else ADB=adb; fi
fi
TV=${TV:-}
BK=""; KEEP=0; DRY=0
for x in "$@"; do
  case $x in
    --keep-launcher) KEEP=1 ;;
    --dry-run) DRY=1 ;;
    *) BK=$x ;;
  esac
done
[ -n "$BK" ] || BK=$(ls -d tv-backup-* 2>/dev/null | sort | tail -1)
[ -d "$BK" ] || { echo "backup folder not found" >&2; exit 1; }
[ -n "$TV" ] || TV=$(cat .tv_target 2>/dev/null)          # latest known address
[ -n "$TV" ] || TV=$(head -1 "$BK/device.txt" 2>/dev/null)
if [[ $TV == *:* ]]; then "$ADB" connect "$TV" >/dev/null 2>&1; fi
a() { if [ -n "$TV" ]; then "$ADB" -s "$TV" "$@"; else "$ADB" "$@"; fi; }
run() { echo "+ adb shell $*"; [ $DRY = 1 ] || a shell "$@" </dev/null 2>&1 | tr -d '\r' | sed 's/^/    /'; }
rev() { awk '{l[NR]=$0} END{for(i=NR;i>0;i--) print l[i]}'; }

"$ADB" start-server >/dev/null 2>&1
if [[ $TV == *:* ]]; then "$ADB" connect "$TV" >/dev/null 2>&1; sleep 2; fi
a get-state >/dev/null 2>&1 || { echo "TV not connected: switch it on, then run ./tv.sh connect <tv-ip>" >&2; exit 1; }
echo "restoring from $BK  (device ${TV:-default})"

# 1) undo logged changes, newest first
if [ -f "$BK/changes.log" ]; then
  grep -v '^#' "$BK/changes.log" | rev | while read -r kind a1 a2 a3; do
    case $kind in
      DISABLE) run pm enable --user 0 "$a1" ;;
      SETTING) if [ "$a3" = "null" ] || [ -z "$a3" ]; then run settings delete "$a1" "$a2"
               else run settings put "$a1" "$a2" "$a3"; fi ;;
      HOME)    case $a1 in
                 */*ResolverActivity|""|No*) : ;;   # there was no fixed default; the chooser will return
                 */*) run pm enable --user 0 "${a1%%/*}"
                      [[ ${a2:-} =~ ^[a-zA-Z][a-zA-Z0-9_.]*$ ]] || a2=${a1%%/*}
                      run cmd role add-role-holder --user 0 android.app.role.HOME "$a2" 0
                      run cmd package set-home-activity --user 0 "$a1" ;;
               esac ;;
      INSTALL) : ;;   # handled in step 3
    esac
  done
fi

# 2) safety net: anything enabled at backup time must be enabled now
if [ -f "$BK/packages/enabled.txt" ]; then
  now_disabled=$(a shell pm list packages -d </dev/null | tr -d '\r' | sed 's/^package://')
  for p in $now_disabled; do
    grep -qx "$p" "$BK/packages/enabled.txt" && run pm enable --user 0 "$p"
  done
fi

# 3) remove our launcher
if [ $KEEP = 0 ] && a shell pm list packages | tr -d '\r' | grep -qx package:tv.galleryhome; then
  echo "+ adb uninstall tv.galleryhome"; [ $DRY = 1 ] || a uninstall tv.galleryhome
fi

# verify
if [ $DRY = 0 ]; then
  a shell pm list packages -d | tr -d '\r' | sed 's/^package://' | sort > /tmp/.tv_disabled_now.$$
  if diff -q "$BK/packages/disabled.txt" /tmp/.tv_disabled_now.$$ >/dev/null; then
    echo "OK: disabled-package list matches the backup exactly."
  else
    echo "Differences vs backup (< backup, > now):"; diff "$BK/packages/disabled.txt" /tmp/.tv_disabled_now.$$
  fi
  rm -f /tmp/.tv_disabled_now.$$
  echo "home screen now: $(a shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)"
  echo "(was: $(cat "$BK/home_activity.txt"))"
  a shell input keyevent KEYCODE_HOME
fi
echo "done."
