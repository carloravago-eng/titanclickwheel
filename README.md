# ClickWheel

A home-screen launcher and music player for the **Unihertz Titan 2 Elite**, built around the classic
click-wheel interface. You drive it with the phone's touch-sensitive QWERTY keyboard.

The home screen is an editable sidebar in a modern light or dark look, with a preview pane beside it.
The pane shows a folder's apps, your notifications, the clock, or what's playing. Lists, the accent
selection bar, Now Playing and the click sound keep the original player's feel.

## Controls

| Input | Action |
|---|---|
| Swipe up/down on the keyboard | Scroll (the "wheel"), with a tick sound and haptic |
| Enter | Open / select |
| Hold Enter, or Alt+Enter | Options for the highlighted item (edit the sidebar, folders, apps) |
| Alt + swipe in the sidebar | Move the highlighted item |
| Del | Back · hold, or Shift+Del: home |
| Space | Play / pause (also other music apps) |
| Shift+Enter | Jump to Now Playing |
| Any letter or number | Search apps, contacts, music and settings; calculator (`12*4`) and dialler |
| **Now Playing:** swipe | Volume |
| **Now Playing:** Enter, then swipe | Scrub through the song |
| **Now Playing:** N / B (or `.` / `,`) | Next / previous |
| **Now Playing:** S / R | Shuffle · repeat (ClickWheel's own player) |
| Screen | Tap to open, long-press for options, drag to scroll, tap the title or swipe right to go back |

If keyboard swipes do nothing, turn on the Titan's **Scroll assistant** and tick ClickWheel in its
app list. The app accepts keyboard swipes as scroll events, as a touchpad, or as injected touches.
If scrolling runs the wrong way, use *Settings → Scroll Direction*. *Settings → Scroll Speed*
adjusts sensitivity.

On phones without a hardware keyboard an on-screen click wheel appears automatically
(*Settings → Click Wheel*: Auto / On / Off).

## Setting up

1. Install the APK (see below) and open ClickWheel.
2. *Settings → Set as Home App*, choose ClickWheel.
3. Optional: *Settings → Notification Access* gives you notifications in the sidebar, app badges,
   and control of Spotify and other music apps from Now Playing.
4. Optional: *Settings → Contact Search*.

## Features

- **Sidebar**: sections, folders of apps, pinned apps and built-ins (Music, Now Playing, Search,
  All Apps, Notifications, Extras, Settings). Everything except All Apps and Settings can be
  removed, renamed or moved.
- **Status bar**: time, wifi, mobile signal, battery. It is drawn around the camera cutout.
- **Search** from anywhere: apps, folders, contacts (call / message), artists, albums, songs, Android
  settings pages, calculator, dial a number, web search.
- **Notifications** with unread badges on apps and folders; open or dismiss them from the wheel.
- **Music**: Playlists, Artists, Albums, Songs, Genres, Shuffle Songs; background playback with a
  media notification, lock-screen and headset controls; remembers the queue between launches.
- **Extras**: clock with next alarm, timer (rings through the system clock app), stopwatch, notes.
- Light / dark / automatic theme, six accent colours, three text sizes, hidden apps.
- No third-party dependencies: framework APIs only.

## Building

GitHub Actions builds the APK on every push. Each build on `main` is published as a release, so
the newest APK is always at:

**https://github.com/carloravago-eng/titanclickwheel/releases/latest/download/ClickWheel.apk**

Open that link on the phone and tap the download to install. Every build is also kept under
[Releases](https://github.com/carloravago-eng/titanclickwheel/releases). 
To make every new build install over the last one (keeping your settings), give CI a fixed signing
key. Add two repository secrets under *Settings → Secrets and variables → Actions*:

- `CLICKWHEEL_KEYSTORE_BASE64`: a keystore with key alias `clickwheel`, base64-encoded
- `CLICKWHEEL_KEYSTORE_PASSWORD`: its password (used for both the store and the key)

Without them, builds are signed with a temporary debug key, so installing a newer build means
uninstalling the old one first.

Locally, with the Android SDK installed:

```sh
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Requires Android 11+ (minSdk 30). The app targets Android 15 (API 35).
