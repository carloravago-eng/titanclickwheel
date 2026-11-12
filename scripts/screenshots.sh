#!/usr/bin/env bash
# Drives ClickWheel on an emulator and saves screenshots to ./shots.
# Usage: scripts/screenshots.sh path/to/app-debug.apk
set -u
APK="$1"
OUT="shots"
PKG="com.orbit.clickwheel"
mkdir -p "$OUT"
exec > >(tee "$OUT/script.log") 2>&1

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "== shot $1"; }
key() { adb shell input keyevent "$@"; sleep 0.6; }
down() { for _ in $(seq "$1"); do adb shell input keyevent DPAD_DOWN; sleep 0.25; done; sleep 0.4; }
type_text() { adb shell input text "$1"; sleep 0.8; }

# Titan 2 Elite-like square screen.
adb shell wm size 1440x1440
adb shell wm density 480
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true

# Test music with tags and cover art. Copied with the shell's cp so it goes through
# Android's storage layer and gets indexed (adb push alone bypasses the media scanner).
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
  adb shell mkdir -p /data/local/tmp/music /sdcard/Music
  adb push music/. /data/local/tmp/music/
  adb shell 'cp /data/local/tmp/music/*.mp3 /sdcard/Music/'
  for f in music/*.mp3; do
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/$(basename "$f")" >/dev/null || true
  done
  sleep 5
  adb shell content query --uri content://media/external/audio/media --projection title:artist || true
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
RAW=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | grep -o '_id=[0-9]*' | tail -1 | cut -d= -f2)
echo "raw contact id: $RAW"
if [ -n "$RAW" ]; then
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$RAW" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"Sam\ Rivera" || true
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$RAW" \
    --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:5551234567 --bind data2:i:2 || true
fi

# Some notifications.
adb shell cmd notification post -S bigtext -t "Sam Rivera" msg1 "Are we still on for dinner at 7?" || true
adb shell cmd notification post -S bigtext -t "Build passed" ci1 "ClickWheel APK built successfully" || true

adb shell cmd uimode night no
key HOME
sleep 5
shot 01-home-light 3

# Sidebar: Music, Now Playing, Search, Extras, All Apps, Notifications, Settings.
down 3
shot 02-sidebar-extras-preview
down 2
shot 03-sidebar-notifications-preview
key ENTER
shot 04-notifications
key ESCAPE
key DPAD_UP
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
shot 09-search-contact 3
key HOME

# Music > Shuffle Songs.
key HOME
key ENTER
shot 10-music-menu
key ENTER
shot 11-now-playing 3
key HOME
shot 12-home-now-playing-preview

# Extras > Clock.
key HOME
down 3
key ENTER
shot 13-extras
key ENTER
shot 14-clock

# Settings, then the Input Test with every kind of emulated scroll.
key HOME
down 6
key ENTER
shot 15-settings
down 17
key ENTER
sleep 1
adb shell input mouse scroll 700 700 --axis VSCROLL,-1 || echo "mouse scroll failed"
sleep 0.5
adb shell input scroll 700 700 --axis SCROLL,-1 || echo "rotary scroll failed"
sleep 0.5
adb shell input trackball roll 0 3 || echo "trackball failed"
sleep 0.5
adb shell input swipe 700 1000 700 600 300 || echo "swipe failed"
sleep 0.5
adb shell input keyevent DPAD_DOWN
shot 16-input-test
key ESCAPE
key HOME

adb shell cmd uimode night yes
key HOME
shot 17-home-dark 3
key ENTER
key ENTER
shot 18-now-playing-dark 3

# Camera cutout emulation, to check the status bar stays clear of it.
for cut in hole corner; do
  adb shell cmd overlay enable "com.android.internal.display.cutout.emulation.$cut" || true
  sleep 4
  adb shell dumpsys window displays | grep -i -m3 cutout || true
  key HOME
  shot "19-cutout-$cut" 4
  adb shell cmd overlay disable "com.android.internal.display.cutout.emulation.$cut" || true
done

adb logcat -d -b crash > "$OUT/crash-log.txt" || true
adb logcat -d -s ClickWheel.Input:D | tail -200 > "$OUT/input-log.txt" || true
adb logcat -d | grep -iE "clickwheel|AndroidRuntime" | grep -v AppsFilter | tail -300 > "$OUT/logcat.txt" || true
ls -la "$OUT"
