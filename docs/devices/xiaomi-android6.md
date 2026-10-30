# 小米 Android 6（MiTV4-ANSM0、MiTV4A、MiTV4X、MiTV3S、小米盒子 4、MDZ-19-AA）

Amlogic 平台、Android 6.0.x 的一批小米电视和盒子，问题基本相同。我们手上没有，以下都来自 issue 里用户的实测。

## 守护「聋」

- PatchWall（`com.mitv.tvhome`，uid 1000）到前台时**不发**无障碍窗口事件，窗口内容也读不到；别的 App 的事件正常（#81 一位用户逐条打印确认）。主页键被 `MitvPhoneWindowManager` 先处理，按键过滤收不到（部分固件）。
- 开机约 20 秒后 PatchWall 的 `BootActivity` 盖住 Aerial，关掉后露出主页，没有新事件（#209 #318）。
- 开机那次绑定有时 `queryIntentActivities(HOME)` 拿不到 PatchWall（#318）。
- 1.43：守护在 Android 6 及以下每秒读一次用量统计（`UsageStatsManager.queryEvents` 最近 10 秒里最新的 `MOVE_TO_FOREGROUND`），前台是原桌面的主页 Activity 就拉回 Aerial，同一个事件只拉一次。要 `appops set app.orchard.tv GET_USAGE_STATS allow`（只能 adb 给）。用户在 ANSM0 上用本地补丁验证过开机和主页键都能接管，按主页键后要 5–8 秒才回到 Aerial（这台启动慢）。
- 2026-10-26 在 MiTV4（pulpfiction，固件 9.1.8.2318）上查清了「按键过滤收不到 Home」，原因在 `flagDefault`：反编译电视自带的 services.jar，`AccessibilityManagerService.notifyKeyEventLocked` 先在**没有** `flagDefault` 的服务里找、再在有的里找，每一轮都从最后绑定的服务往前，把键只交给第一个申请过滤的服务，它不要的键回到系统，不会再给下一个。小米语言麦克风（`com.xiaomi.mitv.karaoke.service` 的 `PuremicKeyService`）也申请过滤且没有 `flagDefault`，于是第一轮总是它拿到键，带 `flagDefault` 的守护一个键都收不到（Home、方向键都没有），Home 进了 PatchWall。
- 现在：Android 6 及以下有别的服务申请过滤且没有 `flagDefault` 时，守护过滤按键时也去掉这个标记（`HomeGuardService.keyFilterRival`）。开机时三个服务的进程由无障碍同时拉起，守护进程最重、最后连上，自然排在最后：8 次开机 40 次 Home 全部拦下。
- 绑定顺序只有 `dumpsys accessibility` 能看到（越靠后越晚绑定）；App 里的 `getEnabledAccessibilityServiceList` 按反馈类型逐位收集，小米语言麦克风的反馈类型是 0，永远不在结果里。
- `BootActivity`：守护的用量统计轮询在开机 3 分钟内也认 `com.mitv.tvhome.BootActivity`，0.6–1.0 秒盖掉（以前要等 30 秒的复查，实测 19.7 秒）。
- 开机目标是信号源时，PatchWall 的**主页 Activity**（`MainActivityUserMode`）会先于 `BootActivity` 出现，而每秒一次的用量统计轮询经常先读到它（它也在原桌面列表里），于是 #963 那条只认 `BootActivity` 的重开逻辑没命中，落到通用分支「把 Aerial 拉回」，信号源被顶掉。已在 1.74（未发布）修复：判断改成「开机窗口内、开机目标刚打开（60 秒内）、前台是原桌面的任意界面、且最近没有按主页键」，`foregroundCheck` 和 `takeOverIfStockHomeInFront` 两处共用（`bootTargetCovered`）。MiTV4-ANSM0（Android 6.0.1，固件 22.10.20.3352）实测：目标 01:45:56 打开，01:46:26 主页恢复、开机页出现，轮询读到 `com.mitv.tvhome/com.mitv.tvhome.MainActivityUserMode`，日志 `stock launcher's screen over what the TV starts on; opening that again`，0.4 秒后信号源重新打开，之后前台一直是 `ExternalSourceActivity`。
- 那个抢前台的 `BootActivity` 就是开机广告位（`onCreate` 里 `sendAdShowBroadcast`）：户主把小米侧 `boot_ad` 关掉后它照样起来、内容是空白的，而且要 **11 秒后自己 finish**（`am_finish_activity ... app-request`，实测 02:52:41.29 起、02:52:52.40 退）。它一起来播放器就被 `am_pause_activity`，踢掉后播放器恢复、重新选源，这段空白就是户主说的「信号中断又恢复」。它抢前台会**连来两次**，中间只隔约 1.2 秒（实测 03:11:55.03 与 03:11:56.23 各一次），两次都要各自重开一次信号源，不要试着对 `openBootSource(again = true)` 去重——第二次被守卫拉起来的 Aerial 已经在前台，跳过一次重开就会停在 Aerial 主页（试过，退回）。
- 固件 22.10.20.3352 上开机时 `TvInputManager` 会列出 droidlogic 的两个 HDMI（`Hdmi3InputService/HW7`、`Hdmi1InputService/HW5`，没有 HW6），长时间运行后又变成空、由小米播放器兜底成 `xiaomi:23/24/25`。所以「开机时打开」要选开机那一刻列出来的那个（本机是 `Hdmi1InputService/HW5`，标为 HDMI 3），选成 `xiaomi:25` 时开机找不到这个 id，开机目标根本不会打开。
- 测按键要往输入设备写原始事件：`sendevent /dev/input/event0 1 102 1` 等（`aml_keypad`，shell 在 input 组里），和实体遥控器走同一条路；`input keyevent` 不经过无障碍按键过滤。
- 手动恢复（老版本）：`settings put secure accessibility_enabled 0`，等 2 秒，再 `1`；或 `am force-stop` 后重开 Aerial。

## HDMI 编号（小米播放器 `ExternalSourceActivity --ei input N`）

- 部分老固件（8.x、20.x）只给接了设备的口注册一个 `Hdmi3InputService/HW7`，播放器对这个口使用 23（#191 ANSM0、#313 4A）。
- 固件 22.4.15.3234（ANSM0）：23/24/25 分别是 HDMI 1/2/3，HW7 = 25，按通用规则 `hw + 18`（#375）。
- 固件 22.8.15.3312 起（ANSM0，#592；22.11.18 #584、22.11.28 #514）：droidlogic 注册的 `Hdmi3InputService/HW7` 是播放器的 HDMI 1，编号 23；HW6、HW5 是 24、25。`devices.json` 用 `buildAtLeast: "22.8"`（1.61 是 22.11，22.8 上 HDMI 1 和 3 名字对调）。22.4 和 22.8 之间的固件没人报过。
- 1.43 按 `Build.DISPLAY` 第一个数字区分（≥ 22 用通用规则），这是错的：固件 20.3.9.2061 上 23/24/25 也是 HDMI 1/2/3（#492），固件 22.11.28.3390 上 HW7 却要 23（#514）。
- 真正决定编号的是固件配置：#191 的日志里播放器 `queryTvConfigByKey hdmi3_order` 读到 23（`ConfigParser`，TvService 进程）。同型号不同尺寸的板子配置不同。#492 的 MiTV4-ANSM0（`8.9.19.854`）通过 `TvService` 交易 82 实测 `hdmi3_order` 返回 25，与直接启动播放器时的正确编号一致。
- `mitv.common.ConfigurationManager.getInstance().queryTvConfigByKey("hdmiN_order")` 是可用的公开中间层入口；Aerial 用反射读它，失败时再回退到已知型号映射。

## 其他

- 没有 `cmd`：`cmd package compile`、`set-home-activity`、`role` 都不可用；通知监听要用 `settings put secure enabled_notification_listeners`。
