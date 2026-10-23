# 小米 MiTV-MFTR0（codename freeguy）

- Android 14（SDK 34），HyperOS TV，固件 OS3.0.101.0.UFTMATV，MediaTek MT9653，32 位 ARM（armeabi-v7a），4 GB 内存。
- 1920×1080，320dpi（960×540dp），只有一个 60Hz 显示模式。GPU 很弱，见 ARCHITECTURE.md 的性能约束。1.15 导航帧耗时：第一排中位数 14ms、网格 18ms、第一排与网格之间切换的 90 分位 20ms（2026-10-21）。
- 原桌面：`com.mitv.tvhome/.MainActivityUserMode`（PatchWall）。备用桌面：`com.xiaomi.mitv.settings/.entry.FallbackHome`（priority -1000）。
- 安装的 flavor：`standard`。普通的 `adb install` 就能装。
- 同平台的零售型号：**Redmi X65 2026** 也上报 `MiTV-MFTR0`。用户在 OS3.0.107.0.UFTMATV 上验证过一键安装和重启后自动进入 Aerial（GitHub #3）。

## HOME 锁定

- `cmd role add-role-holder android.app.role.HOME app.orchard.tv` 和 `cmd package set-home-activity` 都返回成功，但 `resolve-activity` 仍然返回 `com.mitv.tvhome`。
- 原因：`com.android.server.pm.MitvSettings.isThirdpartyHomeAllowed(pkg)` → `isAppInWhitelistWithFlag(pkg, 4)`。白名单来自 `/data/system/mitv_features.xml` 和 `persist.appcontrol_*` 属性，只有系统进程能写。
- **不要停用 `com.mitv.tvhome`**：停用后 HOME 解析到 FallbackHome，而 FallbackHome 也不认第三方 HOME，会一直黑屏并刷日志「User unlocked but no home」。实测过，之后已立即 `pm enable` 还原。
- 解决办法：桌面守护（无障碍）。原桌面一到前台就切回 Aerial；按 HOME 时实测从按下到 Aerial 显示约 0.5 秒，中间会闪一下原桌面。
- 1.11 之前守护服务一直开着按键过滤（`flagRequestFilterKeyEvents`），在系统之前拦 HOME，不闪原桌面。但这样所有按键都要先绕道 Aerial，用户反馈云视听小电视里按一次遥控器移动两格，卸载 Aerial 就好。现在只在用户从「桌面」磁贴主动进入原桌面时才开过滤（`HomeGuardService.filterKeys`），回到 Aerial 或打开别的 App 就关掉。用 `dumpsys accessibility` 看：平时没有 `A11yInputFilter`，在原桌面里是 `[KeyboardInterceptor]`。
- `adb shell input keyevent KEYCODE_HOME` 注入的按键不经过无障碍的按键过滤，测「原桌面里按 HOME 回到 Aerial」必须用实体遥控器。
- 原桌面的 Activity 要求签名权限 `com.mitv.tvhome.permission.HOME_STATE`，App 和 adb shell 都启动不了。「桌面」磁贴改用无障碍的 `performGlobalAction(GLOBAL_ACTION_HOME)` 打开它，并暂时放行（`HomeGuardService.openStockLauncher`）；从原桌面打开别的 App 后放行结束。
- 开 ADB：先在 设置 → 关于 → 产品型号 上连续按确认键，提示「您已处于开发者模式」后，设置 → 账号与安全 里才有「ADB 调试」。

## 待机会强制停止第三方 App

- 待机（遥控器关机，实际是待机；`/proc/uptime` 不会归零）时，`MitvActivityManagerService.killAllThirdApp` → `killAllThirdAppSkipFgPkg` 对所有非系统 App 执行 `forceStopPackage`。
- 豁免条件：系统应用、`MitvConfigParser.isAppInWhiteList(0x8000)`（云端规则）；以及 STR 模式（`persist.sys.system_str == 1`，这台为空）下的 `strKillWhiteList`（来自 `Settings.System str_killbackgroud_pkg_white_list`，只在开机时读取）。在这台电视上，都没有可用的正规途径。
- 后果：Android 对被强制停止的 App 会移除它的无障碍服务 → 唤醒后守护失效。
- 解决办法：`RevivalListener`（通知监听）。系统会在绑定断开后主动重绑，日志里可以看到 `notification listener binding died` 然后 `binding:`，约 0.5 秒。复活后 `GuardSetup` 用 `WRITE_SECURE_SETTINGS` 重新写入守护服务。已实测：待机再唤醒后直接是 Aerial。

## 开机时 HDMI-CEC 自动切信号源（1.15 修复）

- 用户反馈：接了 Apple TV（CEC），开机时电视显示「正在连接 HDMI」，随即被拉回 Aerial。
- 原因：待机唤醒后守护服务被复活，`onServiceConnected` 立刻接管桌面，不看前台是什么。复现方法：`am force-stop app.orchard.tv` 后马上用 `EXTSRC_PLAY`（`input` 23）打开 HDMI 1，日志里 `ExternalSourceActivity` 比守护绑定早 130ms 启动，接着被 Aerial 盖住。
- 修复：复活、开机、亮屏这三种情况都等 1.5 秒再接管，期间如果有原桌面以外的窗口到了前台（信号源、别的 App），就不动它。`BootReceiver` 在守护已运行时不再直接打开 Aerial。
- 注意：覆盖安装后 2 分钟内，守护不会因为「被复活」而接管（防止打断正在看的内容），测这条路径要等 2 分钟。

## 开机自动播放信号源

- 小米桌面开机时会自动播放上一次用过的外接信号源（日志里 `com.mitv.tvhome` 打印 `oneTouchPlay = false, autoPlay = true`，然后启动 `ExternalSourceActivity`）。2026-10-21 实测：重启前切过 HDMI 1，重启后前台就是 HDMI 1。
- 1.14 及以前，守护开机后不看前台直接拉回 Aerial，会盖掉这个行为；1.15 起前台是信号源时不动，和 CEC 的处理一致。想开机总是进 Aerial，要在小米的设置里关掉这个功能；想开机进指定的信号源，也可以用 Aerial 的「开机时打开」。

## ADB 会被关闭

- 唤醒后 `global adb_enabled` 会被系统改成 0（`dumpsys settings` 的历史里可以看到）。
- 开了 `keep_adb`（`KEEP_ADB=1 tools/install.sh`，或用 `ConfigReceiver` 的广播）后，守护服务监听这个设置，立刻写回 1。实测唤醒后 3 秒内 ADB 可连。

## 屏保

- 不用 Android 标准的 Dream（`screensaver_enabled=0`）。由小米的 PowerManagerService 在空闲 `system screen_saver_timeout`（480000 = 8 分钟）后发出 `com.duokan.intent.action.show.screensaver`，交给 `com.xiaomi.mitv.hyper.screensaver` 显示。这个广播受保护，shell 发不出去。
- 解决办法：Aerial 在桌面空闲 5 分钟时启动自己的屏保，同时加 `FLAG_KEEP_SCREEN_ON`，小米的屏保就不会再触发。停在别的 App 里空闲时，小米屏保仍可能出现。

## 其他

- 背光：`Settings.Global picture_backlight`（0–100），改了以后 `tvmenusettingsservice` 会立即应用。需要 `WRITE_SECURE_SETTINGS`。
- 音量：普通的 `adjustStreamVolume` 就能生效。
- 信号源：HW5/6/7 = HDMI 1/2/3（HDMI 2 是 eARC），HW8 是不存在的第 4 个口（已隐藏）；HW2 = AV，HW1 = ATV，HW0 = DTMB。切换用 `com.xiaomi.mitv.tvplayer` 的 Intent（参考 tv-vibehack 仓库的设备笔记）。CEC 的 OSD 名（如「Apple TV」）只有系统权限能读，第三方 App 拿到的是 `Playback_1`。
- 设置：`com.xiaomi.mitv.settings/.entry.MainActivity`；Wi-Fi：`.wifi.MitvWifiActivity`。
- 截图：`adb shell screencap -p /data/local/tmp/x.png` 再 `adb pull`（直接 `exec-out` 在部分小米机型上会坏）。视频画面能截到。
- 还有一个 `pi_config` 黑名单（广电合规），里面有很多第三方桌面、文件管理器的包名，Aerial 不在其中。

## 没有标准桌面入口的系统 App

小米桌面会显示几个系统 App，但它们没有声明 `CATEGORY_LAUNCHER` / `LEANBACK_LAUNCHER`，一般的查询找不到。`AppCatalog` 里单独处理了这几个（1.7 起）：

| App | 入口 | 怎么找到 |
|---|---|---|
| 应用中心 `com.xiaomi.mitv.appstore` | `.MainActivity` | 分类 `android.intent.category.APPSTORE_LAUNCHER`（按分类查询，不写死） |
| 高清播放器 `com.xiaomi.mitv.mediaexplorer` | `.NewScraperMainEntryActivity` | 写死在 `UNLISTED_ENTRIES`；另一个 MAIN 入口 `.xq.XQMainEntryActivity` 打开是空白页 |
| 米家 `com.xiaomi.smarthome.tv` | `.ui.MainActivity` | 写死在 `UNLISTED_ENTRIES` |

其余有 MAIN 但没有桌面分类的（`mitv.mivideoplayer`、`mitv.upgrade`、`xm.webcontent`、`mi_connect_service` 等）是播放器、升级、弹窗之类，不是给用户直接打开的。

## 首页实时 HDMI 画面（试过，放弃）

- 第三方 App 可以用 `TvView` 播放 HDMI：MediaTek 的 `HDMIInputService` 会给 Aerial 建会话，`onVideoAvailable` 能收到（小米显示器 MFFU1 上实测 4K 信号）。
- 但它是 SurfaceView：不跟着磁贴缩放、倾斜、圆角；视频走 sideband 硬件层，`screencap` 截不到，截出来整屏是黑的；户主看到的实际效果是没有圆角、滚动时跳动、背景变黑。最后改成静态的信号源磁贴，按一下用小米自己的 `EXTSRC_PLAY` 全屏切过去（1.13）。

## Aerial 的任务被清理（1.12 修复）

- 1.11 起按 HOME 会先让原桌面到前台。之前 MainActivity 设了 `excludeFromRecents="true"`：Aerial 在这里不是系统认可的 HOME，它的任务一离开前台就会被系统清理（`wm_destroy_activity ... finish-imm:recent-task-trimmed`）。守护服务拉回来时，只能新建一个 MainActivity，焦点于是总回到第一个 App。
- 守护服务原来用 `CATEGORY_HOME` 拉回 Aerial，系统因此另开一个 `type=home` 的任务，同样会新建界面。现在统一用 `MainActivity.bringBackIntent`（只有 ACTION_MAIN，复用同一个任务），并去掉了 `excludeFromRecents`。
- 查法：`adb logcat -b events | grep orchard` 看有没有 `wm_create_activity` 和 `recent-task-trimmed`；`dumpsys activity recents` 里应该只有一个 `type=standard` 的 Aerial 任务。

## 应用内升级（2026-10-22，未发版代码）

- 系统安装器 `com.android.packageinstaller` 先弹小米自己的确认框「该版本未经过安全审核」（会把包名、md5、设备 id 发给小米的服务器查），默认焦点在「取消更新」，按右再确认才是「继续更新」。装完是「安装成功 / 完成」。
- 「安装未知应用」是 Android TV 设置里的总列表（`com.android.tv.settings/.device.apps.specialaccess.ExternalSourcesActivity`），不会直接定位到 Aerial；`install.sh` 用 appops 预先放行。
- 按「完成」后露出的是 PatchWall 的 `com.mitv.tvhome/.DetailsActivity`，不是 HOME Activity，所以加了「安装器关闭后拉回 Aerial」那条规则（见 ARCHITECTURE.md「升级」）。

## 没有「应用信息」和特殊权限页

- HyperOS（Android 11）没有 `ACTION_APPLICATION_DETAILS_SETTINGS`、`MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`、`MANAGE_OVERLAY_PERMISSION`、`MANAGE_WRITE_SETTINGS`、`MANAGE_UNKNOWN_APP_SOURCES` 的处理者；`android.intent.action.MANAGE_PERMISSIONS` 有两个，但 `permissioncontroller` 的那个要 `GRANT_RUNTIME_PERMISSIONS`，`packageinstaller` 的那个缺字符串、只列出一个权限组。Aerial 自己做了只读的应用信息，见 ARCHITECTURE.md「应用信息」。
- 删掉 Aerial 再装一个签名不同的版本时，系统保存的「默认桌面」会丢；`com.mitv.tvhome` 被停用的话，`FallbackHome` 和选择桌面的对话框会每秒启动十几次，`system_server` 占满一个核。保持 `com.mitv.tvhome` 启用。
