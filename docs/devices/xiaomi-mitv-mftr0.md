# 小米 MiTV-MFTR0（codename freeguy）

- Android 14（SDK 34），HyperOS TV，固件 OS3.0.101.0.UFTMATV，MediaTek MT9653，32 位 ARM（armeabi-v7a），4 GB 内存。
- 1920×1080，320dpi（960×540dp），只有一个 60Hz 显示模式。GPU 很弱，见 ARCHITECTURE.md 的性能约束。1.15 导航帧耗时：第一排中位数 14ms、网格 18ms、第一排与网格之间切换的 90 分位 20ms（2026-10-21）。
- 原桌面：`com.mitv.tvhome/.MainActivityUserMode`（PatchWall）。备用桌面：`com.xiaomi.mitv.settings/.entry.FallbackHome`（priority -1000）。
- 安装的 flavor：`standard`。普通的 `adb install` 就能装。
- 同平台的零售型号：**Redmi X65 2026** 也上报 `MiTV-MFTR0`。用户在 OS3.0.107.0.UFTMATV 上验证过一键安装和重启后自动进入 Aerial（GitHub #3）。

## HOME 锁定

- `cmd role add-role-holder android.app.role.HOME app.orchard.tv` 和 `cmd package set-home-activity` 都返回成功，但 `resolve-activity` 仍然返回 `com.mitv.tvhome`。
- 原因：`com.android.server.pm.MitvSettings.isThirdpartyHomeAllowed(pkg)` → `isAppInWhitelistWithFlag(pkg, 4)`。白名单来自 `/data/system/mitv_security_center.xml`（system 0600，shell 读不了，由云控下发）和 `persist.appcontrol_*` 属性，只有系统进程能写。
- 白名单的内容开机时会打到日志里：重启后马上执行 `logcat -d -s MitvConfigParser`，看 `[White] 包名=标志` 这几行（日志缓冲很快会被覆盖）。2026-10-29 在 OS3.0.101 上读到 127 条白名单、54 条黑名单，其中带 HOME 位（4）的有 42 个，不含 `app.orchard.tv`。像第三方桌面的有：`com.ts.launcher`、`com.thunderstone.idun(.dev)`、`com.tongshi.home`、`com.bytv.launcher(.special)`、`com.meituan.retail.launcher`，都是 511。当贝桌面 `com.dangbei.tvlauncher` 在黑名单里。带待机免杀位（`0x8000`）的只有 `com.tencent.qqmusictv` 和 `com.xiaomi.mitv.light`。
- cleanyourtv 的记录（AETP0、MTEQ0、Redmi MTTP0 等，Android 9/10）：用白名单里的包名重新编译后，不停用 PatchWall 也能设为 HOME。在 HyperOS 3 上（ASTR0、MFFU0，以及其他几台 MFTR0）也能接管，但必须先停用 `com.mitv.tvhome`，否则会弹出桌面选择框。这些包名属于别的公司，而且换包名等于装一个新 App，户主数据和授权都要重来，所以 Aerial 没有采用。
- 2026-10-29 在这台上试过（OS3.0.101，不停用 PatchWall）：把 Aerial 编成 `com.ts.launcher` 装上后，`query-activities` 能列出它，普通的 `app.orchard.tv` 也在列表里。但 `set-home-activity` 和 HOME 角色照样被拒，日志 `addPreferredActivity: Don't set 3-app default home`。HOME 的解析结果从 PatchWall 变成了 `ResolverActivity`（桌面选择框）：白名单包名被留作候选，可是设不成默认。选择框里点「始终」能不能记住，没有试（要按实体遥控器）。试完已卸掉测试包，HOME 角色改回 `app.orchard.tv`，解析回到 PatchWall。
- 2026-10-29/30 按 #901 的做法实测（户主同意，测完已还原）：装着 `com.ts.launcher` 测试包时停用 `com.mitv.tvhome`，HOME 直接解析到测试包，不弹选择框；`app.orchard.tv` 虽然持有 HOME 角色，也不会被选中。从设置按主页键 130–355 ms 回到测试包，不闪原桌面；8 个信号源和 HDMI 切换正常；重启时先进 FallbackHome，再进测试包，不黑屏；遥控器关机后再唤醒，系统直接拉起测试包，没有出现报告人说的小米应用界面。PatchWall 常驻约 170–180 MB（PSS）。Aerial 的帧时间（`perf.py` 的 row、grid、cc 各两轮）在停用和 PatchWall 后台常驻两种情况下没有差别。坑：① tvhome 启用而测试包还在时，HOME 解析成选择框；② 守护的原桌面名单（`stockHomes`）只在启动和解锁时读，PatchWall 停用期间启动的守护，在 PatchWall 重新启用后不认它，按主页键会停在 PatchWall，重启后恢复（1.69 之后已修：包被启用、停用、安装、卸载时重读，诊断报告的 `guard keeps away` 能看到名单）；③ ADB 发 `POWER` 让电视待机会断网，远程（WoL、6095、米家）都唤不醒；④ PatchWall 重新启用后第一次启动、以及开机时，会按 `boot_auto_play` 自动播放上次的信号源。
- **不要停用 `com.mitv.tvhome`**（除非白名单包名的桌面已经装好）：停用后 HOME 解析到 FallbackHome，而 FallbackHome 也不认第三方 HOME，会一直黑屏并刷日志「User unlocked but no home」。实测过，之后已立即 `pm enable` 还原。白名单由云控下发，小米删掉某个包名后，靠它接管的电视会落到同样的黑屏。
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
- 2026-11-03（OS3.0.101）没设「开机时打开」时的实测：屏幕亮起 4.6 秒后才放开第三方进程，系统连上守护又要 3.9 秒；有一次连不上，靠通知监听 5 秒后的「拿出再放回」才连上，又多等约 5 秒。从屏幕亮起到 Aerial 第一帧 12.3 秒和 19 秒。改法（`HomeGuardService.patchwallHomeInFront`）：守护进程 `onCreate` 时用量统计显示 PatchWall 的主界面（`MainActivityUserMode`）在前，就和设了「开机时打开」一样马上开始接管（照样等 1.5 秒再看一次前台），连上守护时不再重新计时。改后三次重启：6.7 秒、9.9 秒（守护又要靠「拿出再放回」才连上），以及一次重启前停在 HDMI 1 的，开机后到 Aerial、没有信号源。那次没自动播放是因为这台 `boot_auto_play=0`（PatchWall 日志 `bootAutoPlay=false`）；PatchWall 在第 15.5 秒左右就决定要不要播，比第三方进程放开早，所以开着自动播放时，守护启动时前台已经是 `ExternalSourceActivity`。户主同意后临时设 `settings put system boot_auto_play 1` 实测（测完改回 0）：停在 HDMI 1 重启，PatchWall 16.5 秒 `autoPlay = true` 打开 HDMI 1；那次守护进程起得早、还没解锁（`bound while user locked`），解锁时「user unlocked with com.xiaomi.mitv.tvplayer in front; leaving it there」，`onCreate` 的提前接管没走到。HDMI 1 没接信号，58.8 秒播放器自己退出（`app-request`）回到 PatchWall，守护才把 Aerial 拉上来，和以前一样。连上时偶尔还会再拉一次（用量统计还说 PatchWall 在前），Aerial 只是多一次 onPause / onNewIntent / onResume，改之前也有。
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
- Aerial 屏保保持常亮 4 小时后会放开（`SCREENSAVER_HOLD_MS`，好让电视自己关屏）。这台 `screen_off_timeout` 是无限，放开后屏幕不会关，小米的计时器 60 秒后照样把它的屏保盖上来（户主 2026-10-27 03:35 遇到，用量统计里 Aerial 23:31 回到前台，03:35 小米屏保出现）。现在屏幕本来就不会自动关时不再放开。用 90 秒的测试包对比过：旧逻辑放开 60 秒后小米屏保出现，新逻辑 3 分半仍是 Aerial。按 `MitvPowerManagerService` 反编译：计时器在「有视频在播」「有视频暂停（`media.player` 报的前台播放器）」之后才看常亮；后台 App 里暂停的 MediaPlayer 实测不算（报 `pausing:false uid:-1`），所以 Aerial 在前台时只有常亮放开会让小米屏保出来。
- 只有一个 4K 解码器：航拍壁纸的 HEVC 在播时再开杜比视界，两个都 `DECODING_FAILED`（2026-10-24）。先停壁纸、再从同一时刻开杜比视界能接上，但中间黑 0.9–3.5 秒，户主不接受；现在壁纸和屏保都是航拍、画质为「自动」时，屏保沿用壁纸的播放器和格式（4K SDR），不切换。手动选了「4K 杜比视界」才切过去：先停壁纸、从同一时刻开杜比视界，实测第一帧 2.8 秒（2026-10-27）。
- 杜比视界解码器（`OMX.MS.DOLBY_VISION.DVHE.STN.Decoder`）要在建的时候就拿到要显示的 SurfaceView：建在 ExoPlayer 的占位 surface 上，它走 DIP（logcat `MS_GenerateOutputData … dip:1`，帧拷到 GPU 缓冲），之后换到 SurfaceView 也不回硬件路径，画面不是杜比视界（#754）。走对了时 logcat 有 `GRM: AcquireVdecHw planar 0, dv 1, … bAlwaysPreemptDispBuf 1`，没有 `dip:1`。屏保的播放器因此不用占位 surface（`AerialStream.player(decodeOnScreen = true)`，2026-10-27）。
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

### 按主页键壁纸黑一下（2026-10-29 修，未发版）

- 1.68 起（上一节的 backOnPause），Aerial 里按主页键时 Aerial 会 `onPause` 约 150ms 再被拉回。暂停时 `vm.resumed` 变成 false，航拍壁纸立刻释放播放器，可视频层还开着、下面没画东西，屏幕就黑了；回来后影片从头加载（日志里主页键之后又出现 `wallpaper film starts`）。
- 修复：`HomeGuardService.onAerialPaused()` 返回「这次暂停只是主页键的回弹、正在拉回」，MainActivity 就设 `vm.homeBounce`，航拍和视频壁纸按 `vm.filmFront`（resumed 或回弹中）继续播放；500ms 内没回来或进了 `onStop` 就照常释放。信号源壁纸不跟这个开关。
- 实测（standard release，uinput）：单按 4 次之后都没有 `wallpaper film starts`，没有 `onStop`；间隔 120ms 连按两下照旧打开最近任务（`RecentsPanel`）；Aerial 在前台时仍不过滤按键（音量连发不受影响）。回弹期间 PatchWall 没有开解码器。户主还没有在电视前看。

### 别的 App 里按主页键先闪 PatchWall（2026-11-01 修，未发版）

- 现象：小米 Android 12+ 在别的 App（夸克、HDMI 信号源等）里按主页键，先闪一下 PatchWall 再回 Aerial。Aerial 自己在前台时没有（那里靠上面的 onPause 拉回）。
- 在小米显示器 MFFU1（Android 14）上用 uinput 量的时间（抬键为 0）：+30ms 系统把 PatchWall 切到前台，约 +220ms PatchWall 画出第一帧，+290ms 守护才收到它的窗口事件开始拉回，Aerial 约 +550ms 画出来。录屏里 PatchWall 露出 7 帧。
- 主页键广播帮不上：Android 14 对 `CLOSE_SYSTEM_DIALOGS` 用「等 App 活跃再送」（`deferralPolicy=2`），Aerial 在后台时广播被扣着，Aerial 被拉回前台后才送到（`dumpsys activity broadcasts history`：其他接收者 +1–3ms，Aerial +363ms）。
- 试过、太晚的：用量统计本身有 100–250ms 延迟；`addOnUidImportanceListener`（反射能调到）夸克变后台要 +274ms。
- 修复（`HomeGuardService.uiBoostChanged`）：小米的界面加速，`services.jar` 里 `com.mitv.server.tvqs.TVQSService.startBoostInternal` 在界面切换开始时把 `global mitv_fast_cur_anim_scale` 写成 0.5（结束写回 1.0），按键后 +45–65ms。守护用 ContentObserver 监听，之后每 20ms 读一次用量统计（最多 600ms），一看到 PatchWall 主界面刚切到前台就拉回，Aerial 在 PatchWall 画出来之前盖上去；用量统计先出现别的 App 就停（App 内切页也会触发加速）。开机、唤醒窗口内、「桌面」磁贴的访问都不走这条，原来的窗口事件仍是兜底。
- 不按守护记的前台窗口判断：从后台再次打开的 App（比如从 Aerial 再开一次云视听极光）不发窗口事件（#475），守护记的还是 Aerial，第一版因此在 MFTR0 上对这类 App 不生效。
- 试过、撤掉的第二个信号：无障碍的窗口变化（焦点离开、窗口移除，+120–230ms）。要打开 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`，而打开后系统会把窗口状态事件压到窗口列表更新之后，MFTR0 上兜底的窗口事件从 177–277ms 推迟到 336ms 以后，云视听小电视里按主页键反而闪了（户主在电视前看到）。
- 用量统计的延迟决定了能不能赶上：MFFU1 上 PatchWall 出现在用量统计里是 +116–341ms，MFTR0 开着云视听小电视时 +136–434ms。
- 实测（standard release，uinput 按主页键，screenrecord 逐帧比对，云视听小电视的画面录出来是黑的）：
  - MFFU1 夸克：改前 7 帧 PatchWall；改后 8 次里 7 次 0 帧，确认在 +341ms 的 1 次仍闪。
  - MFTR0 云视听小电视：改前 6 次都 0 帧（这台窗口事件 177–277ms 就到）；改后 6 次都 0 帧。MFTR0 夸克（从后台再开）4 次都 0 帧。
  - 回归：Aerial 前台按主页键不闪、按键过滤仍关着；从 Aerial 开 App、App 内切页、App 叠 App、按返回都没被误拉；「桌面」磁贴留在 PatchWall；HDMI 里按主页键走新路径（HDMI 画面录屏录不到）。
- 原有、没改的两个问题（户主 2026-11-01 在 MFTR0 上报）：
  - Aerial 里连按两下主页键常露出 PatchWall，停 1–3 秒才被拉回：uinput 间隔 120ms，改前 5 次里 4 次、改后 5 次里 2 次（看日志里的 `stock home ... came to the front`；这台在 Aerial 首页录屏只录到几帧）。
  - 长按主页键打开小米任务中心、全部清除：SystemUI（pid 2028）把 Aerial 强行停止（`am_kill ... stop app.orchard.tv due to from pid 2028`），PatchWall 停了约 30 秒 Aerial 才被拉起。

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

## 打开 ADB 后有一个 USB 口不认设备（#907，#875 同一规则）

`/vendor/etc/init/hw/init.mt9653.usb.rc`（户主这台 OS3.0，2026-10-30 读出）：`sys.usb.config=adb` 时执行 `usb_otg_agent device`，把 OTG 控制器 `1c7a3e00.usb` 切到设备模式；`none`、`mtp` 时切回 `host`；`persist.sys.usb.config=mtp,adb` 会被改写成 `mtp`。户主这台 `adb_enabled=1`、`sys.usb.config=adb`、`persist.sys.usb.config` 为空，所以这个控制器下的口（报告人说是 USB 2.0 那个）不认 U 盘和手柄。Aerial 不写 USB 配置，但 `keep_adb` 会在小米关掉 ADB 时重新打开它。没有插设备验证是哪个物理口；`mode` 文件要 root 才能读。海信 VIDAA（MT9952，#875）是同样的规则，报告人 `setprop persist.sys.usb.config mtp` 后重启恢复。

## blbl 里按主页键慢 2 秒（#984，OS3.0.106）

两位用户（MFTR0 OS3.0.106、MFFU0）在第三方 B 站客户端 `blbl.cat3399` 里按主页键，守护看得到 blbl 的窗口，却收不到之后 PatchWall 的窗口事件，开关一次无障碍也一样，只能等 1.5 s 后查用量统计。我们这台 OS3.0.101 上从云视听小电视按主页键，窗口事件 140 ms 就到。1.75 之后守护把这种 App 记下来，下次在里面按主页键就从按下起每 150 ms 查用量统计。

## 航拍壁纸取帧（2026-11-01）

- PixelCopy 读这台的视频层得到的是黑屏（`result 0, black`），每次 3–31ms；所以底板的磨砂全靠 `FrameReader` 从 1080p 文件解帧。`screencap` 倒是截得到影片画面。
- 1.76 及以前，两条标记会让这台丢掉磨砂、只剩烟色：某次黑屏复制超过 150ms 被记成「PixelCopy 慢」（按固件指纹永久保存，连带不读文件）；或者某次解帧超过 10 秒被记成「本次开机文件卡住」。开机 2 天后户主发现底板没有磨砂，日志是 `copy false, file frames stuck true`。修复见 ARCHITECTURE「航拍壁纸取不到帧的电视」⑥。旧版写下的 `stuck_boot` 要重启一次才清掉。
- 1.81 还会丢：慢调用最终返回时才清标记，可调用还没返回进程就没了（当天早上装了 1.81），标记存在偏好里，后面的进程都沿用到重启。2026-11-03 户主发现航拍壁纸下底板、右上角按钮、个性化都没有玻璃，诊断 `file_stuck=true glassless=true`，当前进程里没有卡住的调用。现在别的进程留下的标记只在有 TCL 解析器的电视上算数（ARCHITECTURE 同一条 ①），这台装上后 `file_stuck=false glassless=false`，三处恢复磨砂。

- 小米显示器 MFFU1（安卓 14）和这台不同：PixelCopy 读得到影片，但 4K 时一次 0.7 秒、有时读出绿色。1.77 里一次慢复制按固件指纹记成慢（`copy_slow_read`），连带不读文件，户主切成杜比视界画质（壁纸随之换成 4K）后底板和面板的磨砂都没了，日志 `copy false, file frames stuck false`。现在慢复制只在有 TCL 解析器的电视上关文件（ARCHITECTURE 同一条 ⑦），这台日志 `TCL's extractor false`，从文件解帧，控制中心是磨砂玻璃。
