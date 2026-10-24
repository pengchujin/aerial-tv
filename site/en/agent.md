# Installing Aerial (for AI agents)

You are helping the user install Aerial (an open-source home screen for Android TV, https://github.com/pengchujin/aerial-tv) on their TV and make it the home screen the TV starts into. Everything goes through adb on the user's computer; the TV doesn't need root.

## Rules

1. **Run the commands yourself on the user's computer.** Don't just list steps for the user. If you can't run commands (for example, you are a phone or web chat, including Doubao on a phone), say so and ask the user to switch to a desktop agent that can (Doubao for desktop or Doubao Work, WorkBuddy, Qwen for desktop, Claude Code, Codex, Cursor, Trae and similar), and to allow it to run commands and use the network the first time it asks.
2. **Don't claim success you haven't seen.** Read the real output of every command, and finish with "Check" below, pasting the raw output to the user, including the home screen `resolve-activity` finds and what `enabled_accessibility_services` contains. Without those outputs, don't say it is installed.
3. **Don't uninstall or disable any system app** (including the TV's own launcher), don't delete any file on the TV, and don't factory-reset it. The only file you may delete is `/sdcard/Download/Aerial.apk`, if this install created it.
4. Ask the user before rebooting the TV (`adb reboot`).
5. When the user has to do something on the TV (allow debugging, accept an install), tell them exactly which remote buttons to press.
6. If something fails, show the user the command and its exact output, then check "Common cases" below.

## Step 1: Get adb

Run `adb version`. If it prints a version, go to step 2.

Otherwise download Google's platform-tools and use the adb inside:

- **Windows** (PowerShell):
  ```powershell
  cd $env:USERPROFILE
  curl.exe -fL -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip
  Expand-Archive -Force platform-tools.zip .
  $env:Path = "$env:USERPROFILE\platform-tools;$env:Path"
  adb version
  ```
- **macOS**: `brew install android-platform-tools`. Without Homebrew, skip this: the one-line installer in step 3 downloads adb itself.
- **Linux**: install `adb` or `android-tools` from the package manager, or skip this as on macOS.

## Step 2: Connect to the TV

The computer and the TV must be on the same network. The TV's IP address from the user is `<IP>` below.

```
adb connect <IP>:5555
adb devices
```

The TV is connected once `adb devices` lists it as `device`.

- **`unauthorized`**: the TV is asking "Allow USB debugging?". Ask the user to choose Allow and check Always allow, then run `adb devices` again.
- **`failed to connect`, `Connection refused` or a timeout**: ADB debugging is off on the TV, or the IP is wrong. Ask the user to turn it on as below, then try again. Menus on Chinese firmware are in Chinese, so their names are in brackets.
- **Port 5555 will not connect and the TV only offers "Wireless debugging"** (TCL Q9M / Q10L and other Android 11+ models): these firmwares pair first. Ask the user to open Developer options → Wireless debugging → **Pair device with pairing code** on the TV, and read you two IP:port pairs plus the 6-digit code — one pair from the pairing screen, one from the main wireless-debugging screen (the ports differ and change every time the screen is opened). Then run `adb pair <pair-screen-ip:port> <code>`, after it succeeds `adb connect <main-screen-ip:port>`, and once `adb devices` lists `device`, run `adb tcpip 5555` right away followed by `adb connect <IP>:5555` — later runs need no pairing. The new `install.sh` walks through this (interactive prompts, or set `ADB_PAIR=<ip:port> ADB_CODE=<code> ADB_CONNECT=<ip:port>` before running). Some TCL firmwares (Q10M, 鹤 7 Pro) have no pairing-code entry; there a USB cable to a computer and one `adb tcpip 5555` is the only way in.

| TV | How to turn on ADB |
|---|---|
| Xiaomi / Redmi | Settings (设置) → About (关于) → press OK on **Model** (产品型号) until it says developer mode is on. Then Settings → Account & Security (账号与安全): turn on **ADB debugging** (ADB 调试) and **Install unknown apps** (安装未知来源的应用) |
| TCL / FFALCON | Settings (设置) → About (关于) → **Device info** (本机信息), then press **Up, Down, Left, Right**, less than a second apart. A **More** (更多) group appears at the bottom; turn on **ADB** there |
| OPPO TVs (S1 included) | Settings (设置) → System (系统) → About (关于本机) → **Model** (设备型号), press **Up, Up, Right, Right**, then OK. Then Settings → System → **Developer options** (开发者选项) and turn on **USB debugging** (USB 调试, sometimes called ADB debugging). From user reports; verified on the ColorOS TV in OPPO K9 |
| Haier / MOOKA | Press the **Input/Source button on the TV itself**; while the source menu is open, press **Left, Left, Up, Right** on the remote. In the factory menu (海尔工厂菜单) set **ADB 调试开关** (ADB debugging) to on (some models: second page, Other Option → adb debug). Change nothing else, and never pick 工厂复位 (factory reset). (User reports, e.g. MOOKA U65H3; not verified) |
| Android TV / Google TV, Sony, Hisense and others | Settings → Device Preferences → About → press **Build** 7 times (not Android version, which shows an Easter egg; press Back to leave it), then turn on **USB debugging** in Developer options (sometimes called Network debugging) |

The TV's IP address is usually under Settings → Network → the connected network (at the bottom of that page on OPPO S1).

**TVs that cannot open ADB**: XGIMI H3, old LeTV sets, Honor Vision, old Konka models (no version number to tap) and similar. There is no way yet; tell the user it is not supported and don't keep trying.

**Users without a computer**: some users installed Aerial with the phone apps 甲壳虫 ADB 助手 (Beetle ADB) and 超级小爱 (Super Xiaoai); we have not tested each one. They can follow steps 2 and 3 here on a phone (phone ADB tools usually can't run every command); once installed, run "Check" again from a computer or an agent where possible.

## Step 3: Install

### macOS / Linux: one command

```bash
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=<IP> bash
```

The script downloads the latest release, installs it, grants permissions, takes over Home and the screensaver, and prints the result. Leave out `KEEP_ADB=1` if the user doesn't want Aerial to keep ADB debugging on.

- If it says the firmware refuses home-screen apps, run it again with `-s guard` at the end.
- On **TCL** it prints "Pushing Aerial through 多屏互动". Ask the user to look for the 多屏互动 prompt in the top-right corner of the TV, **press Right to select 同意 (Agree), then OK**.

Then do "Check".

### Windows: one command at a time

Run these in PowerShell, with the adb from step 1. Everything inside `adb shell "..."` runs on the TV, so it's the same on every computer.

1. Download the APK:
   ```powershell
   curl.exe -fL -o aerial-standard.apk https://aerial.qust.me/download/aerial-standard.apk
   ```
   If that fails, use `https://github.com/pengchujin/aerial-tv/releases/latest/download/aerial-standard.apk`.

2. Install it: `adb install -r aerial-standard.apk`. Go on when it prints `Success`. If it fails:
   - With **`INSTALL_FAILED_VERIFICATION_FAILURE`**: this is a TCL, which refuses every adb install. Follow "TCL" below, then continue from item 3.
   - With **`INSTALL_FAILED_USER_RESTRICTED`** (Skyworth and others): see the Skyworth entry under "Common cases", then install again.
   - Otherwise (for example, the firmware refuses home-screen apps): install `aerial-guard.apk` instead (same address with `guard` in place of `standard`).

3. Grant the permissions only adb can grant. If one fails, go on with the next:
   ```
   adb shell pm grant app.orchard.tv android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
   adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
   adb shell appops set app.orchard.tv WRITE_SETTINGS allow
   adb shell appops set app.orchard.tv REQUEST_INSTALL_PACKAGES allow
   adb shell appops set app.orchard.tv GET_USAGE_STATS allow
   adb shell appops set app.orchard.tv android:get_usage_stats allow
   adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
   adb shell appops set app.orchard.tv APP_AUTO_START allow
   adb shell appops set app.orchard.tv APP_ASSOC_START allow
   adb shell cmd package compile -m speed -f app.orchard.tv
   ```
   The two `APP_*` app ops exist only on TCL; elsewhere they fail, which is fine. `compile` takes up to a minute and makes Aerial run smoothly on the TV.

4. Screensaver: run `adb shell settings get secure screensaver_enabled`. If it prints `1`, also run:
   ```
   adb shell settings put secure screensaver_components app.orchard.tv/app.orchard.tv.dream.AerialDream
   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez own_screensaver true
   ```

5. Keep ADB on, if the user wants it (Xiaomi turns ADB off after standby; this makes later updates easier):
   ```
   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
   ```

6. Start Aerial: `adb shell am start -W -n app.orchard.tv/.MainActivity`. The output must include `Status: ok`.

7. Make it the default home screen (skip this for the guard build):
   ```
   adb shell cmd role add-role-holder --user 0 android.app.role.HOME app.orchard.tv
   ```
   If that fails, use `adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity`. Xiaomi, TCL and other firmwares ignore this; Home Guard in the next item takes over there.

   **Hisense VIDAA**: first run `adb shell getprop persist.vt.use_home_pkg`. If it prints something (e.g. `com.jamdeo.tv.vod`) other than `app.orchard.tv`, keep the old value and point it at Aerial:
   ```
   adb shell settings put global aerial_vt_home_pkg <old value>
   adb shell setprop persist.vt.use_home_pkg app.orchard.tv
   ```
   If it prints nothing, skip this; other TVs don't have the property.

8. Home Guard: Aerial turns it on when it starts. Run `adb shell settings get secure enabled_accessibility_services`; the result should include `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`. If it doesn't, add it, **keeping what is already there**, joined with a colon:
   ```powershell
   adb shell "settings put secure enabled_accessibility_services '<previous value>:app.orchard.tv/app.orchard.tv.guard.HomeGuardService'"
   adb shell settings put secure accessibility_enabled 1
   ```
   If the previous value is `null` or empty, write `app.orchard.tv/app.orchard.tv.guard.HomeGuardService` alone. In PowerShell, write any `$` in the previous value as `` `$ ``.

   On some firmwares the `settings` command hangs and never returns (Tencent Aurora Box 6 / Q5043: cut off after 120 s). Skip this item there and check `adb shell dumpsys accessibility` for Aerial under `Enabled services` instead: once granted, Aerial turns Home Guard on itself.

### TCL (when adb can't install)

TCL installs only through the TV's own 多屏互动 (screen sharing) service, and only the guard build. The macOS / Linux one-liner does this by itself. On Windows it needs Python 3 (`python --version`; if missing, ask the user to install Python first):

```powershell
mkdir aerial-tcl; cd aerial-tcl
curl.exe -fL -o Aerial.apk https://aerial.qust.me/download/aerial-guard.apk
curl.exe -fL -o tcast-push.py https://aerial.qust.me/tools/tcast-push.py
adb reverse tcp:8765 tcp:8765
Start-Process python -ArgumentList '-m','http.server','8765','--bind','127.0.0.1' -WindowStyle Hidden
python tcast-push.py <IP> http://127.0.0.1:8765/Aerial.apk app.orchard.tv Aerial 0 180
```

After the last command, ask the user to answer the prompt in the top-right corner of the TV: **press Right, then OK, to choose 同意 (Agree)**. Once the script reports the app installed, stop the `python -m http.server` process, run `adb reverse --remove tcp:8765`, and continue from Windows item 3.

If the TV has no 多屏互动: `adb push Aerial.apk /sdcard/Download/Aerial.apk`, then ask the user to open it with a file manager or the Dangbei store's local install (本地安装) on the TV.

## Check (required)

Run each of these and paste the **raw output** to the user:

```
adb shell "dumpsys package app.orchard.tv | grep versionName"
adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
adb shell settings get secure enabled_accessibility_services
adb shell "dumpsys accessibility | grep -c -E 'label=(Aerial|app.orchard.tv)'"
adb shell input keyevent KEYCODE_HOME
adb shell "sleep 3; dumpsys window | grep mCurrentFocus"
```

- Line 1 shows a `versionName`: installed.
- Line 2 is the home screen the system resolves to. It is fine for Xiaomi to still name PatchWall (`com.mitv.tvhome`) or TCL its own launcher; Home Guard takes over. `app.orchard.tv` is better still.
- Line 3 includes `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`: Home Guard is on. (On stock Android TV, where Aerial is already the default home screen, it may not be; line 6 decides.)
- Line 4 is above 0: Home Guard is running.
- **Line 6 includes `app.orchard.tv`: Home leads to Aerial and the install worked.** Don't tell the user it's installed until it does.
- Paste the raw output of lines 2, 3 and 6 to the user, not just "success". If line 2 errors on an old system without `cmd`, run `adb shell pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` instead.

If line 6 shows something else, wait 5 seconds and run lines 5 and 6 again (Home Guard starts slowly on some TVs). If it still does, see "Common cases".

A Home press sent with `input keyevent` skips Home Guard's key filter, so on a few TVs (Sony, Android 9) it lands somewhere else than the remote's Home button does. Then ask the user to press Home on the remote once and run only line 6; that result counts.

Once all of it passes, ask the user before running `adb reboot`. After the TV has started again (a minute or two), `adb connect <IP>:5555` and run line 6 again to confirm the TV starts into Aerial.

## Undo

macOS / Linux:

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=<IP> bash -s restore
```

Add `--uninstall` (`bash -s restore --uninstall`) to remove Aerial as well.

Windows, one command at a time:

```
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb false --ez own_screensaver false
adb shell cmd notification disallow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell pm revoke app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW default
adb shell appops set app.orchard.tv REQUEST_INSTALL_PACKAGES default
adb shell appops set app.orchard.tv APP_AUTO_START default
adb shell appops set app.orchard.tv APP_ASSOC_START default
adb shell am force-stop app.orchard.tv
adb shell cmd role remove-role-holder --user 0 android.app.role.HOME app.orchard.tv
```

Then:

- If `adb shell settings get secure screensaver_components` is `app.orchard.tv/...`, put back the default: `adb shell settings put secure screensaver_components <value of adb shell settings get secure screensaver_default_component>`.
- `adb shell settings get secure enabled_accessibility_services`: remove `app.orchard.tv/app.orchard.tv.guard.HomeGuardService` (and the colon next to it) and write the rest back unchanged. If nothing else is left, run `adb shell settings delete secure enabled_accessibility_services`.
- Hisense VIDAA: if `adb shell settings get global aerial_vt_home_pkg` prints a value (not `null`), run `adb shell setprop persist.vt.use_home_pkg <that value>` and `adb shell settings delete global aerial_vt_home_pkg`. If it prints nothing but `adb shell getprop persist.vt.use_home_pkg` is `app.orchard.tv` (set by hand on 1.15 and earlier) and `com.jamdeo.tv.vod` is installed, run `adb shell setprop persist.vt.use_home_pkg com.jamdeo.tv.vod`.
- To uninstall: `adb uninstall app.orchard.tv`.
- Finally `adb shell input keyevent KEYCODE_HOME`; the TV goes back to its own home screen.

## Common cases

- **Xiaomi**: `resolve-activity` still names PatchWall (`com.mitv.tvhome`); that's expected, Home Guard takes over. Never disable `com.mitv.tvhome`. `KEEP_ADB` is recommended.
- **Xiaomi on Android 9 with low RAM (`adb shell getprop ro.config.low_ram` prints `true`, e.g. MFTP0)**: Android allows no third-party notification listener there, so `allow_listener` prints nothing and does nothing. That is expected; don't keep retrying. Xiaomi force-stops Aerial at standby; from 1.21 Android's sync service brings Aerial back about 30-60 seconds after the stop, and Home Guard then turns ADB back on. Woken after more than a minute, the TV shows Aerial straight away; woken after a few seconds, it shows the stock launcher first and switches to Aerial within about a minute. To test standby, stay in standby for over a minute and look 30 seconds after waking, without pressing Home or opening an app meanwhile. 1.20 and earlier do not come back after standby on these TVs.
- **TCL**: `adb install` always fails (`INSTALL_FAILED_VERIFICATION_FAILURE`); use 多屏互动 as in "TCL". If the user didn't see the prompt on the TV, run it again.
- **Skyworth (e.g. 7T631_T60, Android 13)**: when `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED`, the firmware is verifying adb installs. Note the current value with `adb shell settings get global verifier_verify_adb_installs`, run `adb shell settings put global verifier_verify_adb_installs 0`, then install again (on macOS / Linux, run the one-liner again). Afterwards put the old value back (if it was `null`, `adb shell settings delete global verifier_verify_adb_installs`), and turn it off again for each update. This comes from a user's report; it has not been checked on a Skyworth here.
- **TVs with only Wireless debugging** (FFALCON 55S595C Ultra, MT9655): adb can't be authorized there, so Aerial installs but can't take over Home.
- **Hisense VIDAA**: once item 7 has changed `persist.vt.use_home_pkg`, Home goes straight to Aerial; the TV still starts into its own launcher after a reboot (the firmware holds third-party apps back until boot completes), so press Home once.
- **Other Vidda systems, OPPO K9, FFALCON Crane 6**: Aerial installs but the TV doesn't start into it (the firmware only accepts its own launcher unless that is removed, which this install doesn't do). Aerial still works as an app.
- **Check line 4 is 0 but line 3 lists Aerial**: common right after an update, when the system still looks in the old install directory (Xiaomi, Android 9, #72). Run `adb shell am force-stop app.orchard.tv`, wait 3 seconds, run `adb shell am start -W -n app.orchard.tv/.MainActivity`, and check again. On macOS / Linux, `curl -fsSL https://aerial.qust.me/install.sh | TV=<IP> bash -s repair` does the same.
- **Home opens the TV's own launcher again after a while**: try the same stop-and-open first.
- **Check line 4 is 0 and line 3 does not list Aerial**: some firmwares block sideloaded accessibility services. Ask the user to allow Aerial's autostart and accessibility in the TV's security or permissions app, then run `adb shell am start -n app.orchard.tv/.MainActivity` and check again.
- **Forgot the parental PIN**: `adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez clear_pin true` turns it off.
- **Not supported**: LG webOS, Samsung Tizen, Huawei Vision (HarmonyOS), and Android 4.4 or older (Aerial needs Android 5.0). TVs that cannot open ADB, such as XGIMI H3, old LeTV sets, Honor Vision and old Konka models (no version number to tap), can't be used yet.

## What users ask

- **Do my apps and subscriptions stay?** Yes. Aerial only replaces the home screen; apps, accounts and memberships are unchanged, and open from Aerial. Xiaomi / TCL subscriptions, Xiaoai, the Xiaomi account and cable TV are unaffected.
- **Can I still use the old home screen?** Yes. It stays in Aerial's app list (including Xiaomi's PatchWall, opened from the 桌面 tile) and opens as before, with its recommendations. Xiaomi Video and PatchWall content are still there.
- **Can it remove the boot ad?** No. The firmware plays it before Aerial starts.
- **How do I update later?** From 1.32, when Control Center shows New Version, choose Download and Install and confirm in the system installer (checked on Xiaomi). On TVs that do not let apps install apps, run the same install command again (on Windows, repeat items 1, 2, 3 and 6 of step 3); every setting is kept.
- **Something is wrong on the TV and the user wants to report it**: on macOS / Linux run `curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<IP> bash`. It only reads, and writes `aerial-report-<model>.md`; read it, then paste it into https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml . Without a computer: scan the QR code in Personalize and use 反馈问题 (Report a problem) at the bottom of the phone page to copy the diagnostics.
- **Where do I report problems?** https://github.com/pengchujin/aerial-tv/issues, with the TV model (`adb shell getprop ro.product.model`) and the install output.
