# SideKeys

Hardware key shortcuts for Samsung Galaxy phones:

- **Flashlight on the Bixby button.** Each press of the dedicated Bixby key turns the rear
  flashlight on or off, with the screen on or off.
- **Track skip on the volume keys.** While audio plays, a quick press of volume up or down
  skips to the next or previous track. Holding the key changes the volume as usual.

Both features can be switched on and off in the app. The app has no network access and its
Accessibility service cannot read screen content.

## Device compatibility

SideKeys needs **Android 10 or newer**.

| Feature | Works on |
|---|---|
| Flashlight on Bixby button | Samsung phones with a dedicated Bixby key **and** Android 10 or newer: Galaxy S10e, S10, S10+, S10 5G (tested on the S10e); Galaxy S9, S9+, Note9 (untested, see below) |
| Track skip on volume keys | Any Android 10+ phone |

Only some Samsung phones have a separate Bixby key: the Galaxy S8, S8+, Note8, S9, S9+, Note9
and the S10 family. Samsung removed it starting with the Galaxy Note10; on later models the
assistant moved to the side (power) key, so there is no Bixby key for SideKeys to use. The
S8, S8+ and Note8 stopped at Android 9 and cannot install SideKeys. The S9, S9+ and Note9 end
at Android 10 (One UI 2.x) and can install it.

The Bixby key is detected from One UI system log lines (see below). Those lines are One UI
internals: they were verified on One UI 4 (Android 12). On the S9 and Note9 (One UI 2.x) the
log lines may differ, so the flashlight feature is untested there; track skip does not depend
on them. Custom ROMs may log differently as well. Reports from other devices are welcome.

Tested on:

| Device | Firmware | Flashlight | Track skip | Screen off |
|---|---|---|---|---|
| Galaxy S10e | One UI 4.1, Android 12 | yes | yes | yes |

On other phones the volume shortcut works with the screen on; whether volume keys reach the
app with the screen off depends on the manufacturer's firmware.

| Key | Condition | Action |
|---|---|---|
| Bixby | flashlight feature on | toggle the rear flashlight |
| Volume up, quick press (< 500 ms) | audio playing, not in a call | next track |
| Volume down, quick press (< 500 ms) | audio playing, not in a call | previous track |
| Volume up/down, held | audio playing, not in a call | media volume, one step every 150 ms |
| Volume up/down | nothing playing, in a call, or feature off | normal system behaviour |

## Install

1. Download `sidekeys-<version>.apk` from the [Releases](../../releases) page and install it
   (`adb install sidekeys-<version>.apk`, or open the file on the phone).
2. For the flashlight feature, grant log access once from a computer with adb:
   ```
   adb shell pm grant com.unl0cker.bixby android.permission.READ_LOGS
   ```
3. Open SideKeys and tap **Open Accessibility settings**, then turn SideKeys on.
   On Android 13 and newer, sideloaded apps first need App info > menu (three dots) >
   **Allow restricted settings** before Accessibility can be turned on, and Android asks
   once to allow log access.

Grant log access before turning the service on: a process only gets log access when it
starts. If the service was already on, turn it off and on again. Do not use
`adb shell am force-stop` for this, because force-stopping an Accessibility service's app
also turns the service off.

If another app also handles the Bixby key (for example Bixby Button Actions), turn it off,
or both apps toggle the flashlight and cancel each other out.

## How it works

### Volume keys

An Accessibility service with `flagRequestFilterKeyEvents` only (`canRetrieveWindowContent`
is false and no event types are requested) receives hardware key presses. While music plays
and the phone is not in a call, it holds a volume key press:

- released before 500 ms: sends `MEDIA_NEXT` or `MEDIA_PREVIOUS` through `AudioManager`, so it
  works with any player that handles media keys;
- held longer: adjusts the media volume one step every 150 ms and shows the volume panel.

In every other case the key passes through untouched.

### Bixby key

The Bixby key is scancode 703 (`WINK` in `Generic.kl`). One UI keeps this key for itself and
does not pass it to apps (`interceptKeyTq interceptKeyResult=4`), so Accessibility services
never receive it. The service therefore runs `logcat` restricted to two system_server tags:

```
InputReader: Input event(9): value=1|0            hardware key down/up (any side key)
PhoneWindowManagerExt: getIntentBixbyService ...   logged for the Bixby key only
```

A `getIntentBixbyService` line right after a key-down is one Bixby press, and the flashlight is
toggled through `CameraManager.setTorchMode`, which needs no permission. Reading the log
requires `READ_LOGS`, a development permission that only adb can grant. The log watcher only
runs while the flashlight feature is on.

### Permissions

| Permission | Why |
|---|---|
| `READ_LOGS` (granted over adb) | Bixby key detection |
| Accessibility service (key events only) | volume keys; keeps the app running for the log watcher |

No internet permission, no camera permission, no storage access.

## Build

The app has no library dependencies and can be built with the Android SDK tools alone:

- Android SDK with build-tools and the `android-36.1` platform
- JDK 17 or newer

```
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # or export ANDROID_HOME
scripts/build.sh debug     # build/manual/app-debug.apk, signed with the Android debug key
```

`scripts/env.sh` finds the tools: `ANDROID_HOME` or `sdk.dir`, `JAVA_HOME` or `javac` on
`PATH`, the newest installed build-tools (override with `BUILD_TOOLS`) and the
`android-36.1` platform (override with `PLATFORM`). Versions and the application ID are read
from `app/build.gradle.kts`, so the project also opens in Android Studio. Timings are
constants in `KeyService.java` (`LONG_PRESS_MS`, `REPEAT_MS`).

## Signing your own build

A debug build is fine for trying the app. For a build you keep and update, sign it with your
own key. The key never goes into the repository: `.gitignore` excludes `*.jks`, `*.p12`
and `*.keystore`, and the password is only typed at the prompt.

1. Create a key once, outside the repository, and keep a backup of it and its password.
   Android only installs an update over an app signed with the same key.
   ```
   keytool -genkeypair -v -keystore ~/keys/sidekeys.jks -alias sidekeys \
       -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Your Name"
   ```
2. Tell the script where it is (for example in your shell profile):
   ```
   export SIDEKEYS_KEYSTORE=~/keys/sidekeys.jks
   export SIDEKEYS_KEY_ALIAS=sidekeys
   ```
3. Build and sign:
   ```
   scripts/sign-release.sh    # dist/sidekeys-<version>.apk
   ```
   apksigner asks for the keystore password, then prints the certificate and the APK
   checksum.

An APK signed with your key cannot be installed over one signed with another key (such as
the release from this page): uninstall the other one first, then grant log access and turn
on the Accessibility service again.

## Disclaimer

Not affiliated with or endorsed by Samsung. Galaxy, Bixby and One UI are trademarks of
Samsung Electronics.
