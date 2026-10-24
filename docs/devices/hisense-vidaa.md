# 海信 VIDAA（`VIDAA_TV`）

来自用户反馈（#40、#7、#84、#119、#120 和微博评论），维护者手上没有海信，以下都是报告人的实测。

## 已报告的机型

| 机型 | 系统 / 平台 | 结果 | 来源 |
|---|---|---|---|
| VIDAA_TV | Android 11，MT9653，1080p | 装得上；改 `persist.vt.use_home_pkg` 后主页键进 Aerial；开机仍先进聚好看 | #40 |
| VIDAA_TV | Android 10，RTD2851D | 1.21 及以前音量键无效（守护过滤按键）；1.22 起不过滤，#40 的报告人在 1.22 上确认 `A11yInputFilter` 为空、音量正常；微博也有 1 人说新版音量好了 | #7 |
| VIDAA_TV | Android 14，MT9655 | 控制中心「信号源」为空：固件没把 HDMI 注册进 `TvInputManager`，有私有服务能弹出海信自己的信号源面板 | #84 |
| Vidda 85V7N-Ultra | 未说 | 装得上，信号源扫不到（同 #84）；开 ADB：声音平衡里连按菜单、确定进工厂模式，To Fac 从 U 改 M 后重启 | #120 |
| 较老的海信（`com.jamdeo.launcher`，`BaseTileActivity`） | 日志里有 `TaskRecord`，系统应在 Android 7 以前 | 开机先显示海信桌面（智汇中心），过很久才到 Aerial；海信的 `AppManagerHelper` 把 `app.orchard.tv` 列为 `Black package` | #119（只有日志，型号待补） |
| VIDAA_TV（老款） | Android 5.1.1（SDK 22），MStar MSD6A828 | 装得上；没有 `cmd`；每次开机固件把 `accessibility_enabled` 清成 0，直接写 1 会被静默丢弃，先 `pm unhide` 任一个包触发包变更后再写才生效；GlobalPanel 吃掉主页键，只有守护能接管；设置里没有无障碍入口，每次开机都要用电脑恢复 | #285 |
| VIDAA C1 投影 | Android 9，MT9669 | 没有 role 服务，也没有 `persist.vt.use_home_pkg`；聚好看 HOME priority=50，守护拉起 Aerial 后 2–5 秒系统（uid 1000）又切回聚好看；`SYSTEM_ALERT_WINDOW` 被改回 ignore；暂时接管不了 | #323 |
| 海信会议室触屏电视、海信投影 | 未说 | 投影打不开 ADB；会议室机只问了能否保留触屏（能，1.20 起支持触屏） | 微博 |

微博上海信 / Vidda 用户最多的反馈是**开机不进 Aerial**（2026-10-22 一天约 6 人），其次是信号源、音量（已修）。

- Android 11（SDK 30），`RP1A.200720.011`，MediaTek MT9653，arm64-v8a，1920×1080。
- 原桌面：`com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity`（聚好看）。
- 安装的 flavor：`standard`。`adb install` 直接成功；`pm grant`、`appops`、通知监听、屏保接管都正常，`APP_AUTO_START` / `APP_ASSOC_START` 报 `Unknown operation string`（TCL 才有，忽略）。

## 开机反复选择桌面（H55E9A）

2026-10-24，H55E9A（Android 8.0.0，固件 `03.02.60.02`），已安装 Aerial 1.43 / versionCode 46。实机日志显示：系统先按默认 HOME 启动 Aerial，随后桌面守护在用户解锁前读取凭据存储里的 SharedPreferences，抛出 `IllegalStateException: SharedPreferences in credential encrypted storage are not available until after user is unlocked`。紧接着系统记录 `Clearing package preferred activities from app.orchard.tv` 并打开 `ResolverActivity`。因此「始终」确实保存过，是开机崩溃后被系统清除了。

- 本地代码中，`onServiceConnected` 在解锁检查前调用 `watchCecInput`，其中 `CecHome.inputSettingKey` 无条件读取偏好；即使 CEC Home 没开启也会触发。
- 这条调用和设置读取由 `617e317a`（2026-10-23，Apple TV HDMI Home 按键控制）引入。
- 修复：`CecHome.enabled` 和 `inputSettingKey` 在未解锁时分别返回 false / null；收到 `ACTION_USER_UNLOCKED` 后重新注册 CEC 输入监听并刷新按键过滤。偏好仍留在原存储，不迁移用户数据。
- 自动化验证：8 项守护相关测试通过，覆盖未解锁时不读设置、解锁后读取保留的配置、服务绑定到解锁后的监听注册和销毁清理，以及 Android 6 的兼容性；standard / guard 的 release Kotlin 编译通过。测试不替代真机重启验证。
- **修复尚未在真机重启验证**：本机缺少原安装的签名密钥，未覆盖安装、重启或更改电视设置。需要同签名修复版覆盖安装后验证开机不再崩溃、默认 HOME 保留以及解锁后的正常使用。

## HOME 锁定

- `cmd role add-role-holder` 成功，`dumpsys role` 里 HOME 的 holder 也是 Aerial，但按 HOME 时系统用**显式 component** 直接拉起聚好看：`START ... cat=[HOME] cmp=com.jamdeo.tv.vod/... from uid 1000`。
- 桌面守护已绑定，但按 HOME 后收不到聚好看的 `TYPE_WINDOW_STATE_CHANGED`，没有任何 `AerialGuard` 日志。守护「拉回前台」本身是好的（用 `ConfigReceiver` 触发重绑时能把 Aerial 拉回来），问题在事件派发。
- **解决办法**：海信用私有属性 `persist.vt.use_home_pkg` 指定主屏幕包。`adb shell setprop persist.vt.use_home_pkg app.orchard.tv` 之后，`resolve-activity` 返回 Aerial，按 HOME 直接进 Aerial，重启后属性仍在。不需要 root。
- `tools/install.sh`（standard 版）只在这个属性存在时改它，原值存在电视的 `settings global aerial_vt_home_pkg` 里；`tools/restore.sh` 用它还原。

## 开机自启（做不到）

- 开机时海信在 `boot_completed` 之前拦截第三方进程：`[VtInterceptApp] intercept 3rd app.processName: app.orchard.tv(service) before boot_completed`。
- 聚好看自己靠海信私有广播 `android.intent.action.HISENSE_BOOT_COMPLETED` 启动；Aerial 的 `BootReceiver` 收不到 `BOOT_COMPLETED`，通知监听开机时也不会被绑定。
- 试过无效：`settings put system settings_startup_source_custom_pkg_key app.orchard.tv`、`cmd notification` 重绑、在默认应用里选 Aerial。
- 结果：开机先到聚好看，按一次 HOME 进 Aerial（改了上面的属性之后）。

## 信号源（还没做）

- 固件不走 Android 的 TvInput 框架：`dumpsys tv_input` 的 `inputMap`、`mHardwareList` 都是空的，`com.hisense.tv.inputs` 和 `com.jamdeo.tv.livetv` 都没有 `TvInputService`（#84）。
- `am start-foreground-service -a com.hisense.tv.start_inputs -n com.hisense.tv.inputs/com.hisense.tv.InputsUIService` 能弹出海信原生的信号源面板（`AllInputsSheet`）。必须用前台服务：普通 `startService` 会被后台启动限制挡住。
- 计划：`TvInputManager` 取不到输入、且这个服务存在时，控制中心的「信号源」改为打开这个面板。拿不到单个 HDMI，所以不能把 HDMI 加到主屏幕，也不能开机切信号源。

## 还原

- `tools/restore.sh` 用 `settings global aerial_vt_home_pkg` 里记下的原值还原 `persist.vt.use_home_pkg`。1.15 及以前是手动改的属性，没有记原值：这时如果属性是 `app.orchard.tv` 且装有 `com.jamdeo.tv.vod`，就还原成聚好看（#40 的报告人发现的，2026-10-22 起）。

## 待机唤醒后时钟暂时显示旧时间（Android 8.0 / bigfish）

2026-10-24 通过 ADB 只读检查了一台海信 `H55E9A`（`ro.product.hisense.model`；系统型号 `VIDAA_TV`，Android 8.0.0，设备 `Hi3751v811` / 平台 `bigfish`，Aerial 1.41 / versionCode 44）：

- 用户报告开机先显示关机前的时间，稍后才恢复。日志中 Aerial 的同一个进程在 02:08:08 `onStop`，09:48:00 `onStart` / `onResume`，这次是保留进程的待机唤醒。
- `auto_time=1`、`auto_time_zone=1`，时区 `Asia/Shanghai`。`dumpsys alarm` 的最后一次系统时间变更为 09:47:59.928，早于 Aerial 恢复界面；09:48:08 的系统网络校时日志也已是正确时间。
- `HomeChrome.rememberNow` 原来保留 Compose 状态，仅靠协程等到下一分钟刷新，没有响应界面恢复、亮屏或系统校时。`MainActivity.dayReceiver` 虽监听校时，却只更新壁纸。因此系统时间已经正确时，界面仍可能暂时保留待机前的值。
- 源码补丁改为前台监听 `TIME_TICK`、`TIME_SET`、`TIMEZONE_CHANGED`、`SCREEN_ON`，并在 `ON_START` / `ON_RESUME` 立即读取时间；首页和 Dream 共用这条逻辑。
- **验证边界**：以上为原版的真机日志与源码分析；`:app:compileStandardReleaseKotlin` 通过。修复版尚未安装，也未验证真实待机唤醒。当前机器缺少与已装 APK 匹配的签名密钥。未改电视设置、未卸载应用。
