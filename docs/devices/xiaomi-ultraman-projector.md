# 小米 Ultraman 系列投影仪（Xiaomi Ultraman Series Projector）

- 型号：`ro.product.model` = `Xiaomi_Ultraman_Series_Projector`，`ro.product.board` = `t3`（Amlogic 方案）。
- **Android 9（SDK 28）**，32 位 ARM，约 2.6 GB 内存。
- 零售型号未确认（`ro.product.model` 只给了系列名），固件 build 号未记录。
- MAC OUI `dc:a3:a2` 属于 Fengmi(Beijing)technology，与峰米桌面一致。

## 桌面是峰米的，不是小米的

**原桌面：`com.tvfengmi.launcher/com.gala.video.app.epg.HomeActivity`**（系统包在 `/system/priv-app/GitvVideo/GitvVideo.apk`）。

**不是** `com.mitv.tvhome`（PatchWall），所以 [xiaomi-mitv-mftr0.md](xiaomi-mitv-mftr0.md)、
[xiaomi-mitv-mffu0.md](xiaomi-mitv-mffu0.md)、[xiaomi-mitv-mftp0.md](xiaomi-mitv-mftp0.md) 的结论只能参考，不能照搬。
`docs/devices/` 里原本也没有这一台。

固件同样锁死 HOME：

```
$ adb shell cmd role add-role-holder --user 0 android.app.role.HOME app.orchard.tv
cmd: Can't find service: role          # Android 9 没有 role 服务

$ adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity
java.lang.IllegalArgumentException: Component ComponentInfo{app.orchard.tv/app.orchard.tv.MainActivity} cannot be home on user 0
```

解决办法和小米一样：**靠 Aerial 桌面守护（无障碍）接管**，不要停用原桌面。

- 固件接受 `settings put secure enabled_accessibility_services`（写进去之后 `dumpsys accessibility` 里能看到
  `Service[label=Aerial 桌面守护]`）。这是绕开 `set-home-activity` 失败的唯一路径。
- `tools/install.sh` 在这台上的表现与小米一致：设完默认桌面仍解析到 `com.tvfengmi.launcher`，
  于是转去开桌面守护，最后输出「Home Guard is running」。

## 安装与接管（2026-10-22 实测，Aerial 1.31 / versionCode 33）

- 装的 flavor：`standard`。`adb install -r` 直接成功，**不需要 guard 版，也不需要 TCL 的多屏互动推送**。
- 授予的权限：`WRITE_SECURE_SETTINGS`、`ACCESS_FINE_LOCATION`，
  `appops set SYSTEM_ALERT_WINDOW allow`、`appops set WRITE_SETTINGS allow`。
- `appops set app.orchard.tv APP_AUTO_START` / `APP_ASSOC_START` 报
  `Error: Unknown operation string` —— 这两个是 TCL 专属 op，其它固件报错属正常，忽略。
- 屏保：原本是 `com.android.deskclock/com.android.deskclock.Screensaver`，`screensaver_enabled=1`，
  标准 Dream 通道可用，已改成 `app.orchard.tv/app.orchard.tv.dream.AerialDream`。
  （与小米相反：小米 `screensaver_enabled=0` 且不能改 AerialDream，会黑屏进待机。）
- `KEEP_ADB=1` 生效，重启后 `settings get global adb_enabled` 仍是 `1`。

## 桌面守护与开机自启（实测）

- 注入 `input keyevent KEYCODE_HOME` 后前台 = `app.orchard.tv/.MainActivity`。
- 从 `com.tvfengmi.launcher` 切走，约 4 秒后守护把 Aerial 拉回前台；切到别的 App 也是 4–6 秒。
- `adb reboot` 后约 47 秒，`sys.boot_completed=1` 时前台已经是 `app.orchard.tv/.MainActivity`，
  中间没有经过原桌面。
- `pm list packages -d` 为空：没有停用或卸载任何系统应用，原桌面 `com.tvfengmi.launcher` 完好。

## 通知监听绑不上（与 MFTP0 一致）

`cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener` **无报错，但没生效**：

```
$ adb shell dumpsys notification | grep RevivalListener
（空）
```

与 [xiaomi-mitv-mftp0.md](xiaomi-mitv-mftp0.md) 记录的 Android 9 小米机型完全一致。
后果是 Aerial 被强停后没有任何东西能自动复活，只能手动：

```bash
adb shell am force-stop app.orchard.tv
sleep 3
adb shell am start -W -n app.orchard.tv/.MainActivity
```

1.21 起 Aerial 的同步适配器会兜底，约 30–60 秒后拉回（**这一条本机未验证**，见下）。

**未验证**：待机/唤醒没有单独测。已知 Android 9 + 通知监听绑不上的组合下，
待机后是否还能回来、Aerial 是否被强停，在这台上没有数据。
1.20 及更早的版本在这类设备上待机后回不来（见 MFTP0 笔记），本机装的是 1.31。

## ADB 端口

ADB 调试打开前，这台电视上**没有任何 adbd 端口**。全端口扫过（1–65535），
常驻开放的是下面这些，其中没有 5555：

| 端口 | 是什么 |
| --- | --- |
| 25 / 110 / 143 | 邮件服务 |
| 6000 | `TooTallNate Java-WebSocket`，投屏协议 |
| 7002 / 7100 / 7202 | 峰米自有服务 |
| 8899 | 沙发管家远程控制 |
| 9898 | 文件互传 |
| 9970 | TV Remote IME |
| 20820 | 峰米 QT 协议（`QT3` 帧头） |
| 39620 / 39621 | Linux 主机上的服务 |
| 51087 | UPnP / DLNA |

打开 ADB 调试后 5555 立即可用。

## 电视预装的「沙发管家」套件

这台出厂带沙发管家（`com.shafa.market`）和小盒精灵（`com.android.tvremoteime`），
**ADB 没开时也能用**它们往电视里装 APK。已实测的接口：

- `9970`（TV Remote IME）
  - `POST /key`，body `code=<keycode>` —— 模拟遥控器按键。常见键码（HOME 3、BACK 4、
    DPAD_UP 20、DPAD_DOWN 19、DPAD_LEFT 21、DPAD_RIGHT 22、ENTER 66）都返回 `ok`。
    **返回 `ok` 不可信**：对任意输入都回 `ok`，不能当作「操作成功」的证据。
  - `POST /keydown` / `POST /keyup` —— 长按（每 100 ms 重发 keydown）。
  - `POST /upload`，multipart 字段 `path` + `autoInstall` + `useSystem` + `file` —— 上传文件，可自动安装 APK。
  - `POST /run`（`packageName`）、`POST /runSystem`（`android.settings.SETTINGS`）、
    `POST /apps`（`system=true/false`，返回 JSON 数组，含 `packageName` / `lable` / `apkPath` / `isSysApp`）。
- `9898`（文件互传）
  - `POST /upload`，`path=安装`，文件字段名 `files-<size>` —— 电视上直接弹安装框并装上，接口返回 `OK`。
- `8899`（沙发管家远程控制）
  - 截图 `/interface?mod=screencap_web&data=null&callback=null`，**依赖 adb**：
    没有 ADB 时返回 `{"success":false,"message":"adberror"}`，页面里 `can_screencap = false` 直接隐藏了入口。

**为什么记下来**：在这类 ADB 入口找不到的设备上，这是绕过 `adb install` 的现成路径。
但**不要用 `/key` 盲按去找开发者选项** —— 没有截图就看不见焦点在哪，很容易改到无关设置。
正确顺序是先把 ADB 打开（`/key` 只能用来在已经看得到屏幕的前提下做微操）。
本机最后也是靠用户手动打开 ADB 调试才走通的官方流程。

## 其它

- 打包/安装的通用注意事项见 [tcl-mt5879-cn.md](tcl-mt5879-cn.md) 的「安装限制」一节；
  这台没有那些限制，`adb install -r` 一次就过。
- 未验证：亮度、音量、信号源、HDMI CEC、Aerial 的航拍屏保是否正常播放、天气功能。

## 亮度（峰米系统通用，2026-11-21）

峰米的投影（小明 Q3 Pro `peppa`、Q1 Pro `olaf`）只认安卓默认的 `screen_brightness=102`，写成别的值后下次待机唤醒光机不亮，卸载 Aerial 也不恢复，`adb shell settings put system screen_brightness 102` 再拔电源开机才好（#1668 #1661）。Aerial 在有 FengOS 设置面板（`cn.formovie.fengos.settings`）或有峰米桌面（`com.tvfengmi.launcher`）的机器上不显示亮度行、不读不写这个值，所以这台 Ultraman 也一样不显示亮度。Q1 Pro 没有 FengOS 设置面板，2.13 上亮度行还在，2.14 起按峰米桌面判断。
