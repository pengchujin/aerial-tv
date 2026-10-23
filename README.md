<div align="center">

<img src="docs/brand/icon.png" width="96" alt="Aerial 图标">

# Aerial

**给国产电视的一块干净桌面。**

参照 Apple TV 做的 Android TV 桌面：没有广告，没有推荐流，只有你的 App、壁纸和航拍屏保。<br>
适合小米、TCL 等国产电视，开机自动进入，待机也不会被清理。

**中文** · [English](README.en.md)

<a href="https://aerial.qust.me"><img alt="官网" src="https://img.shields.io/badge/%E5%AE%98%E7%BD%91-1f6feb?style=for-the-badge&logo=safari&logoColor=white"></a>
<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="下载 APK" src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD%20APK-34a853?style=for-the-badge&logo=android&logoColor=white"></a>
<a href="#安装"><img alt="安装" src="https://img.shields.io/badge/%E5%AE%89%E8%A3%85-d97757?style=for-the-badge&logo=gnubash&logoColor=white"></a>

<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="版本" src="https://img.shields.io/github/v/release/pengchujin/aerial-tv?style=flat-square&label=%E7%89%88%E6%9C%AC&color=2997ff"></a>
<a href="LICENSE"><img alt="许可证 MIT" src="https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF%E8%AF%81-MIT-555555?style=flat-square"></a>
<img alt="Android TV 5.0+" src="https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">

![Aerial 首页](site/img/home.jpg)

| 电视自带的桌面 | 换上 Aerial 之后 |
|:---:|:---:|
| <img src="site/img/stock-xiaomi.jpg" alt="电视自带的桌面：满屏剧集推荐、会员和限免角标"> | <img src="site/img/home.jpg" alt="Aerial 首页"> |

</div>

## 功能

<p align="center">
  <img src="site/img/focus.jpg" width="49%" alt="第一排的玻璃底板">
  <img src="site/img/grid.jpg" width="49%" alt="往下滚动后的应用网格，最后一个是文件夹">
</p>

- **首页**：常用的一排 App 放在屏幕下方的玻璃底板上，往下是全部 App。小米「应用中心」「米家」这类藏起来的系统 App 也在。
- **整理**：长按 OK 键（或按菜单键）可以移动、放进文件夹、隐藏、卸载。固定到主屏幕的 HDMI 可以选接的是 Apple TV、PlayStation、Switch、Xbox、电脑等，换成对应的图标，也能在手机上起名字。
- **控制中心**：在第一排按上键到右上角打开，有信号源、网络、设置、音量、亮度和个性化。
- **屏保**：Apple TV 的 114 段航拍影片，电视支持时播 4K 或 4K 杜比视界，也可以换成照片或时钟。
- **壁纸**：必应每日精选、14 张内置壁纸、用手机扫码传的照片，或者航拍：停在主屏幕 10 秒后在磁贴后面播放，按任意键停在当前画面。
- **个性化**：单排或双排、时钟大小和位置、App 名称、焦点样式、按键声音，都能改。

<p align="center">
  <img src="site/img/folder.jpg" width="49%" alt="打开的文件夹">
  <img src="site/img/control-center.jpg" width="49%" alt="控制中心">
</p>

<p align="center">
  <img src="site/img/screensaver-aerial.jpg" width="49%" alt="航拍屏保">
  <img src="site/img/wallpaper.jpg" width="49%" alt="个性化面板">
</p>

## 安装

不用 root，不动系统应用，随时能还原。电脑和电视连同一个网络。

### 1. 打开电视的 ADB 调试

| 电视 | 怎么打开 |
|---|---|
| **小米 / Redmi** | 设置 → 关于 → 连续按「产品型号」进入开发者模式，再到 设置 → 账号与安全，打开「ADB 调试」和「安装未知来源的应用」 |
| **TCL / 雷鸟** | 设置 → 关于 → 本机信息，快速按遥控器「上下左右」，在底部的「更多」里打开 ADB |
| **海尔 / 模卡** | 按电视机身上的信号源键，再用遥控器按「左左上右」，在工厂菜单里打开「ADB 调试开关」，别动其他选项 |
| **OPPO** | 设置 → 系统 → 关于本机 → 设备型号，按「上上右右」再按确认 |
| **其他 Android TV** | 设置 → 设备偏好设置 → 关于 → 连按「内部版本号」7 次，再在开发者选项里打开「USB 调试」 |

电视的 IP 在「设置 → 网络」里。第一次连接时，电视会问是否允许调试，选「始终允许」。

### 2. 安装

**让 Agent 装（推荐）**：把下面这段发给能在电脑上运行命令的 Agent（WorkBuddy、豆包电脑版、千问电脑版、Trae、Claude Code、Codex 等）：

```text
请帮我把开源电视桌面 Aerial 装到我的电视上，并设为开机进入的桌面。
电视 IP：<电视IP>
保持 ADB 常开：要

安装说明在这里：https://aerial.qust.me/agent.md
（打不开时用 https://raw.githubusercontent.com/pengchujin/aerial-tv/main/site/agent.md ）

请先完整读完安装说明再动手，然后在我的电脑上亲自运行命令，不要只把步骤告诉我。需要我在电视上操作时提醒我。
最后按说明里的「验收」，把那几条命令的原始输出贴给我。
```

手机 App 和网页版聊天装不了。Agent 说装好了，看它贴出的输出里有没有 `app.orchard.tv`。

**一行命令（macOS / Linux）**：

```bash
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=192.168.1.20 bash
```

**手动**：下载 [aerial-standard.apk](https://aerial.qust.me/download/aerial-standard.apk)，然后：

```bash
adb connect 192.168.1.20:5555
adb install -r aerial-standard.apk
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell am start -n app.orchard.tv/.MainActivity
```

Windows 用 Agent 装，或照[安装说明](site/agent.md)里的 Windows 步骤。

**升级**：1.32 起，有新版本时控制中心会出现「有新版本」，点进去选「下载并安装」，在电视自己的安装界面确认（小米、TCL 上验证过），装完回到 Aerial，设置都保留。电视不让 App 安装应用时，升级就是再运行一次安装命令：升级页上有二维码，手机扫码能直接复制这台电视的命令，或者交给 Agent。1.31 及以前没有这个按钮，这一次还是要再运行一次安装命令。

### 还原

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=192.168.1.20 bash -s restore
```

最后加 `--uninstall`（`bash -s restore --uninstall`）会顺便卸载 Aerial。不能运行 bash（Windows）时，逐条的 adb 命令见[安装说明的「还原」](site/agent.md#还原)。

### 常见问题

- **只用 U 盘装了 APK**：1.32 起「我的应用」里有 Aerial 的图标，打开后会提示怎么设为主屏幕。只装 APK 时按主页键、开机和待机后还是原桌面，要用上面的命令或 Agent 再设置一次（会覆盖安装，不丢设置）。
- **TCL 弹出「多屏互动」**：按遥控器「右」再按确认，选「同意」。
- **创维 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`**：先运行 `adb shell settings put global verifier_verify_adb_installs 0` 再装（用户反馈，详见[安装说明](site/agent.md#常见情况)）。
- **提示不允许安装桌面类 App**：命令最后加 `-s guard`，或下载 [aerial-guard.apk](https://aerial.qust.me/download/aerial-guard.apk)。
- **`KEEP_ADB=1` 是什么**：小米待机后会关掉 ADB，加上它 Aerial 会重新打开，方便以后升级。
- **原来的桌面和会员还在吗**：都在，原来的桌面在 App 列表里。开机广告是固件播的，去不掉。
- **不支持**：LG、三星、华为智慧屏，以及 Android 4.4 及更老的系统。

## 它怎么接管桌面

国产电视大多只认自家桌面，待机时还会清理第三方 App。所以 Aerial 用一个无障碍服务，原桌面一出现就切回来（只看哪个窗口在前台，不读屏幕内容）；被清理后再靠通知监听让系统把它拉起来（不读通知）。原生 Android TV 上不需要这些，直接设成默认桌面就行。

## 已验证的电视

| 机型 | 系统 | 情况 |
|---|---|---|
| 小米 MiTV-MFTR0 / Redmi X65 2026 | Android 14 | 日常使用（[笔记](docs/devices/xiaomi-mitv-mftr0.md)） |
| 小米 MiTV-MFFU0 | Android 14 | 安装、开机自启（[笔记](docs/devices/xiaomi-mitv-mffu0.md)） |
| 小米 MiTV-MTSU0 / S65 Mini LED 2026 至尊版 | Android 14 | 安装、开机自启（[笔记](docs/devices/xiaomi-mitv-mtsu0.md)） |
| 小米 MiTV-ASTP0 / NSOP0 | Android 9 | 用户验证（[#48](https://github.com/pengchujin/aerial-tv/issues/48)、[#65](https://github.com/pengchujin/aerial-tv/issues/65)） |
| 小米 MiTV-MFTP0 | Android 9 | 安装、开机自启；升级后守护会掉（[#72](https://github.com/pengchujin/aerial-tv/issues/72)） |
| TCL MT5879 国行 | Android 11 | 日常使用（[笔记](docs/devices/tcl-mt5879-cn.md)） |
| TCL 75V2 国行（m7221） | Android 8 | 免 root：改固件属性开机直达 Aerial，不用无障碍和通知监听（[笔记](docs/devices/tcl-m7221-cn.md)） |
| 索尼 A80J 国行 / BRAVIA VH1 | Android 10 / 12 | 用户验证（[#16](https://github.com/pengchujin/aerial-tv/issues/16)、[#2](https://github.com/pengchujin/aerial-tv/issues/2)） |
| 索尼 BRAVIA 4K VH2 国行 | Android 12 | 安装、主页键、App 内容（Emby 的继续观看和推荐） |
| 创维酷开 K3 Pro | Android 11 | 用户验证（[#43](https://github.com/pengchujin/aerial-tv/issues/43)） |
| 康佳 KKTV U86V9 | Android 9 | 用户验证：主页键、开机自启、信号源；亮度调不了（[笔记](docs/devices/konka-kktv-u86v9.md)） |
| OPPO K9 | Android 9 | 要改包名才能装（[笔记](docs/devices/oppo-coloros-tv.md)） |
| 康佳 2861、PPTV K55U27、极米 m7642、腾讯极光盒子 7S | Android 5–9 | 用户验证，各有小问题，见 [Issues](https://github.com/pengchujin/aerial-tv/issues) |
| 雷鸟 55S595C Ultra | Android 11 | 能装，但接管不了 HOME（[#52](https://github.com/pengchujin/aerial-tv/issues/52)） |

能装但开机进不了 Aerial 的：Vidda、海信、雷鸟鹤 6 2025 Ultra。你的电视能用，或者有问题，欢迎用[机型适配模板](https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml)告诉我们，附上诊断信息：电脑上运行 `curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<电视IP> bash`（只读，不改电视），或者在手机配置页最下面「反馈问题」里复制。诊断信息不含账号、IP、Wi-Fi 名称和 App 列表。

## 开发

需要 JDK 17、Android SDK 36。

```bash
./gradlew assembleStandardRelease assembleGuardRelease
BUILD=1 KEEP_ADB=1 TV=<电视IP> tools/install.sh
```

代码结构见 [ARCHITECTURE.md](docs/ARCHITECTURE.md)，现状和规则见 [HANDOFF.md](docs/HANDOFF.md)，各机型笔记在 [docs/devices/](docs/devices/)。

## 许可

[MIT](LICENSE)。Inter 字体使用 [SIL Open Font License](FONT-LICENSE-OFL.txt)。天气用的国内城市表来自 [GeoNames](https://www.geonames.org)（CC BY 4.0），天气数据来自 [Open-Meteo](https://open-meteo.com)。

Aerial 是独立的开源项目，与 Apple、小米、TCL 等公司无关。航拍影片版权归 Apple，由 Apple 的服务器直接播放；每日精选图片来自必应。
