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

## 开机到 Aerial 的时间（2026-10-23 实测）

- 屏幕亮起后，小米先显示 PatchWall，约 4–5 秒后 `LaunchOtherAppsUtils: enable start process` 才放开第三方进程；Aerial 的进程为守护启动，系统再过 4–5 秒才连上守护。1.41 是连上后再等 1.5 秒接管，屏幕亮起后 11–13 秒才到 Aerial。
- 1.42 起设了「开机时打开」时，开机 3 分钟内守护进程一启动（`onCreate`）就开始接管（没设的不提前，免得打断小米开机自动播放的信号源，见下一节）；开机打开的 App 直接用它自己的入口启动，不再等 Aerial 列完全部 App（开机时要 5.5–6.5 秒）。实测从屏幕亮起到打开开机 App（云视听小电视）是 7.2 秒。
- 开机打开 App 后「又被拉回 Aerial」：户主遇到的那次，日志里是有人在小电视里连按了三次主页键（`interceptKeyTq keycode=3`），守护把它当作主页键处理。不按键时连续三次重启，小电视都停在前台，开机 30 秒、45 秒的两次复查也没动它。
- 主页键：Android 12 以上 1.36 起不过滤按键（#293），在 Aerial 里按主页键会先启动 PatchWall，守护 0.1 秒后再拉回，户主看到「闪一下小米桌面」。1.42 起 Aerial 自己在前台时过滤按键，主页键在系统之前被接住；原桌面和其他 App 里仍不过滤。户主用真遥控器确认不闪了。注意 adb `input keyevent` 注入的按键不经过过滤，测不出来。
- 待机唤醒：小米待机时杀掉了 Aerial，唤醒后要等进程重新拉起，所以会先看到小米桌面。这里没有提前接管：唤醒时 Apple TV 可能通过 CEC 正在切信号源，守护连上之前分辨不出来。

## 开机自动播放信号源

- 小米桌面开机时会自动播放上一次用过的外接信号源（日志里 `com.mitv.tvhome` 打印 `oneTouchPlay = false, autoPlay = true`，然后启动 `ExternalSourceActivity`）。2026-10-21 实测：重启前切过 HDMI 1，重启后前台就是 HDMI 1。
- 1.14 及以前，守护开机后不看前台直接拉回 Aerial，会盖掉这个行为；1.15 起前台是信号源时不动，和 CEC 的处理一致。想开机总是进 Aerial，要在小米的设置里关掉这个功能；想开机进指定的信号源，也可以用 Aerial 的「开机时打开」。

## ADB 会被关闭

- 唤醒后 `global adb_enabled` 会被系统改成 0（`dumpsys settings` 的历史里可以看到）。
- 开了 `keep_adb`（`KEEP_ADB=1 tools/install.sh`，或用 `ConfigReceiver` 的广播）后，守护服务监听这个设置，立刻写回 1。实测唤醒后 3 秒内 ADB 可连。

## 屏保

- 不用 Android 标准的 Dream（`screensaver_enabled=0`）。由小米的 PowerManagerService 在空闲 `system screen_saver_timeout`（480000 = 8 分钟）后发出 `com.duokan.intent.action.show.screensaver`，交给 `com.xiaomi.mitv.hyper.screensaver` 显示。这个广播受保护，shell 发不出去。
- 解决办法：Aerial 在桌面空闲 5 分钟时启动自己的屏保，同时加 `FLAG_KEEP_SCREEN_ON`，小米的屏保就不会再触发。停在别的 App 里空闲时，小米屏保仍可能出现。
- 只有一个 4K 解码器：航拍壁纸的 HEVC 在播时再开杜比视界，两个都 `DECODING_FAILED`（2026-10-24）。先停壁纸、再从同一时刻开杜比视界能接上，但中间黑 0.9–3.5 秒，户主不接受；现在壁纸和屏保都是航拍时屏保沿用壁纸的播放器和格式（4K SDR），不切换。
- 自带屏保的入口：`com.xiaomi.mitv.hyper.screensaver/.MainActivity` 就是屏保本身。系统由受保护广播交给 `MainReceiver`，它再带 `show_saver_list=false`、`show_widget_view=false`、`Source` 启动 `MainActivity`；不带这两个 extra 直接打开，屏保上会盖着样式选择列表（还有广告）。1.42 个性化的「系统内置」：按返回就这样打开它，计时交给小米自己（`screen_saver_timeout`），这时 Aerial 不保持常亮。实测返回键打开的是没有列表的屏保，返回回到 Aerial；停在 Aerial 60 秒后小米屏保自己出来（这台当时 `screen_saver_timeout=60000`）。屏保 3.04.115（2026-10-24）。
- 1.38 加了「从不」以后，选「从不」时 Aerial 不启动屏保，8 分钟后小米的屏保照样出现在 Aerial 上，户主以为屏保「跳回了小米自带的」（2026-10-23，户主的这台就设成了「从不」）。1.42 起选「从不」且 `screen_off_timeout` 是无限（这台是 2147483647）时，Aerial 在前台就保持 `FLAG_KEEP_SCREEN_ON`，日志里有 `setKeepScreenOnFlag KeepScreenOn:true`，户主确认小米屏保不再出来。

## 按住音量键不连发（Android 12+ 已修，未发版；#632 #460 #335）

- 现象：Aerial 在前台时按住遥控器音量键只调一格，回到 PatchWall 或别的 App 正常。#632 是小米 S Pro 2026（1.49、1.50），#460 #335 是 MFTP0（Android 9，守护一直开着过滤，所以哪里都不连发）。
- 起因：1.42 的 2e97ba4 让 Android 12+ 的守护在 Aerial 前台时开无障碍按键过滤，好在系统之前拿走主页键，不再先闪一下 PatchWall。1.36–1.41 在 Android 12+ 不过滤，按住能连发。Android 11 及以下一直要过滤，不然主页键回 Aerial 要等约 5 秒。
- 2026-10-26 在 MFTR0（1.52，`uinput` 按住 1.5 秒）上看到的：过滤开着时日志只有一次 `interceptKeyTi ... repeatCount=0` 加 `xmHandleVolumeKey() mode 1`，和一次抬起 `mode 2`，没有任何连发；守护的 `onKeyEvent` 和 Aerial 的 `dispatchKeyEvent` 都收不到音量键。`dumpsys accessibility` 里开着的是 `KeyboardInterceptor`。推测（按 AOSP 的 KeyboardInterceptor，没有看小米源码）：过滤器先问策略 `interceptKeyBeforeDispatching`，小米在这里调了音量并返回「已处理」，过滤器就丢掉这个键，键不进 InputDispatcher，也就没有连发。2026-10-24 测过：不过滤时按住 1.5 秒 1→6。
- 试过、不行的：守护在「按下后到了连发时间、音量只动了一格」时自己连调（照 TCL 的做法）。守护根本收不到音量键，这段代码没有被执行到，已撤回。
- 只关掉 Aerial 前台时的过滤，连发是回来了，但主页键闪 PatchWall 也回来了。不要为了修一个把另一个带回来；下面「onPause 拉回」是两个都保住的做法。
- 不可行的方向：
  - 只在可能按主页键之前开过滤：没有可靠的提前信号。
  - 由 App 自己连发：连发由 InputDispatcher 生成，被丢掉的键到不了那里；App 又拿不到物理按键的状态（没有公开 API），守护和 Aerial 都不知道键还按着没有。

### 2026-10-26 实测：不过滤时怎样不闪 PatchWall

测试包（不进版本库）用 `settings global aerial_test_*` 开关切换做法，`uinput` 按主页键。录屏在这台上每秒只有约 1 帧，`screencap` 一张要 1.7 秒，perfetto 也没有图层轨迹的数据源，都看不出几百毫秒的闪烁。改用 `dumpsys SurfaceFlinger --frametimeline -all`（最近 64 个显示帧，每帧哪些图层有新 buffer 或事务）：把 PatchWall 的任务层（`Task=1#47`）有没有事务变动，当作它有没有被提到 Aerial 上面。这是推断，没有人眼看过。

- **默认的不过滤做法**（等 PatchWall 的窗口事件再拉回）：抬起主页键后约 6–9ms 守护收到 `homekey` 广播，约 40ms PatchWall 启动，约 220–370ms 守护才看到 PatchWall 的窗口事件，386–518ms Aerial 恢复。3 次都有 PatchWall 任务层的变动（各 4 次），就是那一下闪。
- **悬浮窗挡住：不行，而且更糟。** 收到 `homekey` 时由守护加一层 `TYPE_ACCESSIBILITY_OVERLAY` 黑色全屏遮罩，遮罩在 PatchWall 启动前就画出来了。但小米的 system_server 每次都在切到 PatchWall 时 `Force stopping app.orchard.tv ... from pid 566`（2 次都是），Aerial 被杀掉，PatchWall 留在前台，要等复活机制再拉回来。`TYPE_APPLICATION_OVERLAY` 没有再试。
- **广播后定时拉回：不稳。** 广播后 1ms 就拉回，比 PatchWall 启动还早，PatchWall 反而盖在 Aerial 上面约 1.3 秒，直到重查才拉回；50–110ms 后拉回时，PatchWall 的任务层没动。但主线程的延迟和 PatchWall 的启动时间每次都不一样，定时器靠运气。
- **Aerial `onPause` 时拉回：逐帧记录里 5 次都没闪，户主在电视前看也不闪（疯狂连按除外，见下一节）。** 守护记下 Aerial 在前台时收到 `homekey` 广播的时间；随后 1 秒内 Aerial 的 `MainActivity.onPause`（即 `HomeGuardService.onAerialPaused`）被调用时，马上 `goHome()`。onPause 在抬键后 31–76ms，正好在 PatchWall 启动之后、被提到前面之前。5 次里 PatchWall 的任务层都没有变动（只在后台画了 1–2 帧），Aerial 在 123–290ms 内恢复。
- 同一做法下，Aerial 在前台不过滤，按住音量能连发：音量加 1.5 秒收到 18 次按键，音量减 12 次。
- 顺带发现：这台现在按一下音量键、或者控制中心滑块走一格，音量都变 2 格。日志里 Aerial 的 `adjustStreamVolume` 只调了 1 格，随后 `HdmiCecLocalDeviceTv: setStreamVolume xm_volume` 把音量改成 HDMI ARC 音响回报的值。这台现在从 ARC 出声，音响有自己的步进，所以有些值（比如 18）用按键或滑块调不到。过滤开着和关着都一样，不是 Aerial 造成的。
- 测试补丁没有进版本库，`aerial_test_*` 设置都删了。

### 已修：小米 Android 12+ 在 Aerial 前台时不过滤，onPause 时拉回（2026-10-26，未发版）

`HomeGuardService.backOnPause`：有 `com.mitv.tvhome` 且 Android 12+ 时，Aerial 在前台也不过滤按键，音量键交给小米，按住就能连发。守护在 Aerial 前台时（或刚把它拉回、还在路上时）收到 `homekey` 广播，就记下时间；400ms 内 Aerial 进入 `onPause`，就马上 `goHome()`。两次广播间隔不到 600ms 算连按两下，等 Aerial 回来再打开最近任务，保留 1.52 由过滤器做的功能（#335）。按广播计时是因为 Aerial 正在回来时广播会晚到：按键间隔 120ms，广播间隔 350ms 以上。Android 11 及以下（MFTP0，#460）和其他品牌都不变：Android 11 及以下主页键之后系统会挡住后台启动 5 秒，onPause 时拉不回来。

在 MFTR0 上测（standard release）：
- `uinput`：单按 7 次、连按 5 次（含间隔 120ms 的快速连按），frametimeline 里 PatchWall 的任务层都没被提上来，连按都打开了最近任务；按住主页键是小米的任务中心，守护不干预；按住音量 1.5 秒，音量加 16 次、音量减 12 次连发；从 Aerial 打开云视听极光不会被拉回，在云视听极光里按主页键照旧回到 Aerial。
- 户主在电视前看：正常速度按主页键不闪，按住音量能连发。疯狂连按主页键会闪一下 PatchWall，换回正式的 1.52 也一样，不是这个改动带来的。

## 其他

- 背光：`Settings.Global picture_backlight`（0–100），改了以后 `tvmenusettingsservice` 会立即应用。需要 `WRITE_SECURE_SETTINGS`。
- 音量：普通的 `adjustStreamVolume` 就能生效。按住音量键不连发见上一节。
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

