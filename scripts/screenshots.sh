#!/usr/bin/env bash
# Drives ClickWheel on an emulator and saves screenshots to ./shots.
# Usage: scripts/screenshots.sh path/to/app.apk
set -u
APK="$1"
OUT="shots"
PKG="com.orbit.clickwheel"
mkdir -p "$OUT"

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
key() { adb shell input keyevent "$@"; sleep 0.6; }
scroll() { # scroll N ticks: positive = down
  local n=$1 v=-1
  if [ "$n" -lt 0 ]; then v=1; n=$(( -n )); fi
  for _ in $(seq "$n"); do adb shell input mouse scroll 700 700 --axis "VSCROLL,$v"; sleep 0.35; done
}
type_text() { adb shell input text "$1"; sleep 0.8; }

# Titan 2 Elite-like square screen.
adb shell wm size 1440x1440
adb shell wm density 480
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true

# Test music with tags and cover art.
if command -v ffmpeg >/dev/null; then
  mkdir -p music
  i=0
  for spec in "Morning Light|Northern Shore|Coastlines|#3a7bd5" "Neon Avenue|The Satellites|After Hours|#d53a7b" \
              "Slow River|Northern Shore|Coastlines|#3a7bd5" "Paper Planes|Juniper Lane|Daydreams|#e0a030"; do
    IFS='|' read -r title artist album color <<< "$spec"
    i=$((i + 1))
    ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=$((300 + i * 80)):duration=200" \
      -f lavfi -i "color=c=${color}:s=600x600" -frames:v 1 -map 0:a -map 1:v -c:a libmp3lame -b:a 64k \
      -id3v2_version 3 -metadata title="$title" -metadata artist="$artist" -metadata album="$album" \
      -metadata track="$i" -disposition:v attached_pic "music/$i.mp3" || true
  done
  adb shell mkdir -p /sdcard/Music
  adb push music/. /sdcard/Music/ || true
fi

adb install -r -g "$APK"
# Like the Titan: no on-screen wheel (the emulator has no touch keyboard). Needs the debug APK.
printf '<map><int name="wheel_mode" value="2" /></map>' > settings.xml
adb push settings.xml /data/local/tmp/settings.xml
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/settings.xml shared_prefs/settings.xml'" || true
adb shell cmd package set-home-activity "$PKG/.MainActivity"
adb shell cmd notification allow_listener "$PKG/$PKG.notify.NotifyService" || true

# A test contact.
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:local --bind account_name:s:local || true
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:1 \
  --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'Sam Rivera' || true
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:1 \
  --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:5551234567 --bind data2:i:2 || true

# Some notifications.
adb shell cmd notification post -S bigtext -t "Sam Rivera" msg1 "Are we still on for dinner at 7?" || true
adb shell cmd notification post -S bigtext -t "Build passed" ci1 "ClickWheel APK built successfully" || true

adb shell cmd uimode night no
key HOME
sleep 4
shot 01-home-light 3

scroll 3
shot 02-sidebar-extras-preview

scroll 2
shot 03-sidebar-notifications-preview
key ENTER
shot 04-notifications
key ESCAPE

scroll -1
key ENTER
shot 05-all-apps
adb shell input keyevent --longpress ENTER
shot 06-options-sheet
key ESCAPE
key ESCAPE

key HOME
type_text "se"
shot 07-search-se
key DEL DEL
type_text "12\*4"
shot 08-search-calculator
key DEL DEL DEL DEL
type_text "sam"
shot 09-search-contact
key HOME

# Music: Music > Shuffle Songs.
key HOME
scroll -6
key ENTER
shot 10-music-menu
key ENTER
shot 11-now-playing 3
key HOME
shot 12-home-now-playing-preview

key HOME
scroll 3
key ENTER
key ENTER
shot 13-clock

adb shell cmd uimode night yes
key HOME
scroll -6
shot 14-home-dark 3
key ENTER
key ENTER
shot 15-now-playing-dark 3

# Camera cutout emulation, to check the status bar stays clear of it.
for cut in hole corner; do
  adb shell cmd overlay enable "com.android.internal.display.cutout.emulation.$cut" || true
  key HOME
  shot "16-cutout-$cut" 4
  adb shell cmd overlay disable "com.android.internal.display.cutout.emulation.$cut" || true
done

adb logcat -d -b crash > "$OUT/crash-log.txt" || true
adb logcat -d | grep -iE "clickwheel|AndroidRuntime" | tail -300 > "$OUT/logcat.txt" || true
ls -la "$OUT"
