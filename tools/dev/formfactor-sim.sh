#!/bin/bash
# Plays the Fold8 cover/inner screens and the Pad Mini (portrait and landscape) on the OPPO test phone with
# `wm size` + `wm density`, one LabActivity screenshot each at both ends of the plausible density range (DESIGN §8.1).
# Needs the phone lease (`~/Coach/testphone/lease.sh acquire <card>`); always resets wm size/density on exit.
# Usage: tools/dev/formfactor-sim.sh <apk> <package> <out-dir>
set -u
APK=${1:?apk}; PKG=${2:?package}; OUT=${3:?out-dir}
A=${COACH_ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}
T=${COACH_TESTPHONE_DIR:-$HOME/Coach/testphone}
mkdir -p "$OUT"
S=$("$T/install.sh" "$APK" | tee "$OUT/install.log" | sed -n 's/^测试机: //p')
[ -n "$S" ] || { echo "install failed, see $OUT/install.log"; exit 2; }
grep -q '^Success' "$OUT/install.log" || { echo "install did not report Success, see $OUT/install.log"; exit 2; }
dev() { $A -s "$S" shell "$@" </dev/null; }
# The phone's own display-size setting is itself a density override (420 on the OPPO); reset clears it, so put it back.
USER_DPI=$(dev wm density | sed -n 's/^Override density: //p' | tr -d '\r')
restore() {
  dev wm size reset; dev wm density reset
  { echo "== after reset"; dev wm size; dev wm density; } | tee "$OUT/restored.txt"
  if [ -n "$USER_DPI" ]; then dev wm density "$USER_DPI"; { echo "== user density restored"; dev wm density; } | tee -a "$OUT/restored.txt"; fi
}
trap restore EXIT INT TERM HUP
{ echo "== before"; dev wm size; dev wm density; } > "$OUT/before.txt"

# name widthPx heightPx dpi
CASES="
fold8-cover-portrait 1248 1972 450
fold8-cover-portrait 1248 1972 520
fold8-cover-landscape 1972 1248 450
fold8-cover-landscape 1972 1248 520
fold8-inner-portrait 1848 2448 374
fold8-inner-portrait 1848 2448 450
fold8-inner-landscape 2448 1848 374
fold8-inner-landscape 2448 1848 450
padmini-portrait 1680 2520 280
padmini-portrait 1680 2520 320
padmini-landscape 2520 1680 280
padmini-landscape 2520 1680 320
"
printf '%s\n' "$CASES" | while read -r name w h dpi; do
  [ -n "$name" ] || continue
  tag="$name-${dpi}dpi"
  dev wm size "${w}x${h}"; dev wm density "$dpi"; sleep 2
  dev am start -S -W -n "$PKG/com.coach.android.LabActivity" >/dev/null; sleep 4
  $A -s "$S" exec-out screencap -p </dev/null > "$OUT/$tag.png"
  # Read the override back; dp = px * 160 / dpi.
  got=$(dev wm size | sed -n 's/^Override size: //p' | tr -d '\r')
  line="$tag size=${w}x${h} dpi=$dpi window_dp=$((w * 160 / dpi))x$((h * 160 / dpi)) override=${got:-?}"
  echo "$line" | tee -a "$OUT/readings.txt"
done
dev am force-stop "$PKG"
