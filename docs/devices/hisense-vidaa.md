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
| VIDAA_TV | Android 9，Amlogic T963（`t5fl`），1080p | 1.72 起应用内升级不弹安装界面：安装会话提交后没有回复也没有确认页（1.71 及以前用 `ACTION_VIEW` 正常）；下个版本起会话 8 秒没回复就退回系统安装器（见 ARCHITECTURE「升级」），已在 1.72–1.74 的要手动升一次 | #1004 |

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

## 4K 界面和 USB 口（Vidda G7，#896 #875）

- Vidda G7（VIDAA_TV，MT9655，板 VX5SPRO，Android 14，报告人贴的输出，2026-10-29）：`ro.vt.4KUI_ability=2.1`、`ro.vtf.4kui.mtk=1`、`ro.vt.4k_whitelist_support=1`、`2K_4K.Supported=true`、`ro.surface_flinger.max_graphics_width/height=3840/2160`，`wm size` 1920×1080，显示只有 1920×1080@60 一个模式。聚好看前台时 SurfaceFlinger 里它那一层缓冲 1920×1080、`is4kGraphicLayer=false`、DEVICE 合成：自家桌面也不是 4K 送显，4K 界面看来要进 `4k_whitelist` 才开。
- 同一位报告人的海信（MT9952，Android 14，#875）：`init.mt9952.usb.rc` 在 `sys.usb.config=adb` 时执行 `usb_otg_agent device`，OTG 控制器 `1c7a3e00.usb` 切到设备模式，接在上面的 USB 口不再认设备（2.4G 飞鼠接收器）。开机 `adb_enabled=1`，系统据此把 USB 功能设成 `adb`；`setprop persist.sys.usb.config mtp` 后重启恢复，网络 ADB 靠 `persist.adb.tcp.port=5555` + `persist.service.adb.enable=1` 维持。Aerial 不写 USB 配置，`keep_adb` 只在 `adb_enabled` 变 0 时写回 1。小米 MFTR0 是同样的规则（见 xiaomi-mitv-mftr0.md）。

## 75E8H 的音量条和亮度条（#1025，MT9652 m7642，安卓 9，VIDAA 6.6）

报告人（Agent）实测：没有 `picture_backlight`；`screen_brightness` 写得进去但面板不变；`media volume --stream 3 --set 2` 让 AudioService 的当前值变了，电视实际音量和 `volume_music` 不变，遥控器音量键正常、Aerial 面板的数字会跟着遥控器变。亮度可以在 device profile 里加 `"ignoresScreenBrightness": true`（adapt.md 已补）；音量还要看 `dumpsys audio` 里是不是 fixed volume、走 HDMI/CEC，已问。

## 激光机型的亮度（Vidda M2 Pro 高亮版，MT9653，Android 11）

2026-11-01，在 Vidda M2 Pro 高亮版（型号代码 `VL5R-PRO`；`ro.product.model` 报 `VIDAA_TV`，
MT9653，Android 11，Aerial 1.76）上实测。这台是激光超短焦投影，
此前控制中心的亮度行对它不起作用：`screen_brightness` 写得进去，画面完全不变（216 → 30 与前一张截图
逐像素无差别），和 #1025 在 75E8H 上报的现象一致。

- **真实控光在固件的 `PictureManager` 里**，不在 Android 的设置里。`com.jamdeo.tv.*` 位于
  `/system/framework/com.jamdeo.flow.jar`（boot class path），任何 App 都能直接反射调用，
  不需要权限、root 或系统签名：`TvManager.getInstance().getPictureManager(context)` 之后，
  `setCustomBacklight(0..10)` / `getCustomBacklight()` 就是原生设置「图像 → 亮度」的那 0–10 档。
  验证方式：让户主在原生设置里反复拖亮度，同时由探针每 400 ms 采样全部画质字段，只有
  `getCustomBacklight` 跟随变化、其余全程不动；写入侧同一字段同一量程，闭环成立。
- **判定激光机型用 `getLaserOverLapOrCL0Mode`**（CL0 是激光光源），普通海信没有这个方法。
  没有按 `VIDAA_TV` 判断（所有海信都报这个名字），也没有写进 `devices.json`。
- **亮度模式共 7 个，与 `getBacklightMode` 的对照**（逐个提交后回读）：

  | 模式 | `getBacklightMode` | `getCustomBacklight` | `getLaserOverLapOrCL0Mode` |
  |---|---|---|---|
  | 明亮 | 16384 | 10 | 0 |
  | 标准 | 8192 | 8 | 0 |
  | 柔和 | 2 | 4 | 0 |
  | 动态 | 4096 | 不固定（随画面，实测 7 / 3 / 5） | 0 |
  | 超新 | 131072 | 10 | 2 |
  | 超频 | 65536 | 10 | 1 |
  | 自定义 | 0 | 用户上次档位 | 0 |

- **只有「自定义」的档位归用户管**：明亮 / 标准 / 柔和 / 超新 / 超频 都写死自己的档位（动态不写死，
  它自己会动，见下一节），并且**丢弃**对 `setCustomBacklight` 的写入（回读纹丝不动，但值会记进
  自定义槽位）。所以 Aerial 写入前先 `setBacklightMode(0)`，这正是原生设置在户主拖动亮度滑杆时
  自己做的事——效果就是「一拖亮度，模式变成自定义」。切回「自定义」时系统自动恢复上次档位，
  Aerial 不需要自己记。
- 切模式只要一条 `setBacklightMode(...)`，预设档位和 `getLaserOverLapOrCL0Mode` 由系统联动写入，
  不用再单独调 `setLaserOverLapOrCL0Mode`。
- 「超新 / 超频」偏黄来自激光叠加（CL0Mode 2/1）改变合成白点，不是亮度也不是色温
  （`getColorTemperature` 七个模式恒为 4）。
- 无用的通道（勿再试）：`screen_brightness`（激光机不控光源）、`setBrightness`（只改画质黑位，
  肉眼无感）、`setLightModeStatus`（第三方关不掉，恒为 true）、`setLightModeValue` /
  `setMinBackLight` / `setLaserCL0Index`（空操作）、`setBacklightMode(8192 | v)`（被归一化，
  BacklightMode 是离散枚举而不是标志位）。

Aerial 的处理：`system/HisenseLaserPanel.kt`；`Brightness.level` / `adjust` 最前面分派，
控制中心 `steps = 10`（11 档，其余电视仍是 20 步 5%），行里显示档位而不是百分比
（`4`，不是 `40`；原生设置那一栏也是 0–10），亮度行在屏幕上时每 700 ms 轮询一次
（系统改亮度不发广播，和极米同一个理由）。面板关着时一次都不读：控制中心为首次打开
做的那个 1% 透明预热副本会把这一页也组合一遍，它的轮询被 `poll` 参数挡掉。
验证范围见 HANDOFF 里 2026-11-01 那一条。

### 亮度模式（控制中心里切换）

七个模式也做进了控制中心：亮度滑块下面一行「亮度模式」，右边跟着当前模式名，按进去是
`CcPage.BrightnessMode` 一页列表（和「信号源设置」同一套 `MenuRow` + `ChoiceRow`），
当前模式带勾、并且是进页面时的初始焦点。列表下面**不留说明文字**：七项加一个勾就是整页
（户主要求，原先那行随模式变的解释已删）。

- **「自定义」是七项里的第七项，和别的模式一样可选**（2026-11-01 在原生设置里核对：
  `设置 → 图像 → 亮度` 那一页就是 明亮 标准 柔和 动态 超新 超频 自定义 七颗胶囊，
  全部 `enabled=true`、`focusable=true`）。在「标准」（8 档）下选中「自定义」，
  档位立刻回到上次的 4 档 —— 这是固件行为，Aerial 只是写 `BM=0`，不自己记档位。
- **七个模式各自怎么对待档位**（户主逐项核对，2026-11-01）：

  | 模式 | 进入后的档位 | 可调 | 调亮度之后 |
  |---|---|---|---|
  | 明亮 | 10 | 是 | 转「自定义」 |
  | 标准 | 8 | 是 | 转「自定义」 |
  | 柔和 | 4 | 是 | 转「自定义」 |
  | 动态 | **由系统定**（实测 8 / 9 / 5） | 是 | 转「自定义」 |
  | 超新 | 10 | **否** | 保持 超新 |
  | 超频 | 10 | **否** | 保持 超频 |
  | 自定义 | 户主上次的档位 | 是 | 保持 自定义 |

  档位全部由固件写、Aerial 只读，所以界面上不写死数字，亮度行自己就显示真实档位。
  动态不钉固定档位，也不固定保持原值（从柔和 4 切进去读到 8、从标准 8 切进去仍是 8、
  从明亮 10 切进去读到 9），所以它既不写数字也不写「保持不变」。
- **超新 / 超频 的滑杆是灰的**：这两项下 `设置 → 图像 → 亮度` 那一行 `enabled=false`、
  `focusable=false`（值 10），焦点根本进不去。Aerial 照这个锁定：亮度行降到 40% 透明度、
  左右键只发 `Sounds.limit()` 的「到头」声而不写任何值（`HisenseLaserPanel.setLevel` 里也挡了一道，
  `Brightness.adjust` 直接返回当前档位），所以在超新下按左不会突然掉进「自定义」。
- 锁定由 `levelLocked(i)` 判定（只有 4、5 两项为 true）。
- **为什么是一页而不是滑块旁边一排胶囊**：七个中文标签排不进 300dp 宽的面板；更要紧的是
  亮度滑块的左右键已经是「一档」，旁边再放一排可选中的胶囊，同一个方向键就有了两种含义。
  拆一页之后，模式用 OK 选、亮度用左右调，互不干扰。
- **切模式只要一条 `setBacklightMode(MODE_BACKLIGHT[i])`**，档位和激光叠加由固件联动写入，
  所以选完一个预设，亮度行的数字会跟着变（明亮 10、标准 8、柔和 4、动态 9、超新 / 超频 10）。
- 反过来，在预设模式下拖亮度会转成「自定义」（`setLevel` 先写 `BM=0`），模式行的名字也会
  在下一轮轮询里跟着改。
- 模式和亮度一起读（`Brightness.mode` / `level` 同一次轮询），因为一个预设自带档位，
  两者总是一起变；模式页开着时同样每 700 ms 读一次，实测从别的 app 改档位后，
  页面上的勾会自己挪到「自定义」。
