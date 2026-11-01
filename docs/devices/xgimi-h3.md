# 极米 H3（国行）

- 销售型号 H3。`ro.product.model` = `XGIMI TV`，厂商 `Xgimi Technology Co.,Ltd`，`ro.product.device` / `ro.product.board` = `ironman`，`ro.product.name` = `Product_ys`，`ro.hardware` / `ro.board.platform` = `m7221`。
- 指纹 `Xgimi/ironman/ironman:8.0.0/OPR5.170623.014/v1.15.93:user/dev-keys`。Android 8.0.0（SDK 26），安全补丁 2017-12-01，`OPR5.170623.014`，`dev-keys`。`ro.product.cpu.abi` = `arm64-v8a`。
- 屏幕 1920×1080、240dpi、60Hz。内存 2709 MB，`ro.config.low_ram=true`。网络 Wi-Fi。
- 实测 Aerial 1.57 (69) `standard`，包名 `app.orchard.tv`。测试日期 2026-10-26。未 root、未刷机。

## 开 ADB：系统里没有入口

这台机器的设置里没有可用的开发者选项。ADB 没打开时，`adb connect <IP>:5555` 返回 `Connection refused`。

可行的做法与 [Z7X](xgimi-z7x.md) 用的是同一个工具，但安装方式不同：在手机上下载 [code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)（极米 ADB 开启器）的 APK，用极米手机 App 上传到投影仪并安装，然后打开一次。不需要 U 盘。该工具原先只在 Z7X 上验证过；这次在 H3 上同样能打开网络 ADB，随后可以正常安装应用。普通应用权限即可，不需要 root，也不进工厂模式。

第三方工具自行评估风险。Aerial 本身不依赖它，它只是这台机器打开 ADB 的入口。工具作者也写了不保证全部极米型号，这里只记录 H3 这一台的结果。

## 安装与使用

ADB 打开之后，按仓库的正常流程安装即可。这次实测装上的是 Aerial 1.57 (69) `standard`，可以正常使用。

## 开机与屏保

与 [Z7X](xgimi-z7x.md) 在 1.22 修好之后的行为一致（本次是 1.57，不是 1.19 / 1.20）：

- 重启后先显示极米桌面，几秒后自动切到 Aerial，不用按键。
- `screensaver_enabled=1` 时，屏保接管为 `app.orchard.tv/app.orchard.tv.dream.AerialDream`。

Z7X 笔记里的黑屏个例、长按 Home 和 logcat 限制，这次没有单独记录。`ro.config.low_ram=true`，但是否因此走了「流畅」效果，也没有单独确认。

## 信号源

H3 和 [Z6X 第四代 XK03U](xgimi-xk03u-android8.md) 是同一平台（`ironman` / m7221 / Android 8）。XK03U 的信号源适配（从 `GimiConfigManager` 读真实 HDMI 口、用 `com.xgimi.xhplayer/.InputActivity` 打开）按功能检测，H3 上如果也有这两样就会自动生效；还没有在 H3 上实测。

## H3S：设成默认桌面后开机黑屏（#942，未复现）

2026-10-30 一位用户报告：原版极米 H3S（系统 V6.12.61，Android 9）用 Aerial 1.70 设成默认桌面（极米桌面 `com.xgimi.home` 仍装着、启用）后重启，开机画面之后黑屏，主页键、设置键无效，ADB 5555 连不上，两次都只能刷机恢复。没有日志，原因不明。在弄清楚之前，H3S 上不要把 Aerial 设成默认桌面，只当普通 App 或靠守护接管；如果有人愿意再试，先确认 ADB 在黑屏时还能连（有线 / USB），以便还原。
