<div align="center">

<img src="docs/brand/icon.png" width="96" alt="Aerial icon">

# Aerial

**A calm home screen for Android TV.**

A home screen modeled on Apple TV, with no ads and no recommendation feeds: just your apps, your wallpaper and aerial screensavers.<br>
Made for Xiaomi, TCL, Skyworth, Sony and other TVs sold in China, and runs on boxes and projectors too. It starts at boot and survives standby.

[中文](README.md) · **English**

<a href="https://aerial.qust.me/en/"><img alt="Website" src="https://img.shields.io/badge/Website-1f6feb?style=for-the-badge&logo=safari&logoColor=white"></a>
<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="Download APK" src="https://img.shields.io/badge/Download%20APK-34a853?style=for-the-badge&logo=android&logoColor=white"></a>
<a href="#install"><img alt="Install" src="https://img.shields.io/badge/Install-d97757?style=for-the-badge&logo=gnubash&logoColor=white"></a>

<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="Version" src="https://img.shields.io/github/v/release/pengchujin/aerial-tv?style=flat-square&label=version&color=2997ff"></a>
<a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-555555?style=flat-square"></a>
<img alt="Android TV 5.0+" src="https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">

![Aerial's home screen](site/img/home.jpg)

| The TV's stock home screen | With Aerial |
|:---:|:---:|
| <img src="site/img/stock-xiaomi.jpg" alt="The stock home screen, covered in show recommendations and membership badges"> | <img src="site/img/home.jpg" alt="Aerial's home screen"> |

</div>

## Features

<p align="center">
  <img src="site/img/focus.jpg" width="49%" alt="The glass shelf under the top row">
  <img src="site/img/grid.jpg" width="49%" alt="The app grid, with a folder in it">
</p>

- **Home**: your favorite apps in one row on a glass shelf at the bottom, every app below it. Hidden system apps like Xiaomi's App Store and Mi Home show up too.
- **Organize**: hold OK (or press Menu) to move an app, put it in a folder, hide it or uninstall it. An HDMI input pinned to Home can say what is plugged in (Apple TV, PlayStation, Switch, Xbox, a computer...) and take its icon, or a name you type on your phone.
- **Arrange from your phone**: scan the code on the TV and your phone shows Home as the TV lays it out. Drag tiles into place; tap one to rename it, change its icon, put it in a folder or hide it; add HDMI and other inputs to Home.
- **Control Center**: press Up from the top row to the top right. Inputs, network, settings, volume, brightness and Personalize.
- **Screensaver**: Apple TV's 114 aerial films, in 4K or 4K Dolby Vision where the TV supports it, your photos, a clock, or local videos sent from your phone ([guide, Chinese](docs/SCREENSAVER_VIDEOS.md)).
- **Wallpaper**: Bing's photo of the day, a daily source on your LAN ([Docker service included](tools/daily-source/README.md)), fourteen built-ins, photos you upload by scanning a code with your phone, or aerial films that play behind the apps after ten still seconds on Home and stop on the frame showing at any key.
- **Personalize**: one or two rows, clock size and corner, app names, focus style and key sounds.

<p align="center">
  <img src="site/img/folder.jpg" width="49%" alt="An open folder">
  <img src="site/img/control-center.jpg" width="49%" alt="Control Center">
</p>

<p align="center">
  <img src="site/img/screensaver-aerial.jpg" width="49%" alt="Aerial screensaver">
  <img src="site/img/wallpaper.jpg" width="49%" alt="The Personalize panel">
</p>

## Install

No root, no system apps touched, and you can undo it. Your computer and TV need to be on the same network.

### 1. Turn on ADB debugging

The menus on Chinese firmware are in Chinese; their names are in brackets.

| TV | How |
|---|---|
| **Xiaomi / Redmi** | Settings (设置) → About (关于) → press Model (产品型号) until developer mode is on, then Settings → Account & Security (账号与安全): turn on ADB debugging (ADB 调试) and Install unknown apps (安装未知来源的应用) |
| **TCL / FFALCON** | Settings (设置) → About (关于) → Device info (本机信息), quickly press Up Down Left Right, then turn on ADB under More (更多) |
| **Haier / MOOKA** | Press Source on the TV itself, then Left Left Up Right on the remote; in the factory menu turn on ADB 调试开关 and change nothing else |
| **OPPO (S1 included)** | Settings (设置) → System (系统) → About (关于本机) → Model (设备型号), press Up Up Right Right, then OK. Then Settings → System → Developer options (开发者选项): turn on USB debugging (USB 调试). The IP address is at the bottom of Settings → Network → the connected network |
| **Other Android TVs** | Settings → Device Preferences → About → press Build 7 times, then turn on USB debugging in Developer options |

The TV's IP address is under Settings → Network → the connected network. The first time you connect, the TV asks whether to allow debugging: choose Always allow.
A more detailed guide by brand, including TVs that cannot open ADB, is in [docs/devices/adb-by-brand.md](docs/devices/adb-by-brand.md) (Chinese).

### 2. Install

**With an agent (recommended)**: paste this into an agent that can run commands on your computer (Claude Code, Codex, WorkBuddy, Trae, Cursor, Doubao for desktop…):

```text
Please install Aerial, an open-source home screen for Android TV, on my TV, and make it the home screen the TV starts into.
TV IP address: <TV-IP>
Keep ADB debugging on: yes

The install guide is here: https://aerial.qust.me/en/agent.md
(If you can't open it, use https://raw.githubusercontent.com/pengchujin/aerial-tv/main/site/en/agent.md)

Read the whole guide before you start. Then run the commands yourself on my computer; don't just tell me the steps. Tell me when I need to do something on the TV.
At the end, paste the raw output of these commands from the "Check" section, not just "it worked":
- adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
- adb shell settings get secure enabled_accessibility_services
- adb shell "dumpsys window | grep mCurrentFocus" after pressing Home
```

Phone apps and web chats, including Doubao on a phone, only tell you the steps; they can't run anything. Use a desktop build that can (Doubao for desktop or Doubao Work, WorkBuddy, Qwen for desktop, Trae, Claude Code, Codex…). When the agent says it's done, check that the `mCurrentFocus` output it pastes includes `app.orchard.tv`.

**Without a computer**: users have installed Aerial with the phone apps Beetle ADB (甲壳虫 ADB 助手) and Super Xiaoai (超级小爱); we haven't tested each one. The steps are the same as the Windows ones above; once it's installed, run the check from a computer if you can (see [Check](site/en/agent.md#check-required)).

**One command (macOS / Linux)**:

```bash
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=192.168.1.20 bash
```

**By hand**: download [aerial-standard.apk](https://aerial.qust.me/download/aerial-standard.apk), then:

```bash
adb connect 192.168.1.20:5555
adb install -r aerial-standard.apk
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell am start -n app.orchard.tv/.MainActivity
```

On Windows, use an agent, or follow the Windows steps in the [install guide](site/en/agent.md).

**Updating**: from 1.32, Control Center shows New Version when there is one; choose Download and Install there and confirm in the TV's own installer (checked on Xiaomi and TCL). Aerial comes back afterwards with every setting kept. On TVs that do not let apps install apps, updating means running the install command again: the update page has a QR code that opens this TV's command on a phone, ready to copy or to hand to an agent. 1.31 and earlier have no such button, so this once, run the install command again.

### Undo

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=192.168.1.20 bash -s restore
```

Add `--uninstall` (`bash -s restore --uninstall`) to remove Aerial as well. Without bash (Windows), the adb commands one by one are under [Undo in the install guide](site/en/agent.md#undo).

To have an AI do it (it needs to run commands on a computer), paste this:

```text
Please follow the "Undo" section of https://aerial.qust.me/en/agent.md to give my TV (IP address: <TV-IP>) its original home screen back, without uninstalling Aerial.
Run the commands yourself on my computer, and at the end paste the raw output of settings get secure enabled_accessibility_services and of dumpsys window | grep mCurrentFocus after pressing Home.
```

### Troubleshooting

- **Will it work on my TV?** See [Supported TVs](#supported-tvs) below.

- **Installed only the APK from a USB stick**: from 1.32 Aerial has an icon in the TV's app list and, when opened, shows how to make it the Home screen. With the APK alone, Home, power-on and wake still go to the old launcher; run the command above or hand it to an agent once more (it installs over, settings stay).
- **TCL shows a 多屏互动 prompt**: press Right, then OK, to choose 同意 (Agree).
- **Skyworth: `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED`**: run `adb shell settings put global verifier_verify_adb_installs 0`, then install again (from a user's report; see the [agent guide](site/en/agent.md#common-cases)).
- **The firmware won't install home-screen apps**: add `-s guard` to the end of the command, or use [aerial-guard.apk](https://aerial.qust.me/download/aerial-guard.apk).
- **What `KEEP_ADB=1` does**: Xiaomi turns ADB off after standby; with it, Aerial turns it back on so you can update later.
- **Do my apps, subscriptions, Xiaoai, account and cable TV stay?** Yes. Aerial only replaces the home screen. The old home screen is in the app list (Xiaomi Video and PatchWall open from the 桌面 tile), and Xiaoai, the Xiaomi account and cable TV work as before. A boot ad comes from the firmware and stays.

## How it stays your home screen

Most TVs sold in China only accept their own home screen and clear third-party apps in standby. So Aerial uses an accessibility service to switch back whenever the stock home screen appears (it only looks at which window is in front, never at what's on screen), and a notification listener to get the system to restart it after a kill (it reads no notifications). Old Xiaomi sets on Android 6 report no window changes from their home screen, so there the guard reads the system's usage stats; it also knows the home screens some Coocaa and Skyworth firmware start in their own way. Stock Android TV needs none of this: Aerial is simply the default home screen.

## Supported TVs

✅ works　⚠️ works with limits　❌ can't take over yet. Mostly from user reports; the linked notes (Chinese) have details.

| Brand | Models | System | Status |
|---|---|---|---|
| Xiaomi / Redmi | MiTV-MFTR0 (Redmi X65 2026), MFFU0, MTSU0 (S65 Mini LED 2026) | Android 14 | ✅ In daily use ([MFTR0](docs/devices/xiaomi-mitv-mftr0.md), [MFFU0](docs/devices/xiaomi-mitv-mffu0.md), [MTSU0](docs/devices/xiaomi-mitv-mtsu0.md)) |
| Xiaomi | MiTV-ASTP0, NSOP0, MFTP0 | Android 9 | ✅ MFTP0 takes 30–60 s to come back after the system clears it ([notes](docs/devices/xiaomi-mitv-mftp0.md)) |
| Xiaomi | Mi TV 4 / 4A / 4X / 3S, Mi Box 4 | Android 6 | ✅ From 1.43; Home takes a few seconds to return to Aerial ([notes](docs/devices/xiaomi-android6.md)) |
| Xiaomi (Fengmi) | Ultraman series projector | Android 9 | ✅ ([notes](docs/devices/xiaomi-ultraman-projector.md)) |
| TCL | MT5879 (China) | Android 11 | ✅ In daily use ([notes](docs/devices/tcl-mt5879-cn.md)) |
| TCL | 75V2 (m7221) | Android 8 | ✅ Starts straight into Aerial, no root ([notes](docs/devices/tcl-m7221-cn.md)) |
| FFALCON | 55S595C Ultra, Crane 6 2025 Ultra | Android 11+ | ❌ Installs, can't take over Home ([notes](docs/devices/tcl-mt9655-cn.md)) |
| Sony | A80J (China), BRAVIA VH1 | Android 10 / 12 | ✅ |
| Skyworth / Coocaa | K3 Pro, 75A7D | Android 11 | ✅ 75A7D inputs work from 1.45 |
| Skyworth / Coocaa | 65G60, 8R134 / 8R145, aigc models (C10 projector, P60, 7T863) | Android 8–11 | ⚠️ Boots into Coocaa's home first, or takes 20–30 s to come back after the system clears it ([notes](docs/devices/skyworth-coocaa.md)) |
| Skyworth | 7T641, 75A6E | Android 13 | ❌ Black screen or freeze |
| Panasonic | TH-55HX560C (built by Skyworth) | Android 8 | ⚠️ No inputs listed |
| Konka | KKTV U86V9, 2861 | Android 9 | ✅ U86V9 brightness does not work ([notes](docs/devices/konka-kktv-u86v9.md)) |
| Hisense / Vidda | VIDAA models | Android 5–14 | ⚠️ Home works, most boot into Hisense's home first; VIDAA C1 projector can't be taken over ([notes](docs/devices/hisense-vidaa.md)) |
| OPPO | K9 | Android 9 | ⚠️ Needs a renamed package ([notes](docs/devices/oppo-coloros-tv.md)) |
| XGIMI | Z7X, m7642 | Android 9 | ✅ Z7X needs a third-party tool to open ADB ([notes](docs/devices/xgimi-z7x.md)) |
| OBE | X7 projector | Android 9 | ✅ Home inside HDMI can go to the Apple TV's home screen ([notes](docs/devices/obe-x7.md)) |
| Tencent Aurora box | 6 / 6SE / 7S | Android 10 / 14 | ✅ 6 works; ⚠️ 7S stays on the stock home at boot ([notes](docs/devices/tencent-aurora-box.md)) |
| Other boxes | CM311-1 (modified ATV), PPTV K55U27 | Android 9 | ✅ |

- **Not supported**: LG webOS, Samsung Tizen, Huawei Vision (HarmonyOS), and Android 4.4 or older.
- **Can't open ADB**: XGIMI H3, old LeTV sets, Honor Vision, old Konka models (no version number to tap).
- **Slow or low-memory TVs**: from 1.48 Aerial picks Lite effects on its own (solid panels, no highlights); you can also choose it in Personalize → Effects.

If yours works, or doesn't, tell us with the [device support form](https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml) and its diagnostics: run `curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<tv-ip> bash` on a computer (it only reads), or copy them from More → Report a Problem on the phone page. They hold no accounts, addresses, Wi-Fi names or app lists.

## Development

Needs JDK 17 and Android SDK 36.

```bash
./gradlew assembleStandardRelease assembleGuardRelease
BUILD=1 KEEP_ADB=1 TV=<TV-IP> tools/install.sh
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for the code, [HANDOFF.md](docs/HANDOFF.md) for the current state and rules, and [docs/devices/](docs/devices/) for notes on each TV.

## License

[MIT](LICENSE). The Inter font is under the [SIL Open Font License](FONT-LICENSE-OFL.txt). The list of Chinese cities for the weather is from [GeoNames](https://www.geonames.org) (CC BY 4.0), the weather from [Open-Meteo](https://open-meteo.com).

Aerial is an independent open-source project, not affiliated with Apple, Xiaomi, TCL or any other company. The aerial films belong to Apple and play from Apple's servers; the photo of the day comes from Bing.
