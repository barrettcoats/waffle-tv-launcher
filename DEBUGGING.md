# Debugging

How to find out why the launcher misbehaves on a particular TV, so it can be fixed here.

All commands assume `adb` is connected (`adb connect <tv-ip>:5555`, with network debugging on in
the TV's developer options).

## 1. Quick look on the TV itself

**Settings (gear) → About → Diagnostics** shows:

- **Home Button Opens**: which app Android starts for Home. If it isn't "This launcher", the TV
  isn't using us as its home screen (see section 6).
- Launcher version, device, Android version and API level.
- How many apps were found. If this is much lower than what's installed, it's an app-visibility
  problem (section 5).
- The size of the layout, the number of wallpapers, and the **last crash** with its full stack
  trace.

A photo of that screen is often enough for a bug report.

## 2. Logs

Everything the launcher logs uses one tag, at INFO level or above. Many TV builds, including
LineageOS on the Shield, set `log.tag` to `I` and drop `Log.d`, so don't add debug-level logs.

```sh
adb logcat -s Waffle            # just ours
adb logcat -s Waffle AndroidRuntime ActivityTaskManager   # plus crashes and app starts
```

On start it logs the version and device, the number of apps found and the layout size. It also
logs every app launch (and why one failed), wallpaper changes, and layout save errors.

## 3. Crashes

Any crash is written to `files/last-crash.txt` before the process dies. Read it under Diagnostics
→ Last Crash, or from a debug build:

```sh
adb shell run-as com.barrettcoats.waffle cat files/last-crash.txt
```

## 4. A debuggable build

Release builds can't be inspected with `run-as`. A debug build has the same package name but is
signed with the debug key, not the release key, so it won't install over a release build. Android
refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first, which clears the layout and
photos:

```sh
./gradlew assembleDebug
adb uninstall com.barrettcoats.waffle
adb install app/build/outputs/apk/debug/waffle-<version>-debug.apk
```

Going back to a release build needs another uninstall for the same reason.

Then the app's private files are reachable:

```sh
adb shell run-as com.barrettcoats.waffle ls files/
adb shell run-as com.barrettcoats.waffle cat files/config.json     # the layout
```

To edit the layout by hand, stop the launcher first so it doesn't save over your change:

```sh
adb shell am force-stop com.barrettcoats.waffle
adb shell run-as com.barrettcoats.waffle cat files/config.json > config.json
# edit config.json
adb push config.json /data/local/tmp/tvl.json
adb shell "run-as com.barrettcoats.waffle cp /data/local/tmp/tvl.json files/config.json"
adb shell rm /data/local/tmp/tvl.json
```

A layout file that won't parse is moved aside to `config.json.bad`, never silently deleted, and
the launcher starts with a fresh default layout.

You can also attach Android Studio's debugger to a debug build (Run → Attach Debugger to Android
Process).

## 5. "Some apps are missing"

The launcher lists apps through `queries` in the manifest (every app with a TV or phone launcher
entry). To check what Android reports:

```sh
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

If an app shows up there but not in our list, the bug is in `data/Apps.kt`.

## 6. "Home doesn't open the launcher"

```sh
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
adb shell cmd package set-home-activity com.barrettcoats.waffle/.MainActivity
```

On LineageOS and stock Android TV, `set-home-activity` (or choosing the app in Settings → Apps →
Default apps) is enough. Google TV devices (Chromecast with Google TV, newer TCL and Sony sets) are
different: their own launcher ignores the choice and takes Home anyway. The usual workaround there
is to disable the stock launcher with
`adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx`, at your own risk.

## 7. "Weather or phone upload doesn't work"

First check **Diagnostics → Internet Access**. On LineageOS with *restricted networking* on,
newly installed apps get no network at all (Settings → Apps → Waffle → Network access is
off), while the TV and adb are online. Requests then fail with a misleading
`Unable to resolve host … No address associated with hostname`. The launcher detects this and
offers App Info, but you can also confirm it from adb:

```sh
adb shell settings get global restricted_networking_mode       # 1 = on
adb shell pm list packages -U | grep waffle                 # our uid, e.g. 10108
adb shell dumpsys netpolicy | grep 'UID=10108'                  # policy=REJECT_ALL means blocked
```

Logs show `(network access blocked for this app)` on each failed weather update.

**Why it happens:** LineageOS gives an app a `REJECT_ALL` network policy when it's installed
*without* the INTERNET permission, and keeps it when an update later adds that permission. Our
first builds had no INTERNET permission, so a TV upgraded from one of them stays blocked. A
fresh install of the current version isn't affected (verified: policy `0`). On phones,
App Info → Network access clears it. LineageOS's TV Settings has no such switch, so on a TV
either reinstall (the layout resets) or clear the flag over adb, keeping everything:

```sh
U=$(adb shell pm list packages -U | grep waffle | sed 's/.*uid://')
adb shell service call netpolicy 4 i32 $U            # getUidPolicy: 00040000 = REJECT_ALL
adb shell service call netpolicy 3 i32 $U i32 262144 # removeUidPolicy(uid, REJECT_ALL)
```

(Transaction codes 3 and 4 are `removeUidPolicy` and `getUidPolicy` in
`INetworkPolicyManager` on Android 15. Check them with the read-only `4` call first on other
versions.)

## 8. "The remote doesn't move anything" (frozen picture)

If the home screen looks dead but the app hasn't crashed, check whether it's actually drawing:

```sh
adb shell dumpsys gfxinfo com.barrettcoats.waffle | grep "Total frames"   # run twice with a D-pad press between
adb shell dumpsys activity top | grep mLastPerformDrawFailedReason          # screen_off = this bug
```

A flat frame count plus `screen_off` means the window thinks the display is off. Focus still
moves (`uiautomator dump` shows it), but nothing reaches the screen. `adb shell am force-stop
com.barrettcoats.waffle` and then Home recovers it.

**Why it happens:** Android 15 holds back display notices for apps it considers cached (a
launcher sitting behind another app is one) and delivers them when the app comes forward. If the
Shield is put to sleep with the power button while our screensaver is showing, the app hears
"display off" right away (the screensaver makes the whole app foreground), but the "display on"
at wake is held back and never arrives. Moving the screensaver to its own process doesn't help:
Android decides per app (uid), not per process. Letting the Shield sleep on its timer doesn't
trigger it.

To reproduce: choose Waffle's screensaver, set `screen_off_timeout` to 30000 and `sleep_timeout`
to 90000, open another app, wait for the screensaver, press power, wait a minute, then wake and
press Home. `MainActivity` now notices a resume that doesn't draw while the screen is on and
restarts itself in a new window (logged as `Home screen is stuck not drawing`).
`recreate()` isn't enough, because it keeps the same stuck window.

## 9. Layout logic tests

The layout operations (moving between rows and sections, resizing, sizing new tiles) are pure
functions in `data/LayoutOps.kt`, with unit tests that run on your computer. No TV is needed:

```sh
./gradlew testDebugUnitTest
```

Add a test there for any layout bug before fixing it.

## 10. Driving the TV from a script

`adb shell input keyevent KEYCODE_DPAD_…` works, but check what's focused before sending OK
(`adb shell uiautomator dump` lists focused elements by name). A blind sequence can land
somewhere unexpected, such as the Wi-Fi button and then the Wi-Fi off switch. While edit mode's
wiggle is running the UI never goes idle and `uiautomator dump` fails, so use screenshots
(`adb exec-out screencap -p > shot.png`) there.
