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
| VIDAA_TV（70V1F-S，`NOVA72691`） | Android 9，NT72691（Novatek n62020），armv7 | 装得上；不 root 的所有接管路径都失败（`set-home-activity` 无效、第三方 HOME priority 被钳成 0、启动指针无效、无障碍守护收不到窗口事件、禁用聚好看桌面组件会让开机走不完）；root 后把桌面装成系统应用（priority=100）主页键可直达，开机仍先画聚好看约 8–10 秒 | 本次报告（2026-10-29） |

Vidda C1S 激光投影（VIDAA_TV，MT9669，Android 9，PR #545）：聚好看（`com.jamdeo.tv.vod`）不向第三方无障碍服务发窗口事件，别的 App 的事件照常；`set-home-activity` 写了 preferred 也不生效；用量统计里有聚好看的 `MOVE_TO_FOREGROUND`。1.49 起原桌面里有聚好看就每秒查一次用量统计（要 `GET_USAGE_STATS`，安装命令会给），报告人实测从 B 站按主页键 1–2 秒回到 Aerial。

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

## 信号源（较新固件）

- 固件不走 Android 的 TvInput 框架：`dumpsys tv_input` 的 `inputMap`、`mHardwareList` 都是空的，`com.hisense.tv.inputs` 和 `com.jamdeo.tv.livetv` 都没有 `TvInputService`（#84）。
- `am start-foreground-service -a com.hisense.tv.start_inputs -n com.hisense.tv.inputs/com.hisense.tv.InputsUIService` 能弹出海信原生的信号源面板（`AllInputsSheet`）。必须用前台服务：普通 `startService` 会被后台启动限制挡住。
- 计划：`TvInputManager` 取不到输入、且这个服务存在时，控制中心的「信号源」改为打开这个面板。拿不到单个 HDMI，所以不能把 HDMI 加到主屏幕，也不能开机切信号源。

## H55E9A 的 HDMI（Android 8 / bigfish）

2026-10-24，海信 H55E9A（系统型号 `VIDAA_TV`，设备 `Hi3751v811`，Android 8.0.0 / SDK 26，固件 `03.02.60.02`，Aerial 1.41 standard）：

- HDMI 3 已接设备，但标准 `tv_input` 的输入和硬件列表都是空的，也没有 `hdmi_control` 服务。新版海信的 `com.hisense.tv.inputs` 没装，旧的 `com.jamdeo.action.START_INPUT_SOURCE` 也解析不到 Activity。
- 原生接口是 `com.jamdeo.tv.TvManager.getInstance().getSourceManager(context)`，通过异步绑定 `com.jamdeo.tv.service/.SourceService` 提供数据。`getInputSources()` 实测返回 `[257, 513, 1281, 1025, 2561, 1282, 2562, 1283]`；`getInputSourceName(1283)` 为 `HDMI3`，`getConectedSourcesArray()` 为 `[1283]`。
- `HisenseSources` 只在原生服务存在且可访问时启用，从设备报告的列表和名字中提取 HDMI，过滤 LiveTV、OnDemand 等虚拟源，不按型号写死端口数量。端口列表首次可能尚未就绪，只在工作线程短时等待；不在主线程阻塞。
- 切换使用发给 `com.hisense.tv.tiletransition` 的 `com.jamdeo.tv.policy.SILO_SWITCH_EVENT` 广播，整数 extra 为 `silo_input_src_id`。普通权限的临时诊断包发送 `1283` 后，前台变为 `com.jamdeo.tv.livetv/.LiveTvActivity`，原生当前源从 `2562` 变为 `1283`，户主确认看到 HDMI 3 的设备画面。单独启动 LiveTvActivity 不能代替选择端口。
- 验证范围：临时诊断包内包含实际编译出的 `HisenseSources`，新进程首次读取耗时 118 ms，列出 HDMI 1–3，只有 HDMI 3 为已连接；同一份代码发送切换广播成功。原生接口首次切换时户主确认设备画面正常。临时包和设备上的临时文件已删除。完整 Aerial 的信号源页、固定磁贴和开机输入尚未上机验证，当前机器没有与已装版本匹配的签名密钥。没有覆盖 Aerial 或清除其数据。

## VPN 下的网络类型（H55E9A）

2026-10-27，H55E9A（Android 8.0.0 / SDK 26，固件 `03.02.60.02`）验证 #435 的检测修复：

- 使用独立包名、没有 HOME 和守护服务的临时 release 诊断包，直接编译当前 `SystemProbe.network()` 和 `GlyphIcon`，执行 `cmd package compile -m speed -f` 后测试；不是重写一份检测逻辑。
- Wi-Fi 连接、VPN 关闭时返回 `Wifi`；开启现有 VPN 客户端后，应用的默认网络只报告 `TRANSPORT_VPN`，另一个物理网络报告 Wi-Fi 且 `isConnected=true`，检测仍返回 `Wifi`，名称可读、信号强度正常，截图显示 Wi-Fi 图标。
- VPN 已开启时结束诊断进程再冷启动，首次读取及默认网络回调同样返回 `Wifi`；关闭 VPN 后恢复为 Wi-Fi 默认网络，结果不变。未使用网络的 `VALIDATED` 能力判断连接类型。
- 79 个单元测试通过，其中 16 个网络测试覆盖断开的候选网卡、双连接顺序、VPN 继承的 transport、未知 SSID 和原有兜底行为。有线及双物理连接没有真机条件，仅做自动化验证。
- **验证边界**：电视原有 Aerial 1.50 与本机签名不同，没有覆盖安装或卸载原桌面，所以完整首页、控制中心和 Activity 生命周期仍需使用匹配签名的完整包回归。此次只确认实际检测代码及原有图标在诊断包中的行为。临时包和电视上的测试文件已清理，VPN 恢复关闭，未修改订阅、节点、路由配置或户主数据。
- standard / guard release 构建通过；全量 `lintStandardRelease` 仍有 22 个非本次网络文件的错误（包括 `UhdCanvas` 的 API 兼容性、Media3 opt-in 等），未在此次修复中顺带修改。

## 控制中心网络入口（H55E9A，#760）

2026-10-28，在 H55E9A（Android 8.0.0 / SDK 26）确认：`com.android.tv.settings`
同时包含 Android TV 的 `connectivity.NetworkActivity` 和海信自己的
`com.hisense.systemsettings.MainActivity`。原来的候选顺序先打开前者；#760 的用户报告
它的连接页或高级选项崩溃，海信自己的设置页能用。

- 海信入口：`android.settings.WIFI_SETTINGS`，显式组件
  `com.android.tv.settings/com.hisense.systemsettings.MainActivity`。从 Aerial 上方启动时，
  截图确认直接弹出海信自己的 Wi-Fi 列表，不需要先在总设置中选择网络。
- `SystemProbe` 和 Lite 的 `SystemIntents` 仅在该组件存在、启用且导出时优先使用它，
  不按品牌名或 `VIDAA_TV` 型号判断。其他电视的候选顺序不变；完整版保留原有启动失败和闪退回退机制。
- 自动化：88 项单元测试通过，新增 7 项覆盖海信优先级、未安装/未导出/禁用时的回退、
  小米有线/无线优先级、TCL 标准 Wi-Fi action，以及实际启动 action 和通用设置入口不变。
  standard、guard、Lite release 构建及 Lite lint 通过。
- 普通应用权限验证：独立包名的 release 诊断包直接编译当前 `SystemProbe`，执行 speed 编译后，
  系统日志确认由诊断包的普通应用 UID 发起上述显式 Wi-Fi action，海信设置页收到真实遥控器
  返回键后回到诊断页。测试包及电视上的临时文件已清理，没有改变默认 HOME 或网络配置。
- 验证边界：本机没有与电视上 Aerial 1.61 匹配的签名密钥，未覆盖安装或卸载原桌面。
  完整控制中心点击流程、连接/更换 Wi-Fi 和高级选项未验证；#760 的另一台
  85E5N Pro+（MT9653）以及 Android 4.x 的 Lite 尚未上机验证。

## 还原

- `tools/restore.sh` 用 `settings global aerial_vt_home_pkg` 里记下的原值还原 `persist.vt.use_home_pkg`。1.15 及以前是手动改的属性，没有记原值：这时如果属性是 `app.orchard.tv` 且装有 `com.jamdeo.tv.vod`，就还原成聚好看（#40 的报告人发现的，2026-10-22 起）。

## 待机唤醒后时钟暂时显示旧时间（Android 8.0 / bigfish）

2026-10-24 通过 ADB 只读检查了一台海信 `H55E9A`（`ro.product.hisense.model`；系统型号 `VIDAA_TV`，Android 8.0.0，设备 `Hi3751v811` / 平台 `bigfish`，Aerial 1.41 / versionCode 44）：

- 用户报告开机先显示关机前的时间，稍后才恢复。日志中 Aerial 的同一个进程在 02:08:08 `onStop`，09:48:00 `onStart` / `onResume`，这次是保留进程的待机唤醒。
- `auto_time=1`、`auto_time_zone=1`，时区 `Asia/Shanghai`。`dumpsys alarm` 的最后一次系统时间变更为 09:47:59.928，早于 Aerial 恢复界面；09:48:08 的系统网络校时日志也已是正确时间。
- `HomeChrome.rememberNow` 原来保留 Compose 状态，仅靠协程等到下一分钟刷新，没有响应界面恢复、亮屏或系统校时。`MainActivity.dayReceiver` 虽监听校时，却只更新壁纸。因此系统时间已经正确时，界面仍可能暂时保留待机前的值。
- 源码补丁改为前台监听 `TIME_TICK`、`TIME_SET`、`TIMEZONE_CHANGED`、`SCREEN_ON`，并在 `ON_START` / `ON_RESUME` 立即读取时间；首页和 Dream 共用这条逻辑。
- **验证边界**：以上为原版的真机日志与源码分析；`:app:compileStandardReleaseKotlin` 通过。修复版尚未安装，也未验证真实待机唤醒。当前机器缺少与已装 APK 匹配的签名密钥。未改电视设置、未卸载应用。

## 系统屏保起不来（VIDAA X Pro MT9655 Android 14，85V1KD-S T963D4 Android 11）

- 屏保设成 Aerial 的 Dream 后，Aerial 用 Somnambulator 请系统起屏保，系统没有起：Aerial 30 毫秒后又回到前台（#525 的日志）。用了航拍壁纸时，壁纸的播放器已经为屏保让出解码器，于是停住（#533）。
- 1.48 起：请求后 1.5 秒内 Aerial 仍在前台且有焦点，就改放 Aerial 自己的屏保，这个进程里不再请系统起。没在海信真机上验证。

## 海信 70V1F-S（`VIDAA_TV` / `NOVA72691`，Android 9，NT72691）

2026-10-28/29 在一台实机上做的完整验证。维护者没有这台机器，以下全部来自报告人的实测日志和命令输出。
写得比较长，是因为这台固件的几个失败点**长得像"工具坏了"，实际是固件不让第三方窗口当桌面**，
记下来可以省掉后来人重复踩。

### 机器信息

- `VIDAA_TV` / `NOVA72691`，零售型号 70V1F-S（BOM 0008），Android 9 / API 28。
- 指纹 `Hisense/VIDAA_TV/NOVA72691:9/PPR2.180905.006.A1/04.02.60.03:user/release-keys`，软件版本 `04.02.60.03`，
  安全补丁 `2021-01-05`，内核 `4.9.118+` #1（2024-02-20 编译）。
- SoC：Novatek `n62020`（NT72691），只有 `armeabi-v7a`（32 位）。
- `ro.adb.secure=1`、`persist.adb.tcp.port=5555`；`ro.boot.selinux=permissive`。
- 原桌面：`com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity`（聚好看），HOME priority 50。

### 结论速览

- **不 root：这台机器上接管不了主页键。** 下面列的 7 条路径全部实测失败，不是配置没调对。
- **不要禁用聚好看的桌面组件。** 它不会把 HOME 交出来，只会让开机永远走不完（见「开机被搞挂的原理」）。
- **root 之后可行**：桌面装成系统应用 + HOME filter `priority=100`，主页键直接进；开机仍先画聚好看约 8–10 秒，
  用一个只在开机跑 90 秒的脚本重试切换即可。
- 还有一条**没测**的线索：`persist.vt.use_home_pkg`（Android 11 机型 #40 可用），见文末。

### 不 root：试过什么、结果如何

| # | 尝试 | 命令 | 结果 |
|---|---|---|---|
| 1 | 看 HOME 候选 | `cmd package query-activities --brief -a MAIN -c HOME` | 聚好看 `priority=50`，第三方桌面 `priority=0` |
| 2 | 指定首选桌面 | `cmd package set-home-activity --user 0 <桌面>/.MainActivity` | 返回 `Success`，**不生效**；只写进 `always="false"`，HOME 仍进聚好看 |
| 3 | 改厂商"自定义启动包" | `settings put system settings_startup_source_custom_pkg_key <桌面>`（`startup_source_is_custom` 本来就是 `1`） | **无效**，两次预置重启后开机仍解析到聚好看 |
| 4 | 禁用聚好看桌面组件 | `pm disable --user 0 com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity` | 主页键失效，**并且开机永远走不完**（见下节） |
| 5 | 换系统自带兜底桌面 | `pm enable com.android.settings`（放出 `.FallbackHome`）+ 禁用聚好看组件 | 开机会解析到 `FallbackHome`，但它发现没有真桌面就自己 finish 再被拉起，循环，开机仍走不完 |
| 6 | 无障碍守护 | 第三方无障碍服务监听 `TYPE_WINDOW_STATE_CHANGED` | 服务能绑定，但**收不到聚好看的窗口事件**（与 #545 Vidda C1S 一致） |
| 7 | 用 adb 模拟主页键 | `input keyevent 3` | **被固件吞掉**：无论聚好看组件启用还是禁用，logcat 里都没有任何 `START`。主页键只能用实体遥控器验证 |

另外，海信在 `boot_completed` 之前会拦截第三方进程（`VtInterceptApp`），第三方 `BootReceiver` 收不到
`BOOT_COMPLETED`，所以"开机自启 + 自己抢前台"这条路在本机也不成立。

### 不 root 能做的（实用建议）

既然主页键抢不过，就**别跟聚好看抢，保留它当默认桌面**：

1. Aerial 正常安装、正常运行，只是别当默认桌面；
2. 给用户一个次级入口：桌面第一排放 Aerial 图标，或把遥控器上的自定义键（海信 `rc_*_key_action`
   一类映射，视固件而定）指向 Aerial；
3. **绝对不要**为了"抢桌面"去 `pm disable` 聚好看的桌面组件——那会直接卡开机（见下节）；
4. 精简/去广告这类操作不受影响，可以照常做，本文只是说"默认桌面"这一项在本机无解。

### 开机被搞挂的原理（重要）

正常开机发出的是一条**隐式** HOME 启动：

```
I ActivityManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME]
  flg=0x10000100 cmp=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity} from uid 0
```

把聚好看的 `LauncherActivity` 组件禁用后，**这条 START 根本不会发出**。没有应用窗口被绘制 → 开机动画拿不到第一帧
→ 不退出 → `sys.boot_completed` 一直是空。实测两次开机、每次每 15 秒采样、共 4 分钟，全部为空。

它不是"找不到桌面"，而是"没有任何窗口可画"。反证：启用系统自带的 `com.android.settings/.FallbackHome` 后，
开机会解析到它（说明走的确实是隐式 HOME intent）：

```
START u0 {act=MAIN cat=[HOME] cmp=com.android.settings/.FallbackHome} from uid 0
```

但 `FallbackHome` 没有真桌面接力，会反复 finish/重启，开机照样走不完。

**所以：这台机器上禁用聚好看桌面组件 = 开机卡死**，而且 `adbd` 在 `/system` 里，
一旦 `/system` 出问题 adb 也起不来。测试时务必先准备一个 root 侧的恢复脚本（重新 `pm enable` + 拉起 HOME intent）。

### root 方案（完整步骤）

#### 1. 拿到 root（本机专属，不可移植）

海信的 `autorun` 服务在 `/init.hisense.rc` 里是 `user root` + `class main` + `oneshot`，
它执行的 `/system/bin/autorun.sh` 末尾会解包 `/data/local/misc/Arun.zip` 到 `/tmp`、`chmod 777 /tmp/run.sh`
并执行。`/data/local/misc` 是 `0777 shell:shell`，adb 直接可写：

```console
adb push hook.tar /data/local/misc/Arun.zip     # 是 tar 不是 zip，里面放 run.sh
adb shell setprop ctl.start autorun             # 约 15 秒后以 uid 0 执行
```

#### 2. 桌面装成系统应用

```console
mount -o remount,rw /                     # root shell 内执行
mkdir -p /system/priv-app/TvHome
cp /data/local/tmp/TvHome.apk /system/priv-app/TvHome/com.local.tvhome.apk
chown -R 0:0 /system/priv-app/TvHome
chmod 755 /system/priv-app/TvHome
chmod 644 /system/priv-app/TvHome/com.local.tvhome.apk
restorecon -R /system/priv-app/TvHome
sync && mount -o remount,ro /
```

两个坑：

- **APK 文件名必须等于包名**（`<包名>.apk`），否则整目录被拒：
  `W PackageManager: Failed to parse /system/priv-app/TvHome: No packages found in split`
- 目录权限要是 `0755`（root 的 umask 若为 077 会生成 `0700`）

#### 3. HOME filter 给 priority=100

```xml
<intent-filter android:priority="100">
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.HOME" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

成了系统应用之后 priority **不再被钳成 0**：`query-activities` 显示 `priority=100`，`resolve-activity`
返回它，**实体遥控器按主页键直接进入**（报告人实测，不再闪聚好看）。

确认安装生效：

```console
adb shell pm path com.local.tvhome
# package:/system/priv-app/TvHome/com.local.tvhome.apk
adb shell dumpsys package com.local.tvhome | grep -m1 flags=
# flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

#### 4. 开机仍会先画聚好看（8–10 秒）

固件在任何第三方组件可启动之前就先起聚好看；实测第三方包在 `boot_completed` 之后约 **8–10 秒**才可启动，
这之前 `am start` 会报：

```
Error: Activity class {com.local.tvhome/com.local.tvhome.MainActivity} does not exist.
```

用一个"只在开机跑 90 秒"的钩子轮询并重试即可，跑完退出、不留常驻进程：

```sh
VODPKG=com.jamdeo.tv.vod
TVH=com.local.tvhome/.MainActivity
pm enable --user 0 com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity   # 保证开机画得出界面
k=0
while [ $k -lt 90 ]; do
  k=$((k+1))
  case "$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)" in
    *com.jamdeo.tv.vod*)
      i=0
      while [ $i -lt 6 ]; do
        i=$((i+1)); am start -n "$TVH"; sleep 2
        case "$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)" in
          *tvhome*) break ;;
        esac
      done
      sleep 1; am force-stop "$VODPKG"     # 顺手回收 ~180 MB
      break ;;
  esac
  sleep 1
done
```

#### 5. 内存

`am kill` 对聚好看无效（常驻 KeepAliveService 兜住进程）；`am force-stop com.jamdeo.tv.vod` 实测把 PSS
从 179–217 MB 降到 0，`MemAvailable` 从约 678 MB 回到约 834 MB，且不影响下次开机由系统重新拉起。

### 排障命令

```console
cmd package query-activities --brief -a MAIN -c HOME     # 谁在候选、什么优先级
cmd package resolve-activity --brief -a MAIN -c HOME     # 现在会选谁
logcat -b system -d | grep 'START u0' | grep -i home     # 开机到底起了谁
getprop sys.boot_completed                                # 启动有没有走完
ps -A | grep com.jamdeo.tv.vod                            # 聚好看进程还在不在
dumpsys meminfo com.jamdeo.tv.vod | grep -m1 TOTAL        # 它占多少
```

### 给后来人的经验清单

1. **别禁用聚好看的桌面组件**——开机动画要等第一帧窗口，禁了就是卡死，不是"换个桌面"。
2. **`input keyevent 3` 不可信**：本机固件会吞掉注入的主页键，必须用实体遥控器验证。
3. **`set-home-activity` 会骗你**：返回 `Success`，实际只写 `always="false"`。
4. **第三方 HOME 的 priority 会被钳成 0**，这是"为什么装了却抢不到"的直接原因。
5. **系统应用是分水岭**：装进 `/system/priv-app` 且文件名=包名之后，priority 才保得住。
6. **开机有 8–10 秒的组件不可用窗口**，任何"开机就拉起"的方案都要带重试。
7. **`adbd` 在 `/system` 里**，动 `/system` 前先备份、先准备好恢复手段。
8. **留一个看门狗**：180 秒内没等到 `boot_completed` 就自动 `pm enable` 聚好看组件并拉起 HOME，
   测试期间它救了三次。

### 未验证

- **`persist.vt.use_home_pkg`**：Android 11 / MT9653 的机型（#40）用它就能直接指定主屏幕包，不用 root。
  本机 Android 9 是否存在这个属性、是否生效**还没测**——这是下一步最值得试的一条：
  ```console
  adb shell getprop persist.vt.use_home_pkg
  adb shell setprop persist.vt.use_home_pkg <你的桌面包名>
  ```
  如果有效，可以彻底省掉 root 和那 8–10 秒的开机聚好看。
- 未评估任何厂商包的精简清单，也不建议照搬其他海信主板的禁用列表。
- 本文不含序列号、IP、About 页截图。
