<div align="center">

<img src="docs/brand/icon.png" width="96" alt="Aerial icon">

# Aerial

**A calm home screen for Android TV.**

A home screen modeled on Apple TV, with no ads and no recommendation feeds: just your apps, your wallpaper and aerial screensavers.<br>
Made for the Xiaomi, TCL and other TVs sold in China. It starts at boot and survives standby.

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
  <img src="site/img/grid.jpg" width="49%" alt="The app grid, with a folder at the end">
</p>

- **Home**: your favorite apps in one row on a glass shelf at the bottom, every app below it. Hidden system apps like Xiaomi's App Store and Mi Home show up too.
- **Organize**: hold OK (or press Menu) to move an app, put it in a folder, hide it or uninstall it. An HDMI input pinned to Home can say what is plugged in (Apple TV, PlayStation, Switch, Xbox, a computer...) and take its icon, or a name you type on your phone.
- **Control Center**: press Up from the top row to the top right. Inputs, network, settings, volume, brightness and Personalize.
- **Screensaver**: Apple TV's 114 aerial films, in 4K or 4K Dolby Vision where the TV supports it, or your photos, or a clock.
- **Wallpaper**: Bing's photo of the day, fourteen built-ins, photos you upload by scanning a code with your phone, or aerial films that play behind the apps after ten still seconds on Home and stop on the frame showing at any key.
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
| **OPPO** | Settings (设置) → System (系统) → About (关于本机) → Model (设备型号), press Up Up Right Right, then OK |
| **Other Android TVs** | Settings → Device Preferences → About → press Build 7 times, then turn on USB debugging in Developer options |

The TV's IP address is under Settings → Network. The first time you connect, the TV asks whether to allow debugging: choose Always allow.

### 2. Install

**With an agent (recommended)**: paste this into an agent that can run commands on your computer (Claude Code, Codex, WorkBuddy, Trae, Cursor…):

```text
Please install Aerial, an open-source home screen for Android TV, on my TV, and make it the home screen the TV starts into.
TV IP address: <TV-IP>
Keep ADB debugging on: yes

The install guide is here: https://aerial.qust.me/en/agent.md
(If you can't open it, use https://raw.githubusercontent.com/pengchujin/aerial-tv/main/site/en/agent.md)

Read the whole guide before you start. Then run the commands yourself on my computer; don't just tell me the steps. Tell me when I need to do something on the TV.
At the end, paste the raw output of the commands under "Check" in the guide.
```

Phone apps and web chats can't install it. When the agent says it's done, check that its output includes `app.orchard.tv`.

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

### Troubleshooting

- **Installed only the APK from a USB stick**: from 1.32 Aerial has an icon in the TV's app list and, when opened, shows how to make it the Home screen. With the APK alone, Home, power-on and wake still go to the old launcher; run the command above or hand it to an agent once more (it installs over, settings stay).
- **TCL shows a 多屏互动 prompt**: press Right, then OK, to choose 同意 (Agree).
- **Skyworth: `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED`**: run `adb shell settings put global verifier_verify_adb_installs 0`, then install again (from a user's report; see the [agent guide](site/en/agent.md#common-cases)).
- **The firmware won't install home-screen apps**: add `-s guard` to the end of the command, or use [aerial-guard.apk](https://aerial.qust.me/download/aerial-guard.apk).
- **What `KEEP_ADB=1` does**: Xiaomi turns ADB off after standby; with it, Aerial turns it back on so you can update later.
- **Do my apps, subscriptions and old home screen stay?** Yes. The old home screen is in the app list. A boot ad comes from the firmware and stays.
- **Not supported**: LG, Samsung, Huawei Vision, and Android 4.4 or older.

## How it stays your home screen

Most TVs sold in China only accept their own home screen and clear third-party apps in standby. So Aerial uses an accessibility service to switch back whenever the stock home screen appears (it only looks at which window is in front, never at what's on screen), and a notification listener to get the system to restart it after a kill (it reads no notifications). Stock Android TV needs none of this: Aerial is simply the default home screen.

## Tested TVs

| Model | System | Status |
|---|---|---|
| Xiaomi MiTV-MFTR0 / Redmi X65 2026 | Android 14 | In daily use ([notes](docs/devices/xiaomi-mitv-mftr0.md)) |
| Xiaomi MiTV-MFFU0 | Android 14 | Install and boot ([notes](docs/devices/xiaomi-mitv-mffu0.md)) |
| Xiaomi MiTV-ASTP0 / NSOP0 | Android 9 | User-verified ([#48](https://github.com/pengchujin/aerial-tv/issues/48), [#65](https://github.com/pengchujin/aerial-tv/issues/65)) |
| Xiaomi MiTV-MFTP0 | Android 9 | Install and boot; the guard drops after an update ([#72](https://github.com/pengchujin/aerial-tv/issues/72)) |
| TCL MT5879 (China) | Android 11 | In daily use ([notes](docs/devices/tcl-mt5879-cn.md)) |
| Sony A80J (China) / BRAVIA VH1 | Android 10 / 12 | User-verified ([#16](https://github.com/pengchujin/aerial-tv/issues/16), [#2](https://github.com/pengchujin/aerial-tv/issues/2)) |
| Sony BRAVIA 4K VH2 (China) | Android 12 | Install, Home button, App Content (Emby's continue watching and picks) |
| Skyworth Coocaa K3 Pro | Android 11 | User-verified ([#43](https://github.com/pengchujin/aerial-tv/issues/43)) |
| Konka KKTV U86V9 | Android 9 | User-verified: Home, start at boot, inputs; brightness does not work ([notes](docs/devices/konka-kktv-u86v9.md), Chinese) |
| OPPO K9 | Android 9 | Needs a renamed package ([notes](docs/devices/oppo-coloros-tv.md)) |
| Konka 2861, PPTV K55U27, XGIMI m7642, Tencent Aurora box 7S | Android 5–9 | User-verified with small issues, see [Issues](https://github.com/pengchujin/aerial-tv/issues) |
| FFALCON 55S595C Ultra | Android 11 | Installs, but can't take over Home ([#52](https://github.com/pengchujin/aerial-tv/issues/52)) |

Installs but doesn't start at boot: Vidda, Hisense, FFALCON Crane 6 2025 Ultra. If yours works, or doesn't, tell us with the [device support form](https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml) and its diagnostics: run `curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<tv-ip> bash` on a computer (it only reads), or copy them from 反馈问题 (Report a problem) at the bottom of the phone page. They hold no accounts, addresses, Wi-Fi names or app lists.

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
