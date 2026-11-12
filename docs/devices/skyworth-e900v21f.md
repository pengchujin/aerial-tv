# 创维 E900V21F（中国移动「移动高清盒子6」2024 版，乐家桌面）

报告人实测，维护者手上没有这台盒子。

- 硬件：Amlogic S905L3S，2 GB 内存 + 8 GB 存储，带网口。
- 系统：Android 9（API 28）。
- 固件桌面：乐家 `com.mylejia.launcher/.activity.DesktopActivity`。
- 装的 flavor：`guard`。standard 也装得上，但设不成默认桌面，Home 键也接不住（见下）。

## 打开 ADB

设置 → 账户安全 → **ADB 模式**。

部分批次没有这一项：遥控器**菜单键连按 10 次**，再按**下键**，屏幕上出现 open 字样就是打开了。

## 安装

标准流程（`install.sh`、`adb install`）可用。遇到 `INSTALL_FAILED_USER_RESTRICTED`，先关掉安装校验再装：

```bash
adb shell settings put global verifier_verify_adb_installs 0
adb install -r aerial-guard.apk
```

## HOME 接管：只有守护这一条路

三条实测，合起来说明这个固件的 HOME 不走标准解析：

1. 开机和按 Home 键时，固件按组件直接拉起乐家，不走 HOME 解析。
   `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`
   在这台盒子上永远返回 `com.android.tv.settings/.system.FallbackHome`。
2. `cmd package set-home-activity --user 0 com.ktcp.launcher.aerial/app.orchard.tv.MainActivity`
   → `java.lang.IllegalArgumentException: cannot be home on user 0`。
   framework 在把包写进 HOME 之前会先看它自己的候选列表，第三方包进不去。
3. 守护能正常绑定（`dumpsys accessibility` 里有这个服务，`eventTypes=TYPE_WINDOW_STATE_CHANGED`），
   AerialDiag 心跳也正常，但乐家在前台时从不切换，日志里没有任何 take-over。
   `dumpsys package com.mylejia.launcher` 显示它的 MainActivity 声明了
   `android.intent.category.HOME` + `DEFAULT`，本来是合法 HOME 候选；
   但第三方进程 `queryIntentActivities(HOME)` 拿不到它，`refreshStockHomes()` 里就没有乐家，
   守护也就「看不见」乐家。

这台是 Android 9，还没有 Android 11 的包可见性限制，所以挡它的不是系统的可见性过滤，
而是固件自己改过的 HOME 候选列表（和 #106、#487 那类固件同一个模式）。具体改在哪一处没有反编译确认。

**办法**：写进 `HomeGuardService.FIRMWARE_HOMES_WITHOUT_FILTER`（`com.mylejia.launcher` →
`com.mylejia.launcher.activity.DesktopActivity`）。这张表用 `getActivityInfo` 按组件查，
组件存在性查询不受 HOME intent 过滤影响，所以在这台上有效。

副作用两个，都是预期内的：

- 乐家会作为「原桌面」出现在 Aerial 的网格里（`Apps.stockLaunchers` 取的就是这张表）。
  点它直接 `startActivity` 打开 `DesktopActivity`：这台 `resolvedHome()` 不是乐家，
  `openStockLauncher` 走不到 `performGlobalAction(GLOBAL_ACTION_HOME)` 那条。
- Android 11 以上的固件如果也这样挡，`getActivityInfo` 会因为包不可见抛 `NameNotFoundException`，
  那时要往清单的 `<queries>` 里补 `<package android:name="com.mylejia.launcher" />`。
  这台是 Android 9，不需要。

## 双网段

盒子可能有线和 Wi-Fi 同时在线：光猫段 `192.168.1.x`，路由段 `192.168.3.x`。
`adb connect` 之前先确认盒子当前在哪个段（设置 → 网络，或路由器客户端列表），
连错段会一直卡在 `connecting`。Aerial 手机页和 `install.sh` 显示的是其中一个地址，两个段都要试。

## 验证状态

- 已实测（报告人，改动前的版本）：上面三条现象、守护能绑定、AerialDiag 心跳正常。
- 待实测：这张表加上 `com.mylejia.launcher` 之后的接管。要记录的是：
  1. `adb reboot` 后从屏幕亮起到 Aerial 在前台的耗时，中途乐家闪现了多久；
  2. 按 Home 键是否回到 Aerial；
  3. logcat 里守护的 take-over 日志（`adb logcat -s AerialGuard`）。

## E900V22C（p291_iptv，安卓 9，#1319）

- 报告人实测（1.86）：开机后系统把所有无障碍服务都不绑（连固件自带的 `com.cmhi.andlink` 也没有），开机 150 秒后仍是 `services:{}`。重写 `enabled_accessibility_services`、`accessibility_enabled` 0→1、`pm disable-user` 再 `enable` 守护组件都绑不回来。
- 只有整包替换（`adb install -r` 同一个 APK，`ACTION_PACKAGE_REPLACED`）会让系统马上重新绑定全部无障碍服务。
- 所以这台把 Aerial（standard）设成默认桌面用，不靠守护；开机时乐家、当贝抢前台挡不住，掉了用电脑重装一次恢复。
