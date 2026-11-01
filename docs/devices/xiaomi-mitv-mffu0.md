# 小米 MiTV-MFFU0（codename finch）

- Android 14（SDK 34），HyperOS TV，固件 `OS4.0.11.0.UFFMATV`（build `UQ1A.240105.004.A1`），MediaTek MT9655（`ro.board.platform` 是 `mt5877`），arm64-v8a，约 4 GB 内存。
- 1920×1080，320dpi（960×540dp）。
- 原桌面：`com.mitv.tvhome/.MainActivityUserMode`（PatchWall）。备用桌面：`com.xiaomi.mitv.settings/.entry.FallbackHome`。
- 安装的 flavor：`standard`。`adb install -r` 直接成功，不需要 TCL 那套推送流程。

## 和 MiTV-MFTR0 的差异

| | MiTV-MFTR0（freeguy） | MiTV-MFFU0（finch） |
|---|---|---|
| 固件 | OS3.0.101.0.UFTMATV | OS4.0.11.0.UFFMATV |
| SoC | MT9653 | MT9655 |
| ABI | 32 位（armeabi-v7a） | 64 位（arm64-v8a），另带 armeabi-v7a |
| 分辨率 / dpi | 1920×1080 / 320 | 1920×1080 / 320 |

已知的限制（HOME 锁定、待机杀进程、ADB 被关）沿用 MFTR0 的结论，见 [xiaomi-mitv-mftr0.md](xiaomi-mitv-mftr0.md)。

## HOME 锁定

- `cmd role add-role-holder` / `cmd package set-home-activity` 都不报错，但 `resolve-activity` 仍然返回 `com.mitv.tvhome/.MainActivityUserMode`，和 MFTR0 一样。
- `install.sh` 因此转用桌面守护。装上后追加 `HomeGuardService`，原有的 `com.xiaomi.voicecontrol/com.xiaomi.voicecontrol.VoiceAccessibilityService`（小爱）留在列表里，没有被覆盖。
- 用 `adb shell input keyevent KEYCODE_HOME` 注入的 HOME 不经过无障碍，**不能用来判断守护是否生效**：同源固件的 MFTR0 上注入 HOME 会停在 PatchWall，而实体遥控器回到 Aerial（见 PR #9 的讨论）。只有实体遥控器的结果算数，这台还没测。不依赖按键的替代检查：Aerial 在前台时执行 `am start -n com.mitv.tvhome/.MainActivityUserMode`，前台应该很快回到 Aerial。

## 开机自启

- 2026-10-20 实测 `adb reboot`：约 25 秒后网络 ADB 恢复，`sys.boot_completed=1` 时前台已经是 `app.orchard.tv/.MainActivity`，没有再落到 PatchWall。
- 开机后 `settings get global adb_enabled` 仍是 `1`（装了 `KEEP_ADB=1`）。
- 未停用任何系统应用，`pm list packages -d` 为空。

## 待机与唤醒

**没有单独验证。** 只做了重启，没有做待机唤醒。MFTR0 上待机会 `killAllThirdApp`、靠 `RevivalListener` 在约 0.5 秒内复活，这条路在这台上还没测过。

## 屏保

- `screensaver_enabled=0`，`screensaver_components=null`，和 MFTR0 一样走小米自己的屏保（不是 Android 标准 Dream）。
- 所以 `install.sh` 不会改写屏保设置，Aerial 靠桌面空闲时自己启动屏保。
- 小米自己的屏保等待时间在 `system screen_saver_timeout`（有的机器是 120000，即 2 分钟，比 Aerial 原来的 5 分钟早，广告屏保会抢先）。1.14 起 Aerial 会读这个值，比它早 15 秒启动自己的屏保。
- 不要把 `screensaver_components` 改成 AerialDream：小米掐掉了标准 Dream 通道，实测会黑屏并进入待机（PR #9 评论）。

## 信号源

- `dumpsys tv_input` 注册了 `HW0`（DTMB）、`HW1`（ATV）、`HW2`（AV）、`HW5/6/7`（HDMI 1/2/3）、`HW8`（HDMI 4）。
- HW8 在这台上有对应的 `hdmi_port=4`，不像 MFTR0 那样是个没有接口的幽灵输入，因此 `DeviceProfile` 里没有对它做隐藏。电视是否真有第 4 个 HDMI 口没有确认（只看了 `TvInputHardwareManager`）。

## 设置入口

按 MFTR0 的路径应该可用，未逐项验证：

- 设置：`com.xiaomi.mitv.settings/.entry.MainActivity`
- Wi-Fi：`com.xiaomi.mitv.settings/.wifi.MitvWifiActivity`

## 安装记录

```bash
adb connect <电视IP>:5555
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=<电视IP> bash
```

输出：

```text
Installing .../aerial-standard.apk
Aerial will switch ADB debugging back on whenever the TV turns it off.
The firmware still sends Home to com.mitv.tvhome/.MainActivityUserMode; turning on Aerial Home Guard.
Home Guard is running. Press Home on the remote.
```

装的是 1.9（`versionCode=10`）。

## 另一台同型号（固件 OS3.0.115.0.UFFMATV）

2026-10-22 在第二台 finch 上重跑同一条命令，型号字符串、`ro.product.device` 都是 `MiTV-MFFU0` / `finch`，固件是 `OS3.0.115.0.UFFMATV`（不是上面的 OS4.0.11）：

- 一次装成功，`install.sh` 的结尾输出和上面一致，装的版本是 1.31。
- 验收 5 条全部通过：`versionName=1.31`、`enabled_accessibility_services` 里有 `HomeGuardService`、`dumpsys accessibility` 计数为 1、按 HOME 后前台是 `app.orchard.tv/.MainActivity`。
- 重启后开机直接进入 Aerial，实机确认正常（这次没有留 adb 采样输出）。

## 待机强停（#534，用户反编译 services.jar 并实测）

- 深睡 STR 走 `killAllThirdAppForStr()`，按包名强停，但跳过 `Settings.System` 的 `str_killbackgroud_pkg_white_list`（逗号分隔，ADB 可写，不用 root）：`adb shell settings put system str_killbackgroud_pkg_white_list app.orchard.tv`。
- 音箱模式待机走 `gotoSleepStayAliveProcess()`：不在名单就回原桌面、2 秒后 `killAllThirdApp`，这就是唤醒时闪 PatchWall。`disable_back_home_whitelist` 只认字面量 `disable`（写包名无效），设成 `disable` 后所有第三方应用待机都保活。
- 这两项是机主自己的选择，install.sh 不设。

## 别的 App 里按主页键闪 PatchWall

在小米显示器 MFFU1（Android 14，和这台同一套固件）上查的，记录和修复见 [xiaomi-mitv-mftr0.md](xiaomi-mitv-mftr0.md) 的「别的 App 里按主页键先闪 PatchWall」。
