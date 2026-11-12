# ClickWheel

A music player for the **Unihertz Titan 2 Elite** with a classic click-wheel interface: white
screen, a blue selection bar, a split main menu with artwork, Now Playing with a reflection, and a
glossy progress bar. You drive it with the phone's touch-sensitive QWERTY keyboard.

## Controls

| Input | Action |
|---|---|
| Swipe up/down on the keyboard | Scroll (the "wheel"), with a tick sound and haptic |
| Enter | Select |
| Del | Menu / back · hold, or Shift+Del: main menu |
| Space | Play / pause |
| Shift+Enter | Jump to Now Playing |
| Letter keys in a list | Jump to that letter (press again for the next match) |
| **Now Playing:** swipe | Volume |
| **Now Playing:** Enter, then swipe | Scrub through the song |
| **Now Playing:** N / B (or `.` / `,`) | Next / previous song |
| **Now Playing:** S / R | Shuffle on/off · repeat off/one/all |
| Arrow keys, media keys, headset | Work as expected |
| Screen | Tap a row, drag to scroll, tap the title bar or swipe right to go back |

**Search** (main menu) is a live search: just type.

If keyboard swipes do nothing, turn on the Titan's **Scroll assistant** and tick ClickWheel in its
app list. The app accepts keyboard swipes as scroll events, as a touchpad, or as injected touches.
If scrolling runs the wrong way, use *Settings → Scroll Direction*. *Settings → Scroll Speed*
adjusts sensitivity.

On phones without a hardware keyboard an on-screen click wheel appears automatically
(*Settings → Click Wheel*: Auto / On / Off).

## Features

- Music → Playlists, Artists, Albums, Songs, Genres; Shuffle Songs; Search
- Background playback with a media notification, lock-screen controls, headset buttons,
  audio focus and pause on headphone unplug
- Remembers the queue and position between launches
- Fast-scroll letter overlay with acceleration through long lists
- No third-party dependencies: framework APIs only

## Building

GitHub Actions builds the APKs on every push. Download them from the
run's **clickwheel-apk** artifact. The release APK is signed with the debug key so you can sideload
it directly.

Locally, with the Android SDK installed:

```sh
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Requires Android 11+ (minSdk 30). The app targets Android 15 (API 35).
