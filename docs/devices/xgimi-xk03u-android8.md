# 极米 Z6X 第四代（XK03U / ironman / MStar m7221）信号源

- Android 8.0 / SDK 26，固件 `v1.1.21`；原生播放器 `com.xgimi.xhplayer` 1.4.2。
- 实机验证日期：2026-10-26。

## 实现

此固件没有注册 `TvInputService`，`TvInputManager` 因而列不出输入；MStar 通用输入列表又包含本机不存在的 HDMI 口和调谐器。Aerial 改为读取 `com.xgimi.clients.GimiConfigManager` 的 `global` 产品配置，只从真实配置中生成 HDMI 磁贴。

连接状态由 `TvCommonManager.GetInputSourceStatus()` 读取，并按运行时 `TvOsType.EnumInputSource` 的枚举序号对应状态数组。点击 HDMI 磁贴时，直接启动已导出的 `com.xgimi.xhplayer/.InputActivity`，action 为 `com.xgimi.action.hdmiPlayer`，extra `launcher_start_source` 传入 `HDMI1`。

适配仅在 Android 9 之前、播放器 Activity 可用且产品配置可读取时生效；异常或接口不可用时返回空列表，由 Aerial 原有的其他输入源探测路径继续处理。没有打包厂商 JAR 或新增权限。

## 验证范围

- 普通应用 UID，无额外权限：读取产品配置和输入连接状态成功。
- Aerial 首页及控制中心显示 HDMI1；点击后进入极米原生播放器。
- 测试时没有有效 HDMI 输入信号；未验证视频、声音、HDCP 或 CEC。
- 本适配只覆盖 Android 8 上实测的播放器协议；Android 9 及以上机型不在验证范围内。

## 与另一条极米适配路径的区别

[PR #437](https://github.com/pengchujin/aerial-tv/pull/437) 面向 Z7X / GMUI Android 9：系统能列出输入，但标准 passthrough Intent 无处理器，因此改为通过 `com.xgimi.newsettings.SettingService` 打开极米信号源页。本机 XK03U / Android 8 没有 TV 输入服务，使用产品配置生成端口并直接启动原生播放器；两条路径针对不同固件，不能互相替代。

## 桌面、守护与后台清理（2026-11-06，Aerial 1.86）

另一台同型号机器（`Product_zm01` / `XGIMI_TV` / `ironman` / m7221），固件 `v12.15.214`，Android 8.0 / SDK 26，`ro.config.low_ram=true`，内存 1.7 GB（`dumpsys meminfo` 状态 `critical`），ADB 5555 已开。

### 默认桌面

- Android 8 没有 `cmd role`（报 `Can't find service: role`），只能用 `cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity`：返回 `Success`，**当次开机有效**。
- **每次开机固件都会把 `com.xgimi.home/...HomeActivity` 重新写成首选桌面**：`dumpsys package` 的 `Preferred Activities User 0` 里，Aerial 是 `mAlways=false`，极米是 `mAlways=true`。开机能看到 Aerial，靠的是 Aerial 自己启动后守护把它拉到前台，不是默认桌面。
- 不带 `-c android.intent.category.DEFAULT` 的 `resolve-activity` 会返回 `android/com.android.internal.app.ResolverActivity`：极米的 `com.android.newsettings/.FallbackHome` 只声明 HOME、没有 DEFAULT，查询集合变成三个。带 DEFAULT 的查询才是真实结果，系统按 Home 键时也用 DEFAULT 集合。
- 守护被杀、首选桌面又被改回极米时，Home 键会停在极米桌面：重跑一次 `set-home-activity`，或从应用列表打开一次 Aerial 即可恢复。
- 极米 Android 9 上的「双 HOME 首选记录」办法（[xgimi-z7x.md](xgimi-z7x.md)）没有在 Android 8 上试；`set-home-activity` 只写 `HOME+DEFAULT` 一条记录，开机仍会被固件改回。

### 后台清理与守护

- 切到别的 App 后，Aerial 进程约 **15–45 秒**被整进程杀掉（`dumpsys activity services` 里的 `HomeGuardService` ServiceRecord 消失）。此时守护仍是无障碍服务、进程 `adj=100`；Doze 白名单、`am set-inactive app.orchard.tv false`、`appops set app.orchard.tv 63 allow`（`RUN_IN_BACKGROUND`）都加上也一样。
- 杀进程后 `settings get secure enabled_accessibility_services` 里**仍然有守护**，但系统不会重新绑定；`RevivalSync` 只调用 `GuardSetup.ensureEnabled()`，它看到设置里已有此项就返回 `false`，于是不会走到 `rebindIfStalled()`。结果：设置里有、`dumpsys activity services` 里没有 `ServiceRecord`，`pidof app.orchard.tv` 只剩一个空的缓存进程。这是本项目可以改进的一处：守护被固件停掉、设置仍在时不会重绑。**main 上已改（#1326，下个版本）**：同步框架那条路看到「设置里有、没绑定」也会重绑，每分钟最多一次；没在这台上验证。
- `ro.config.low_ram=true` 上第三方通知监听不会真正绑定（`dumpsys notification` 的 `Live notification listeners (0)`）；同步复活只在系统设置被写入时触发，极米的清理不写设置，也指望不上。
- 结果：只有 Aerial 刚被打开、守护还活着的那段时间 Home 键有效。

### 开机「应用优化」

- 开机时固件的 `com.xgimi.upgrade/com.xgimi.forceapp.AppBootUpdateActivity` 会显示「应用优化中 N/16」，在检测到应用变化时运行（装完 Aerial 的首次开机是 9/16，约 2 分钟）。它检查/更新预装应用并让系统编译（`AppBootUpdate_have_apk`、`AppBootUpdate_sys_no_install`、`AppBootUpdate_versionCode_no_big_delete` 等判定）。
- 这段时间 Aerial 在后台，按上面 15–45 秒的清理节奏会被杀掉；优化结束、画面回到固件桌面时，如果首选桌面已改回极米，就会停在极米。优化期间守护还活着时，日志里能看到优化结束后把 Aerial 从极米桌面拉回。
- 这不是优化本身改了桌面，是它期间 Aerial 被杀、暴露了「开机重设首选桌面」的问题。不建议为此停用极米的系统组件。

### 安装时遇到的两处 Android 8 差异

- `cmd notification allow_listener ...` 报 `No shell command implementation.`：改用 `settings get/put secure enabled_notification_listeners`，原有的监听项要保留。
- `cmd package compile -m speed -f app.orchard.tv` 报 `java.lang.RuntimeException: Can't create handler inside thread that has not called Looper.prepare()`（Android 8 的 `PackageDexOptimizer$ForcedUpdatePackageDexOptimizer`）；去掉 `-f` 重跑返回 `Success`，`dumpsys package dexopt` 仍是 `compilation_filter=speed`。

### 对应办法

- 开机落到极米、或 Home 键回极米：重跑 `cmd package set-home-activity`，或从应用列表打开一次 Aerial（守护会重新开始工作）。
- 需要长期稳定时，只能在每次开机后重写首选桌面；目前没有能在本机自动完成这件事的路径（通知监听在 low_ram 上不可用，同步复活不触发重绑）。
