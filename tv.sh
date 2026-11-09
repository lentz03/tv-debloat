#!/usr/bin/env bash
# tv.sh — reversible Android TV debloat helper (ADB over Wi-Fi).
#
#   ./tv.sh setup                    one-step start: get adb, find the TV, connect, back up, screenshot
#   ./tv.sh find                     scan the local network for TVs with ADB (port 5555) open
#   ./tv.sh connect <ip>             adb connect (accept the prompt on the TV)
#   ./tv.sh backup                   full state backup into ./tv-backup-<date>/  (run this first!)
#   ./tv.sh shot <name>              save a TV screenshot into the backup's shots/ folder
#   ./tv.sh disable <pkg>...         pm disable-user (reversible), logged for restore
#   ./tv.sh enable <pkg>...          re-enable packages
#   ./tv.sh setting <ns> <key> <val> change a settings value, old value logged for restore
#   ./tv.sh install <apk>            install an APK, logged for restore
#   ./tv.sh home <component>         make <component> the default home screen, previous logged
#   ./tv.sh apply <plan.txt>         run a plan file step by step (with safety checks + screenshots)
#   ./tv.sh doctor                   explain why the TV can't be reached and how to fix it
#   ./tv.sh diag                     save Gallery Home logs + network checks to the backup's diag.txt
#   ./tv.sh restore                  undo everything in the backup's changes.log (see restore.sh)
#
# Env: TV=<ip:5555> (else the only connected device), BK=<backup dir> (else newest ./tv-backup-*)
# Nothing is ever uninstalled: apps are only disabled for user 0 and can be re-enabled any time.
set -uo pipefail

cd "$(dirname "$0")" || exit 1
HERE=$(pwd)
if [ -z "${ADB:-}" ]; then
  if [ -x "$HERE/platform-tools/adb" ]; then ADB="$HERE/platform-tools/adb"
  elif command -v adb >/dev/null; then ADB=adb
  else ADB="$HERE/platform-tools/adb"; fi
fi
TV=${TV:-$(cat .tv_target 2>/dev/null)}
die() { echo "error: $*" >&2; exit 1; }
a() { if [ -n "$TV" ]; then "$ADB" -s "$TV" "$@"; else "$ADB" "$@"; fi; }
sh_() { a shell "$@" </dev/null 2>&1 | tr -d '\r'; }
bkdir() {
  if [ -n "${BK:-}" ]; then echo "$BK"; return; fi
  ls -d tv-backup-* 2>/dev/null | sort | tail -1
}
need_bk() { local d; d=$(bkdir); [ -n "$d" ] && [ -d "$d" ] || die "no backup found — run './tv.sh backup' first"; echo "$d"; }
log() { echo "$*" >> "$(need_bk)/changes.log"; }

ensure_adb() {
  [ -x "$ADB" ] || command -v "$ADB" >/dev/null 2>&1 && return 0
  local os=linux; [ "$(uname)" = Darwin ] && os=darwin
  echo "adb not found — downloading Google's platform-tools for $os into $HERE/platform-tools …"
  curl -fL# -o platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" \
    || die "download failed; on a Mac you can also run: brew install --cask android-platform-tools"
  unzip -qo platform-tools.zip && rm -f platform-tools.zip
  [ "$os" = darwin ] && xattr -dr com.apple.quarantine platform-tools 2>/dev/null
  ADB="$HERE/platform-tools/adb"; "$ADB" version | head -1
}

# TCP probe of port 5555 with a hard 1-second limit (macOS nc needs -G for a connect timeout).
NC_G=0; nc -h 2>&1 | grep -q -- '-G' && NC_G=1
probe() {
  if [ $NC_G = 1 ]; then nc -z -G 1 "$1" 5555 >/dev/null 2>&1
  elif command -v timeout >/dev/null; then timeout 1 bash -c "echo > /dev/tcp/$1/5555" 2>/dev/null
  else
    ( bash -c "echo > /dev/tcp/$1/5555" 2>/dev/null & p=$!; sleep 1; kill $p 2>/dev/null; wait $p 2>/dev/null )
  fi
}

mask2prefix() { # 0xfffffc00 or 255.255.252.0 -> 22
  local m=$1 n=0 v
  if [[ $m == 0x* ]]; then v=$((16#${m#0x})); else
    IFS=. read -r o1 o2 o3 o4 <<< "$m"; v=$(( (o1<<24) | (o2<<16) | (o3<<8) | o4 )); fi
  while [ $v -gt 0 ]; do n=$(( n + (v & 1) )); v=$(( v >> 1 )); done
  echo $n
}

cmd_find() {
  local nets="" ipaddr pfx
  if command -v ip >/dev/null; then nets=$(ip -4 -o addr show scope global | awk '{print $4}')
  elif command -v ifconfig >/dev/null; then
    nets=$(ifconfig | awk '/inet / && $2!="127.0.0.1" {print $2" "$4}' | while read -r ipaddr m; do
      echo "$ipaddr/$(mask2prefix "$m")"; done)
  fi
  for n in $nets; do
    case $n in 127.*|169.254.*) continue ;; esac
    ipaddr=${n%/*}; pfx=${n#*/}
    [ "$pfx" -lt 22 ] && pfx=22          # scan at most 1022 hosts
    [ "$pfx" -gt 24 ] && pfx=24
    local o1 o2 o3 o4 c0 c count=$(( 1 << (24 - pfx) ))
    IFS=. read -r o1 o2 o3 o4 <<< "$ipaddr"
    c0=$(( o3 & ~(count - 1) & 255 ))
    echo "scanning ${o1}.${o2}.${c0}.0/${pfx} for ADB (port 5555)…" >&2
    for (( c = c0; c < c0 + count; c++ )); do
      for i in $(seq 1 254); do
        ( probe "${o1}.${o2}.${c}.${i}" && echo "${o1}.${o2}.${c}.${i}" ) &
        (( i % 128 == 0 )) && wait
      done
      wait
    done
  done
  "$ADB" mdns services 2>/dev/null | awk '/_adb/{print "mdns: "$0}'
}

cmd_connect() {
  [ $# -ge 1 ] || die "usage: connect <ip[:port]>"
  local t=$1; [[ $t == *:* ]] || t="$t:5555"
  "$ADB" connect "$t"
  echo "If the TV shows 'Allow USB debugging?', tick 'Always allow' and press OK."
  for _ in $(seq 1 30); do
    "$ADB" devices | grep -q "^${t}[[:space:]]*device" && { echo "$t" > .tv_target; echo "connected: $t"; return 0; }
    sleep 2
  done
  "$ADB" devices; die "not authorised yet — accept the prompt on the TV and run connect again"
}

cmd_backup() {
  local d; d="tv-backup-$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$d/shots" "$d/settings" "$d/packages"
  echo "backing up to $d …"
  echo "${TV:-$(a get-serialno | tr -d '\r')}" > "$d/device.txt"
  sh_ getprop > "$d/getprop.txt"
  sh_ pm list packages -f -u -U  > "$d/packages/all.txt"
  sh_ pm list packages -e        | sed 's/^package://' | sort > "$d/packages/enabled.txt"
  sh_ pm list packages -d        | sed 's/^package://' | sort > "$d/packages/disabled.txt"
  sh_ pm list packages -s        | sed 's/^package://' | sort > "$d/packages/system.txt"
  sh_ pm list packages -3        | sed 's/^package://' | sort > "$d/packages/third_party.txt"
  sh_ pm list packages -u        | sed 's/^package://' | sort > "$d/packages/including_uninstalled.txt"
  for ns in global secure system; do sh_ settings list "$ns" > "$d/settings/$ns.txt"; done
  sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
      | tail -1 > "$d/home_activity.txt"
  sh_ dumpsys package preferred-activities > "$d/preferred_activities.txt"
  sh_ pm query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
      > "$d/home_candidates.txt" 2>/dev/null
  sh_ dumpsys package > "$d/dumpsys_package.txt"
  sh_ dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' > "$d/foreground.txt"
  # Component-level states (disabled activities/services inside apps) for completeness
  sh_ dumpsys package | awk '/^  Package \[/{p=$2} /disabledComponents:/{f=1;next} f&&/^ +[a-zA-Z]/{print p" "$1; next} {f=0}' \
      > "$d/disabled_components.txt"
  : > "$d/changes.log"
  echo "# changes.log — one line per change, undone in reverse order by restore.sh" >> "$d/changes.log"
  BK="$d" cmd_shot 00-before-home
  ( cd "$d" && { if command -v sha256sum >/dev/null; then find . -type f ! -name changes.log -exec sha256sum {} + > SHA256SUMS; else find . -type f ! -name changes.log -exec shasum -a 256 {} + > SHA256SUMS; fi; } ) || true
  echo "backup complete: $d  ($(wc -l < "$d/packages/all.txt") packages, $(wc -l < "$d/packages/disabled.txt") already disabled)"
}

cmd_shot() {
  [ $# -ge 1 ] || die "usage: shot <name>"
  local d; d=$(need_bk); local f="$d/shots/$1.png"
  a exec-out screencap -p > "$f"
  [ -s "$f" ] || { a shell screencap -p /sdcard/_shot.png && a pull /sdcard/_shot.png "$f" >/dev/null && a shell rm /sdcard/_shot.png; }
  echo "$f"
}

state_of() { # enabled | disabled | missing
  if sh_ pm list packages -d | grep -qx "package:$1"; then echo disabled
  elif sh_ pm list packages | grep -qx "package:$1"; then echo enabled
  else echo missing; fi
}

cmd_disable() {
  local p s out
  for p in "$@"; do
    s=$(state_of "$p")
    case $s in
      missing)  echo "skip  $p (not installed)"; continue ;;
      disabled) echo "skip  $p (already disabled)"; continue ;;
    esac
    out=$(sh_ pm disable-user --user 0 "$p")
    if [ "$(state_of "$p")" = disabled ]; then
      log "DISABLE $p"; echo "off   $p"
    else
      echo "FAIL  $p: $out"
    fi
  done
}

cmd_enable() {
  local p
  for p in "$@"; do
    sh_ pm enable --user 0 "$p" >/dev/null
    echo "on    $p ($(state_of "$p"))"
  done
}

cmd_setting() {
  [ $# -eq 3 ] || die "usage: setting <global|secure|system> <key> <value>"
  local old; old=$(sh_ settings get "$1" "$2")
  sh_ settings put "$1" "$2" "$3" >/dev/null
  log "SETTING $1 $2 $old"
  echo "$1/$2: $old -> $(sh_ settings get "$1" "$2")"
}

cmd_install() {
  [ $# -ge 1 ] || die "usage: install <apk> [package]"
  local pkg=${2:-}
  [ -n "$pkg" ] || pkg=$(aapt dump badging "$1" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
  a install -r -g "$1" </dev/null || a install -r "$1" </dev/null || die "install failed"
  log "INSTALL ${pkg:-unknown}"
}

cmd_home() {
  [ $# -eq 1 ] || die "usage: home <pkg/.Activity>"
  local comp=$1 pkg=${1%%/*} old oldrole
  old=$(resolve_home)
  oldrole=$(sh_ cmd role get-role-holders --user 0 android.app.role.HOME | tr -d ' ;' | head -1)
  [[ $oldrole =~ ^[a-zA-Z][a-zA-Z0-9_.]*$ ]] || oldrole=""
  # Android 10+: the home screen is a "role"; older versions use set-home-activity. Do both.
  sh_ cmd role add-role-holder --user 0 android.app.role.HOME "$pkg" 0
  sh_ cmd package set-home-activity --user 0 "$comp"
  log "HOME $old ${oldrole:-}"
  echo "home role: ${oldrole:-none} -> $(sh_ cmd role get-role-holders --user 0 android.app.role.HOME)"
  echo "home: $old -> $(resolve_home)"
}

resolve_home() { sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }

foreground() { sh_ dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -1 | sed 's/^ *//'; }

# Reconnect automatically (adb forgets the TV after the Mac restarts or adb's server restarts).
ensure_connected() {
  [ -n "$TV" ] || return 0
  "$ADB" start-server >/dev/null 2>&1
  "$ADB" devices | grep -q "^${TV}[[:space:]]*device" && return 0
  echo "reconnecting to $TV …"
  "$ADB" connect "$TV" >/dev/null 2>&1
  for _ in $(seq 1 10); do
    "$ADB" devices | grep -q "^${TV}[[:space:]]*device" && { echo "connected: $TV"; return 0; }
    sleep 2
  done
  # The router may have given the TV a new address: scan, and accept only this Panasonic TV.
  echo "not at $TV — scanning the network for the TV…"
  local h
  for h in $(cmd_find 2>/dev/null | grep -E '^[0-9.]+$' | sort -u); do
    "$ADB" connect "$h:5555" >/dev/null 2>&1; sleep 2
    if [ "$("$ADB" -s "$h:5555" shell getprop ro.product.brand </dev/null 2>/dev/null | tr -d '\r')" = PANASONIC ]; then
      TV="$h:5555"; echo "$TV" > .tv_target; echo "found the TV at its new address: $TV"; return 0
    fi
  done
  die "can't reach the TV at $TV. Make sure it's switched on (not standby) and on the same Wi-Fi.
Run  ~/Documents/\"TV Debloat\"/tv.sh doctor  to find out why."
}

cmd_doctor() {
  ensure_adb
  "$ADB" start-server >/dev/null 2>&1
  local ip=${TV%%:*} out st
  echo "Saved TV address: ${TV:-none}"
  if [ -n "$ip" ]; then
    out=$("$ADB" connect "$ip:5555" 2>&1); echo "adb connect: $out"
    sleep 2
    st=$("$ADB" devices | awk -v t="$ip:5555" '$1==t{print $2}')
    case "$st" in
      device) echo "RESULT: connected and working. Run your command again."; return 0 ;;
      unauthorized) echo "RESULT: the TV is asking for permission. Look at the TV, tick 'Always allow' and press OK, then run your command again."; return 0 ;;
      offline) echo "RESULT: the TV is half-connected. Turn USB debugging off and on again in Developer options, then run: tv.sh connect $ip" ;;
    esac
    case "$out" in
      *refused*) echo "RESULT: the TV is on at $ip but network debugging is off (TVs often switch it off after a restart)."
                 echo "        On the TV: Settings > Device Preferences > Developer options > turn USB debugging OFF then ON. Then run: tv.sh connect $ip" ;;
    esac
  fi
  echo; echo "Scanning the network for the TV…"
  local hits; hits=$(cmd_find 2>/dev/null | grep -E '^[0-9.]+$' | sort -u)
  if [ -n "$hits" ]; then
    echo "Found ADB at: $hits"
    [ -n "$ip" ] && ! echo "$hits" | grep -qx "$ip" && echo "RESULT: the TV's address changed. Run: tv.sh connect $(echo "$hits" | head -1)"
  else
    echo "RESULT: no TV with debugging on was found. Check that the TV is ON (not standby) and on the same Wi-Fi as this Mac."
    echo "        If it is on: Settings > Device Preferences > Developer options > turn USB debugging OFF then ON, then run: tv.sh setup"
  fi
}

cmd_apply() {
  [ $# -eq 1 ] && [ -f "$1" ] || die "usage: apply <plan.txt>"
  ensure_connected
  local d; d=$(need_bk)
  echo "applying $1 — log: $d/apply.log"
  {
  local line verb n=0
  while IFS= read -r line <&3; do
    case $line in ''|\#*) continue ;; esac
    n=$((n + 1))
    # shellcheck disable=SC2086
    set -- $line; verb=$1; shift
    echo; echo "[$n] $line"
    case $verb in
      disable) cmd_disable "$@" ;;
      setting) cmd_setting "$@" ;;
      install) cmd_install "$@" ;;
      home)    cmd_home "$@" ;;
      shot)    cmd_shot "$@" ;;
      key)     sh_ input keyevent "$1" >/dev/null ;;
      launch)  sh_ am start -n "$@" ;;
      diag)    cmd_diag ;;
      action)  sh_ am start -a "$1" ;;
      restore-dry-run) "$HERE/restore.sh" --dry-run ;;
      guard)   # guard <text> <pkg>: if <text> isn't on screen, re-enable <pkg>, go Home and stop
               local fg2; fg2=$(foreground); echo "foreground: $fg2"
               echo "$fg2" | grep -q "$1" || { echo "Not on Gallery Home — turning $2 back on for safety."
                 sh_ pm enable --user 0 "$2"; sh_ input keyevent KEYCODE_HOME; sleep 3; cmd_shot guard-rollback
                 echo "STOPPED: expected '$1' on screen. $2 was re-enabled."; exit 3; } ;;
      wait)    sleep "$1" ;;
      expect)  # expect <text>: stop everything unless the foreground app matches
               local fg; fg=$(foreground); echo "foreground: $fg"
               echo "$fg" | grep -q "$1" || { cmd_shot "stopped-step$n" >/dev/null; cmd_diag >/dev/null
                 echo "STOPPED: expected '$1' on screen. Nothing after this step was changed."; exit 3; } ;;
      running) sh_ pidof "$1" >/dev/null || { echo "STOPPED: $1 is not running (crashed?)."; exit 3; }
               echo "$1 is running (pid $(sh_ pidof "$1"))" ;;
      say)     echo ">>> $*" ;;
      *)       echo "unknown step: $verb"; exit 2 ;;
    esac
  done 3< "$1"
  echo; echo "PLAN COMPLETE. Tell Claude: plan done."
  } 2>&1 | tee -a "$d/apply.log"
}

cmd_diag() {
  ensure_connected
  local d; d=$(need_bk); local f="$d/diag.txt"
  {
    echo "== $(date)"; echo "== foreground"; foreground
    echo "== pid"; sh_ pidof tv.galleryhome
    echo "== logcat (GalleryHome / crashes / network security)"
    sh_ logcat -d -v time | grep -iE 'GalleryHome|tv\.galleryhome|FATAL|AndroidRuntime|SSL|CertPath|cleartext' | tail -150
    echo "== art cache"; sh_ run-as tv.galleryhome ls -la cache/art 2>&1 | head
    echo "== art log (Gallery Home re-logs its saved wallpaper notes)"
    sh_ am start -n tv.galleryhome/.HomeActivity --ez dumplog true >/dev/null; sleep 3
    sh_ logcat -d -v time | grep 'GalleryHome.*artlog' | sed 's/.*artlog //' | tail -80; echo
    echo "== dns/ping"; for h in api.artic.edu www.artic.edu commons.wikimedia.org upload.wikimedia.org; do
      echo "$h: $(sh_ ping -c1 -W2 "$h" | head -2 | tail -1)"; done
    echo "== date/time on TV"; sh_ date
    echo "== screensaver"; sh_ settings get secure screensaver_components
    echo "== disabled now"; sh_ pm list packages -d | sed 's/package://' | sort
  } > "$f" 2>&1
  echo "saved $f — tell Claude: diag done"
}

cmd_setup() {
  ensure_adb
  "$ADB" start-server >/dev/null 2>&1
  local ip=${1:-}
  if [ -z "$ip" ]; then
    echo "Looking for your TV (make sure USB/network debugging is on)…"
    local hits; hits=$(cmd_find | sort -u)
    echo "$hits" | sed '/^$/d;s/^/  found: /'
    ip=$(echo "$hits" | grep -E '^[0-9.]+$' | head -1)
    if [ -z "$ip" ]; then
      printf "Not found automatically. Type the TV's IP (TV: Settings > Network > About): "; read -r ip
    fi
  fi
  cmd_connect "$ip"
  TV=$(cat .tv_target)
  echo "TV: $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)  Android $(sh_ getprop ro.build.version.release)"
  cmd_backup
  echo; echo "All set. Tell Claude: setup done."
}

cmd_restore() { exec "$(dirname "$0")/restore.sh" "$@"; }

c=${1:-}; shift || true
case "$c" in
  doctor|diag|apply|setup|find|connect|backup|shot|disable|enable|setting|install|home|restore) "cmd_$c" "$@" ;;
  *) sed -n '2,21p' "$0"; exit 1 ;;
esac
