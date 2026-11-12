# 大眼橙 OBE_R3Ultra 高亮版（投影仪）

- Android 11（SDK 30），MediaTek **MT5879**（`ro.product.device=mt5879_cd`，指纹 `MediaTek/mt5879_cd/mt5879:11/RP1A.200720.011/2.0.18:user/dev-keys`）。和 [TCL MT5879 国行](tcl-mt5879-cn.md) 是同一颗芯片，但那台的安装校验链在这台上不存在。
- **纯 32 位**：`ro.product.cpu.abilist = armeabi-v7a,armeabi`。装 APK 只能挑 32 位包，arm64-only 的包会 `INSTALL_FAILED_NO_MATCHING_ABIS`（例如哔哩哔哩 HD 2.0.1）。
- 1920×1080，**213dpi**；WebView 83.0.4103.120。
- UPnP 里的 friendlyName 是 `OBE_R3Ultra 高亮版(OBE)079`，厂商 Huanglong Technologies；`ro.product.brand` 是 `MediaTek`，`ro.product.manufacturer` 是 `OBE`。
- 原桌面是优酷/CIBN 那套：按主页键后前台窗口先是 `com.cibn.tv/com.youku.tv.home.activity.HomeActivity`。
- 一点小矛盾：`ro.board.platform = mt5879` 但 `ro.hardware = mt9679`，安全补丁 `2024-08-01`。
- 固件自带的屏保是 `com.obe.dreams/com.obe.dreams.service.ObeDreamService`，装上 Aerial 后
  `secure screensaver_components` 被换成 `app.orchard.tv/app.orchard.tv.dream.AerialDream`。
- 固件里有开机自启应用的设置键，但都是空的：`system boot_app_mode = null`、`system boot_app_componentname = null`。
  也就是说这台**支持**"由固件在开机时启动指定组件"这条路（[FFALCON 笔记](ffalcon-t966d5.md) 用的就是它），
  需要时可以拿它做兜底。
- `cmd role add-role-holder` 生效，但 `cmd role get-role-holders` 这个构建不支持
  （`Unknown command: get-role-holders`）——和[#551](https://github.com/pengchujin/aerial-tv/issues/551) 里 XGIMI 的 Android 11 是同一种情况，
  `tools/report.sh` 已经在处理了，只是提醒一句别把 `role holder: Unknown command` 当成失败。
- 日志里有 `COMPAT FOR PKG app.orchard.tv: {213dpi always-compat}`：Aerial 在这台上走的是 213dpi 的兼容缩放。
- 装的 flavor：`standard`（1.65）。`adb install` 直接可用，没有被系统校验拦。

## 打开 ADB：开发者选项默认没有，但 USB 调试一开就监听网络

厂家给的答复是"ADB 打不开"。实际是菜单入口被藏了，服务本身是好的：

1. `设置 → 关于/系统信息 → 连点「版本号」7 次`，出现「开发者选项」。
2. 进去后**只有「USB 调试」这一项，没有「无线调试」**。
3. 打开「USB 调试」后，`adbd` **立刻监听 TCP 5555**，不需要配对码、不需要数据线：

   ```bash
   adb connect <IP>:5555      # 首次需在电视上点「允许」
   ```

所以这台属于"Android 11 但走的是 pre-11 的网络 adb 路径"。如果遇到同型号开不了 ADB，先别放弃——找开发者选项里的 USB 调试，而不是找无线调试。

## 主页键接管：会被固件先抢一次，约 13 秒后回来

`cmd role add-role-holder` 生效之后 `resolve-activity` 已经报 `app.orchard.tv/.MainActivity` 且 `isDefault=true`，
但按主页键后前台窗口仍先落到 `com.cibn.tv`，随后被 HomeGuard 切回：

```bash
adb shell input keyevent KEYCODE_HOME
adb shell "sleep 8; dumpsys window | grep mCurrentFocus"
#   mCurrentFocus=Window{... u0 com.cibn.tv/com.youku.tv.home.activity.HomeActivity}
adb shell "sleep 5; dumpsys window | grep mCurrentFocus"
#   mCurrentFocus=Window{... u0 app.orchard.tv/app.orchard.tv.MainActivity}     ← 约 13 秒
```

`com.cibn.tv` 并不在 `query-activities -a MAIN -c HOME` 的候选里（那三个是 `com.ktcp.launcher`、`app.orchard.tv`、`FallbackHome`），
所以这不是"两个 launcher 抢 HOME"，而是**固件自己拦下主页键去开优酷桌面**，再由无障碍守护把 Aerial 拉回前台。

- 无障碍服务：`enabled_accessibility_services` 追加
  `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`，原来的 `com.ktcp.aiagent`（极光语音）保留不动；`dumpsys accessibility` 里能数到 1 个。
- **开机实测直达 Aerial**（`adb reboot` 后 `mCurrentFocus` 就是 `app.orchard.tv`），不受那 13 秒影响。
- `KEEP_ADB=1` 已开，固件关掉 USB 调试后 Aerial 会自己再打开。

## ADB 没开时的安装通道

这台预装了沙发管家（`com.shafa.market`），它的远程控制网页监听 **8899**：

```bash
curl -F "file=@Aerial.apk" "http://<IP>:8899/?fallback=1"   # 返回「上传成功」，电视上确认安装
```

不依赖 ADB（Aerial 和自测工具最早就是这么推上去的）。注意它的截图/推送功能**内部调 adb**，
ADB 没开时 `/interface?mod=screencap` 会回 `{"success":false,"message":"adberror"}`。

## 调试这台机器时的两个坑（和固件有关，不是 Aerial 的问题）

1. `uiautomator dump` 在 Aerial 首页（动态壁纸）和带 Lottie 动画的界面上必报
   `ERROR: could not get idle state`，关掉三个 `*_animation_scale` 也没用。
2. `screencap` 抓动态壁纸/视频画布是**全黑**（PNG 1.7 MB，转 JPEG 只剩 23 KB）。换成静态界面就正常。

替代方案：`adb shell screencap` + 本地 OCR（macOS Vision / tesseract），UI 坐标靠 OCR 的 bounding box 推算。

## 没验证

- HDMI 信号源磁贴与 CEC（这台没接 HDMI 设备测）。
- 亮度、音量这两个控制中心滑块的映射。
- 待机唤醒后是否也直达 Aerial（只测了冷启动/重启）。
- `guard` flavor（standard 装得上，就没试 guard）。

## OBE_C3Ultra（m7642，Android 9，#1482）：应用内更新不弹安装框

大眼橙 C3 Ultra（报告人 Agent 反编译 + logcat）：系统 `PackageInstaller` 的 `InstallStart.onCreate` 第 111 行厂商加了统计，无条件 `intent.getData().toString()`；PackageInstaller 会话的确认页没有 data，安装器 200 ms 内 NPE 崩掉。Aerial 回到前台后当成「看过确认页又退出」，不再走 ACTION_VIEW 兜底，于是点「下载并安装」只闪一下。2026-11-12 main：确认页盖住首页后 1.5 秒内首页就回来、再过 1.5 秒会话仍没有答复，就放弃会话、用 ACTION_VIEW（带 content:// data）打开安装器（单测 `UpdateSessionTest`，没在这台上验证）。

