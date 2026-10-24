# 小米 Android 6（MiTV4-ANSM0、MiTV4A、MiTV4X、MiTV3S、小米盒子 4、MDZ-19-AA）

Amlogic 平台、Android 6.0.x 的一批小米电视和盒子，问题基本相同。我们手上没有，以下都来自 issue 里用户的实测。

## 守护「聋」

- PatchWall（`com.mitv.tvhome`，uid 1000）到前台时**不发**无障碍窗口事件，窗口内容也读不到；别的 App 的事件正常（#81 一位用户逐条打印确认）。主页键被 `MitvPhoneWindowManager` 先处理，按键过滤收不到（部分固件）。
- 开机约 20 秒后 PatchWall 的 `BootActivity` 盖住 Aerial，关掉后露出主页，没有新事件（#209 #318）。
- 开机那次绑定有时 `queryIntentActivities(HOME)` 拿不到 PatchWall（#318）。
- 1.43：守护在 Android 6 及以下每秒读一次用量统计（`UsageStatsManager.queryEvents` 最近 10 秒里最新的 `MOVE_TO_FOREGROUND`），前台是原桌面的主页 Activity 就拉回 Aerial，同一个事件只拉一次。要 `appops set app.orchard.tv GET_USAGE_STATS allow`（只能 adb 给）。用户在 ANSM0 上用本地补丁验证过开机和主页键都能接管，按主页键后要 5–8 秒才回到 Aerial（这台启动慢）。
- 手动恢复（老版本）：`settings put secure accessibility_enabled 0`，等 2 秒，再 `1`；或 `am force-stop` 后重开 Aerial。

## 开机广告（BootActivity）

- 开机广告是 tvhome 自己的 `BootActivity`（`am start` 日志里 `from uid 1000`），不是 `com.xiaomi.mitv.advertise`（那个包本来就是禁用状态）；广告配置 `api.ad.xiaomi.com`（net only 不走缓存）、内容 `ottimg.cdn.pandora.xiaomi.com`。
- 组件禁不掉：tvhome 是 uid 1000 系统应用，`pm disable(-user)` 全是 SecurityException，`adb root` 在 production build 不可用；`am force-stop` 能杀但 ~2 秒内被播放器框架（`com.mitv.oneplayer` 绑 `MediaService`）保活拉回，stopped 标志也被清——「永久停用 PatchWall」走不通。
- 可行的只有开机窗口内击杀：守护见到原桌面非主页类 Activity 露头（BootActivity）时，先把 Aerial 盖上去（`goHome`），2.5 秒后 `killBackgroundProcesses` 杀进程——实测广告露头 0.7 秒即被盖死、3 秒内进程消灭。`FORCE_STOP_PACKAGES` 固件不授予（`not a changeable permission type`），只能靠普通权限 `KILL_BACKGROUND_PROCESSES` + 先盖后杀。
- 杀后 tvhome 被保活拉起到后台，但不重播广告（BootActivity 只跟随真实开机流程触发）；运行期原桌面露头仍只拉回不杀，不影响用户主动打开 PatchWall。

## HDMI 编号（小米播放器 `ExternalSourceActivity --ei input N`）

- 老固件（8.x、20.x）只给接了设备的口注册一个 `Hdmi3InputService/HW7`，播放器只认 23（#191 ANSM0、#313 4A）。
- 固件 22.4.15.3234（ANSM0）：23/24/25 分别是 HDMI 1/2/3，HW7 = 25，按通用规则 `hw + 18`（#375）。
- 1.43 按 `Build.DISPLAY` 第一个数字区分（≥ 22 用通用规则），22.x 以外的新固件没验证。

## 其他

- 没有 `cmd`：`cmd package compile`、`set-home-activity`、`role` 都不可用；通知监听要用 `settings put secure enabled_notification_listeners`。
