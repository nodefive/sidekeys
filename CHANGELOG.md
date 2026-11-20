# Changelog

## 1.8

Track skip now only reacts to the music apps you choose, and gains seeking.

### Added

- **Apps list.** Track skip only works while one of the checked apps is the one playing,
  whether it is on screen or in the background. Anything else that plays (videos, voice notes,
  audiobooks) keeps normal volume keys. Common music players are checked by default: Spotify,
  YouTube Music, Samsung Music, Apple Music, Deezer, Amazon Music, TIDAL, SoundCloud, Pandora,
  Poweramp, Musicolet, Retro Music and Gramophone.
- **Hold delay** setting (250 to 1000 ms, default 500): how long a press must last to change
  the volume instead of skipping.
- **Seek with tap and hold** (off by default): tap a volume key, then press and hold it to
  fast forward (volume up) or rewind (volume down). Two quick taps skip twice.
- **Seek speed** setting (2× to 30×, default 8×). The position slides smoothly at that speed
  rather than jumping.
- **Speed up while holding** (on by default): the seek speed doubles every 2 seconds held, up
  to 8 times.
- Card on the main screen with a shortcut when notification access is missing.

### Changed

- Track skip needs **notification access**. Android only reveals which app is playing, and
  lets other apps skip and seek in it, to apps with notification access. SideKeys registers an
  empty listener and does not read notifications. Without it, the volume keys stay normal.
- Skip and seek go straight to the playing app's media session instead of a generic media key.
- The main screen groups the track skip options in their own section.

### Upgrading from 1.4

After installing, allow SideKeys in Settings > Notifications > Notification access (the app
shows a shortcut), or:

```
adb shell cmd notification allow_listener com.unl0cker.bixby/com.unl0cker.bixby.MediaListener
```

## 1.4

First release.

- Bixby button toggles the rear flashlight, with the screen on or off.
- While audio plays, a quick press of volume up or down skips to the next or previous track;
  holding changes the volume.
- Both features can be switched on and off in the app.
- Android 10 or newer.
