# 坚果投影 S621（MediaTek mt5873，Android 13）

用户在 #986 实测整理（2026-10-30，Aerial 1.69 / 1.72 standard，未 root，SELinux Permissive，固件 `S621-userdebug 13 TP1A.220624.021.A1 1.1.31.19 dev-keys`）。维护者手上没有这台。

## 守护

坚果固件不让第三方写 `enabled_accessibility_services`（`SettingsProvider: mutateSecureSetting disable modify enabled_accessibility_services`）。#793 里找到的 `com.jmgo.hippo` 广播能让固件启用守护，原有 JMGO 无障碍服务保留。

遥控器「关机」其实只灭屏（`/proc/uptime` 一直累加），关机时 `com.jmgo.hippo` 结束所有第三方应用（`am_kill … stop app.orchard.tv due to from pid <hippo>`）。再开机后守护还在启用列表里，但不再绑定；Aerial 自己的修复写设置被拒。通过 hippo 广播先关后开一次才会重新绑定。

## 默认桌面改不了

`cmd role add-role-holder` 和 `set-home-activity` 都返回成功，但一按主页键就被改回。`services.jar` 里的 `com.android.server.wm.JmgoWmHook` 从 `/vendor/etc/launcher.cfg`（只读）读桌面名单，每次 `startHomeHook` 都 `clearPackagePreferredActivities` 后把坚果桌面设回首选，并把 HOME intent 的组件强制改成它。

## 按组件启动的 Aerial 不可见

和 #864、#839 的创维 titanite 同一类：守护或开机广播按组件启动 Aerial，进的是 standard 任务，SurfaceFlinger 里任务图层一直隐藏（`flags=0x00000001`），InputDispatcher 回 `NOT_VISIBLE`，约 30 秒一轮 ANR、被杀、再拉起。以 HOME 方式启动（`am start -a MAIN -c HOME -p app.orchard.tv`）进的是 `type=home` 任务，显示和焦点正常，之后守护的拉起会复用这个任务。

wzpan 的 `skyworth-home-task` 分支在「Aerial 是系统默认桌面」时改用 HOME 方式拉起；坚果上 Aerial 不是系统默认桌面（被 JmgoWmHook 改回），所以那个条件覆盖不到这台。

## 用户的变通办法（不在 Aerial 里）

报告人自己写了一个辅助应用：开机、通知监听被重新绑定（关机再开机后唯一可靠的入口）、亮屏时，检查守护有没有绑定（没绑就用 hippo 广播关开一次），前台是坚果桌面或 Aerial 时以 HOME 方式启动 Aerial。实测 adb 重启、遥控器关机再开机、断电上电都直接进 Aerial。依赖坚果未公开的广播，固件升级后可能变。

## 冷启动顺序

用户解锁前坚果桌面不支持直接启动，钩子找不到它，系统按正常规则以 HOME 方式启动 Aerial，Aerial 再按 `handOverToFirmwareFallbackHome()` 让给坚果的 FallbackHome；解锁后钩子把坚果桌面设回首选并启动。
