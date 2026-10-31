# TCL tcl_mt5879_cn（国行，雷鸟系统）

- Android 11（SDK 30），build RQ3A.210705.001，MediaTek MT5879，32 位 ARM，约 4 GB 内存。
- 1920×1080，**240dpi**（1280×720dp）。Aerial 的画布缩放会让它和小米显示得一模一样。
- 显示模式：1080p 下 50 / 60 / 100 / 120Hz，平时是 60Hz。Android 11 说不出哪些切换不黑屏，所以 Aerial 不切。1.15 导航帧耗时中位数 8ms、90 分位 10ms（2026-10-21）。
- 240dpi 下 `loadIcon` 给的是 72px 的图标，所以磁贴图要取 App 自带的最高密度。
- 原桌面：`com.tcl.cyberui/.MainActivity`（下述 guard 配置中唯一的 HOME 候选）。
- 原有实测配置的 flavor：`guard`。另有同型号、固件 `2026012200` 的 standard 默认 HOME 实测，见下文；不要把两台的配置混用。

## 安装限制：系统校验链

`adb install` 总是返回 `INSTALL_FAILED_VERIFICATION_FAILURE`。系统必经的校验器是 TCL 改过的 `com.android.packageinstaller/.verifier.VerifierReciver`，策略链（`InstallStrategyManager`，配置由云端下发，见 `InstallConfigManager.registerOnlineConfig`）：

| 策略 | 作用 |
|---|---|
| `PmStrategy` | 通过安装标志识别 ADB / pm 安装，**一律拒绝** |
| `LauncherStrategy` | APK 声明了 MAIN + HOME 时：包名在云端白名单里就放行；否则签名要和**当前默认桌面**一致（没有默认桌面时比 `com.tcl.cyberui`），并且带 `meta-data com.tcl.app.type = com.tcl.ui`，两条都满足才放行（见下） |
| `ThridStrategy` | 只放行由系统应用或 `com.android.packageinstaller` 发起的「显式安装」 |
| `BlackStrategy` / `OverDueStrategy` / `SafeStrategy` | 黑名单 / 过期 / 放行 |

还试过但不行的办法：
- `pm install -i <各种 installer>`：同样被拒。
- 从 shell 调起 `InstallStart`：报错 `uid 2000 needs to declare REQUEST_INSTALL_PACKAGES`，然后直接关闭。
- TCL 应用商店 `com.tcl.appmarket2` 的 `android.intent.action.APPSTORE_INSTALL_APK` 广播（`ThirdAppManagerReceiver`）：只按包名从商店升级，`Fileurl` 以 `apk` 结尾的直接忽略，装不了本地文件。
- 媒体中心 `com.tcl.ui_mediaCenter`（系统 App，自己的文件浏览里能装 APK，见 `PackageInstallUtils`）：可以从 adb 直接打开某个目录（`-n com.tcl.ui_mediaCenter/.main.FileListActivity --es extra_title Download --es extra_device_path /storage/emulated/0 --es extra_path /storage/emulated/0/Download --ei extra_device_type 3 --ei extra_media_file_type 64`），但内置存储的目录一直卡在加载，只对 U 盘有效。
- 安全卫士 `com.tcl.guard` 能装 U 盘里的 APK（户主确认过），没有找到能传本地路径的入口。

`LauncherStrategy.isTclLauncher()` 的顺序（2026-10-30 从本机 `PackageInstaller.apk` 反编译，#983）：取 `getHomeActivities()` 的包名，为空时用 `com.tcl.cyberui`（再没有就用 `com.android.providers.settings`）；新 APK 的签名和它比，不一致就拒绝；再看 application 的 `com.tcl.app.type` 是不是 `com.tcl.ui`。所以 standard 版已经是默认桌面时，更新比对的是 Aerial 自己的签名，能过；1.72 之后的 standard 版带了这个 meta-data，应用内更新应该就能装（待 #983 报告人确认）。Aerial 不是默认桌面时（首次安装、guard 用户）比的是 TCL 桌面的签名，仍然过不了。

能过校验的关键是「由 App 把 APK 交给系统安装界面」：当贝不是系统 App 也能装成功，说明 `ThridStrategy` 只拦 adb / pm 直接安装。

**可行的安装方法一：多屏互动推送**（`tools/install.sh` 默认走这条；协议实现在 `tools/tcast-push.py`）

TCL 手机 App 有一个「把手机里的 APK 推到电视」的功能。电视端由多屏互动 `com.tcl.MultiScreenInteraction_TV`（系统 uid 1000）接收，下载后用 `PackageInstaller` session 静默安装，能通过 TCL 的安装校验。2026-10-20 在这台电视上实测，1.6 升到 1.7 成功。

- 协议（从多屏互动 5.30 反编译得到，类 `bf.d`、`com.tcl.tcast.wrapper.data.l`、`ManageAppCmd`；字符串用 `ea.b.a(base64, key)` 做 XOR 混淆）：
  - TCP 端口 6553。每条消息是「4 字节大端长度 + UTF-8 文本」，字段之间用 `>>` 分隔。
  - 握手：第一条消息发 `159>>1>>0`，表示不加密。电视会回 `253>>1>>0`，以及一条 `159>>…` 设备信息。
  - 心跳：发 `150>>`，电视回 `150>>YES`。会话安静太久会被电视断开。
  - 安装指令：`223>>INSTALL>>包名>>名字>>下载地址>>2>>versionCode>>大小MB>>>>>>>>apkPathMd5`。来源填 `2`（本地）并带上 `apkPathMd5`，就会跳过商店查询，也跳过版本和签名检查，直接在电视右上角弹「多屏互动：正在发起应用推送，同意后将接收应用并进行安装 [拒绝] [同意]」。
  - 回复：`223>>INSTALL>>…>>confirm_pending|denied`；同意之后会连续收到 `223>>PROGRESS>>包名>>名字>>阶段>>百分比>>…`，阶段 1 是下载中，3 是安装中，5 是装好了，2 是失败。
- 下载地址用的是 `adb reverse tcp:8765 tcp:8765` 加上 Mac 本机 `127.0.0.1` 的 HTTP 服务，电视下载的是 `http://127.0.0.1:8765/Aerial.apk`，所以不需要在局域网上开端口。
- 用遥控器操作时：提示框出现后焦点在「拒绝」上，要先按「右」再按 OK。
- 有 ADB 时不用人去点：提示框是多屏互动的悬浮窗，`uiautomator dump` 能看到「同意」按钮 `com.tcl.MultiScreenInteraction_TV:id/btn_positive`，对它的中心点 `input tap` 就会开始安装（2026-10-21 实测，按钮在 `[1650,240][1858,304]`）。
- 同一个 versionCode 也可以覆盖安装。
- 其他指令（发 Intent、截图、模拟按键、应用列表）和加密握手见 [../tcast-protocol.md](../tcast-protocol.md)。

**方法二：当贝**（多屏互动不在或 6553 端口不通时，`install.sh` 会自动回退到这条；只用电视上已经装着的当贝，不要为此去装当贝或别的应用商店）
1. `adb push app-guard-release.apk /sdcard/Download/Aerial.apk`，然后触发媒体扫描（`content call --uri content://media --method scan_volume --arg external_primary`）。
2. 打开当贝市场的安装包管理：`am start -n com.dangbeimarket/.ui.apkmanager.ApkManagerActivity`。
3. 选中 Aerial →「安装应用」→（首次安装会提示「应用未被认证」，选「继续」）→ 系统安装器的「安装」。升级时只有最后一步。
4. 用遥控器操作时：列表第一个通常是别的 APK，按「右」到 Aerial；在系统安装器里按 4 次「下」再按「右」，焦点才会到「安装」。
5. **versionCode 必须比已装的大**，否则当贝会把它从列表里隐藏。
6. 每一步都先截图确认焦点再按 OK。当贝左侧有「批量删除」和「一键清空」，列表还没加载出来时焦点会停在那里。

**方法三：Aerial 自己的「下载并安装」**（2026-10-22 实测，从自称 1.30 的 guard 包升到官方 1.31-guard）：Aerial 用 `ACTION_VIEW` 把 APK 交给 `com.android.packageinstaller`，和当贝一样能过校验。这台没有「安装未知应用」设置页（`ACTION_MANAGE_UNKNOWN_APP_SOURCES` 解析不到），`REQUEST_INSTALL_PACKAGES` 要由 `install.sh` 用 appops 放行。安装界面用遥控器要按 4 次「下」再按「右」才到「安装」；装完弹「应用安装已完成，是否立即体验？」，选「暂不体验」时露出 TCL 原桌面 `com.tcl.cyberui`，守护照常拉回 Aerial。

## 打开 ADB

设置 → 关于 → 本机信息，在这个页面依次按「上、下、左、右」（`DeviceInfoItemModel.HIDE_KEY_LIST = {19, 20, 21, 22}`，两次按键间隔要小于 1 秒），页面会刷新出隐藏的「商场」和「更多」分组，「更多」里有「ADB」开关。2026-10-20 在这台电视上实测有效。注意刷新后焦点会落在「商场模式」开关上，不要直接按确认。

## 开机自启：TCL 的自启动管理

- 冷启动后，第三方 App 的服务绑定、广播接收和进程启动，都要经过 `TclAppBootManagerImpl`（在 `/apex/com.tcl.tcore/javalib/tcl-service.jar` 里，由 `ActiveServices` 调用 `getTclAppBootManagerInjector().canBindServiceGo`）。默认会拒绝，日志里是 `ActivityManager: forbid bind service app.orchard.tv/...HomeGuardService`，所以开机后停在 TCL 桌面。
- 放行条件是两个 TCL 自定义的 AppOps：`APP_AUTO_START`（op 100，系统拉起）和 `APP_ASSOC_START`（op 101，其他 App 关联拉起）。默认分别是 `ignore` 和 `default`。`install.sh` 用 `appops set app.orchard.tv APP_AUTO_START allow`（以及 `APP_ASSOC_START`）放行，`restore.sh` 会改回 `default`。
- 2026-10-20 实测：放行后 `adb reboot`，开机约 0.5 秒通知监听就被绑定，Aerial 被拉起，守护服务接管桌面。
- 在上述 guard 配置中试过但没用的办法：`settings put system boot_app_mode boot_launcher` 加上 `boot_app_componentname app.orchard.tv/.MainActivity`（TCL「开机进入」的设置，由 `com.tcl.ttvs` 的 MMMService 管理），重启后还是进 TCL 桌面，已改回空值。
- TCL 开机时有可能把 `screensaver_components` 写回「艺术画报」（实测出现过一次）。所以 `install.sh` 接管屏保时，会通过 ConfigReceiver 发送 `own_screensaver=true`，之后 Aerial 每次启动都会检查，被改回去就再改成 Aerial（`GuardSetup.keepScreensaver`）。

## HOME 与守护

- 和小米一样，靠桌面守护接管。待机唤醒后会自动回到 Aerial（实测）。
- 同样是 cyberui、多屏互动装 guard 版的 TCL tc8000_ay30a2（Amlogic，Android 11）：守护运行时，遥控器关机后再也唤不醒，只能拔电；关掉守护就正常（#371，报告人做了对照实验）。怀疑是守护的按键过滤让唤醒的电源键经过了 Aerial 的进程。1.41 起屏幕关闭时不过滤按键，亮屏后再开，等报告人复测。1.43 复测仍唤不醒，关掉应用冻结也一样（#371），同型号刷回 V472 固件正常、V745 才有（#132）。1.44 加了可选的「待机时守护退出」：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez guard_off_in_standby true`。息屏时守护用 `disableSelf()` 把自己移出无障碍列表（Android 7 以下改写设置），亮屏时由挂在进程上的接收器加回，加回后照常检查前台、拉回原桌面；进程在待机中被杀时，下一次启动（通知监听重绑、同步）发现还在息屏就只挂接收器，不提前加回。我们的 MT5879 上实测：按待机后 0.5 秒内守护已移出、walleve 两个服务保留；遥控器唤醒后守护加回、walleve 仍在、前台是 Aerial（2026-10-24，测完把选项关回去了）。
- **升级 App 后，守护服务处于「已启用但没绑定」状态**。`GuardSetup.rebindIfStalled` 会先移除、1.5 秒后再加回，触发重新绑定（两次写入挨得太近会被系统合并，不起作用）。
- TCL 自己有两个无障碍服务（`com.tcl.walleve`，「小T助手」），改 `enabled_accessibility_services` 时一定要保留。
- 待机时网络 ADB 会断，但 adbd 不会被关；唤醒后能直接重连。

## 另一台同型号：standard 默认 HOME（固件 2026012200）

这份补充记录来自另一台 Android 报告型号为 `tcl_mt5879_cn` 的电视，Android 11 / SDK 30，
build `RQ3A.210705.001`、incremental `2026012200`，MT5879 / 32 位 ARM / 约 4 GB 内存。
商品型号没有确认，不把平台名当作零售型号。这台已安装官方 `standard`，
`app.orchard.tv/.MainActivity` 是真实默认 HOME；上文的 guard 方案仍适用于无法注册 HOME 的配置。

### 默认 HOME 与开机目标要一致

这台原来的 Android HOME 已是 Aerial，但 `Settings.System` 里仍保存：

```text
boot_app_mode=boot_launcher
boot_app_componentname=com.tcl.cyberui/.MainActivity
```

本机日志和 `TclBootAppPolicy` 显示，TCL 开机策略会另外打开保存的组件；组件与默认 HOME
一致时不再覆盖，由 Android 启动默认 HOME。这解释了为什么已选 Aerial 仍会先进原桌面。
**这和 guard 没有 HOME 声明时只写开机字段的情况不同。**

仅针对这台已成功安装 standard 的配置，先保存原值和 HOME 查询结果，再设置：

```bash
adb shell settings get system boot_app_mode
adb shell settings get system boot_app_componentname
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT

adb shell cmd package set-home-activity app.orchard.tv/.MainActivity
adb shell settings put system boot_app_mode boot_launcher
adb shell settings put system boot_app_componentname app.orchard.tv/.MainActivity
```

字段在 **system**，不是 global。2026-10-25 分两步验收：

1. 只纠正开机组件，保持 CyberUI 启用：重启后直接启动 Aerial，没有 CyberUI Activity 启动。
   所以停用原桌面不是开机直进 Aerial 的前提。
2. 之后另做了停用 CyberUI 的配置，并移除它的通知监听授权；用户拔电再通电后，
   HOME 仍直接启动 Aerial，日志无 CyberUI 进程或 Activity 启动，无 CyberUI PID，崩溃缓冲区为空。
   设置面板、信号源菜单及 HOME 返回均已检查。第二步只是这台的单独配置，不作为默认安装步骤。

恢复时用保存的原 HOME 和两个 system 字段；原来没有的字段用 `settings delete system <字段>`
还原，不要写字符串 `null`。若另改过原桌面启用状态或通知监听，也要分别还原原值，保留 TCL
自带的设置、SystemUI、LiveTV、语音服务以及已有的无障碍服务。

### 受校验器限制时的覆盖升级记录

2026-10-30，这台从 1.47（55）升级到 1.72（91），普通 `adb install -r` 仍返回
`INSTALL_FAILED_VERIFICATION_FAILURE`；当时 `REQUEST_INSTALL_PACKAGES` 已是 `allow`。
这个 appop 不能放行 ADB 的 `PmStrategy`，也不能证明电视上点击 APK 的路径已经可用。

这台两次覆盖升级（1.47 → 1.72，以及 2026-10-31 的 1.72 →
[1.74（94）](https://github.com/pengchujin/aerial-tv/releases/tag/v1.74)）实际使用了临时安装窗口：
只在设备端执行 `pm install -r` 时短暂禁用系统安装器，随后立即恢复。
窗口期间系统安装界面暂时不可用；这是本固件的人工排障记录，不替代前面的多屏互动等正常安装方式，
也不要加入默认安装脚本或永久关闭验证器。

已验证的操作约束：

- 先备份当前 APK、HOME、开机字段、屏保、无障碍、通知监听、已停用包列表及安装器的用户启用状态。
- 本机安装器原状态是 `enabled=0`（default）。设备端脚本检查型号、APK 可读及此原状态；
  任一不符便退出，不能把别的机器上原本停用的安装器强行恢复成启用。
- 在禁用前同时设置退出 trap 和独立的 `nohup` 看门狗；看门狗 45 秒后执行
  `pm default-state --user 0 com.android.packageinstaller`，避免主机断连后一直停用。
- 执行 `pm disable-user --user 0 com.android.packageinstaller`、`pm install -r <已校验的官方APK>`，
  不管安装成功与否都立即执行 `pm default-state --user 0 com.android.packageinstaller`。
  只有恢复成功才取消看门狗；最后再用 `dumpsys package` 确认原启用状态。
- 两次安装和恢复返回值均为 0；安装器最终均为 `enabled=0`。没有卸载 Aerial、清空数据或永久停用安装器。

### 1.74 升级后的验收范围

- `app.orchard.tv` 保留官方签名（与已装的 1.72 相同），版本为 1.74 / 94；桌面可正常显示。
- 默认 HOME、`boot_app_mode`、`boot_app_componentname`、AerialDream、已有无障碍和通知监听、
  已停用包列表、安装权限与升级前一致。
- `cmd package compile -m speed -f app.orchard.tv` 返回 `Success`，dexopt 为 `[status=speed]`，
  检查时崩溃缓冲区无致命异常。
- 10 月 30 / 31 日的两次升级均未重启；断电冷启动验收是 10 月 25 日的 1.47，不能标成 1.74 冷启动已测。
- 未复测 1.74 应用内升级或从电视下载后点击 APK 的完整安装路径。
  新版针对默认 standard 的 `LauncherStrategy` / 安装会话改进，和本次 ADB 安装成功是不同的验证范围。

## 设置入口

- `android.settings.SETTINGS` 没有任何 Activity 响应。`com.tcl.settings/.ui.MainActivity` 用 `ACTION_MAIN` 打开时，会在左侧弹出 TCL 完整的设置面板（图像、声音、网络……）。
- `android.settings.WIFI_SETTINGS` 由同一个 Activity 响应，会弹出左侧的「无线网络」面板。两个面板都是悬浮窗口，Aerial 仍然是 resumed Activity。
- 1.7 之前，控制中心的「设置」和「网络」在 TCL 上都打不开：`firstResolvable` 用的是 `Intent.resolveActivity()`，它碰到带明确 component 的 Intent 时不做检查，直接返回这个 component，结果选中了不存在的小米组件。

## 音量与亮度

- 音量：写功放的是 SystemUI 里的 `tsdkNative.setVolumeForDevice`。1.40 以前以为它只在显示音量条时才执行，所以控制中心的滑块带 `AudioManager.FLAG_SHOW_UI`，TCL 的音量条会同时弹出来（#288）。2026-10-23 在这台电视上复测：不带这个标志调音量，SystemUI 照样调用 `setVolumeForDevice`（`value = 27`，`ret=0`），也不弹音量条；TCL 75V2（m7221）的报告人测的也一样。1.41 起控制中心的滑块不带标志，只有守护代替按住的音量键连续调时还带（和按键本身一样弹 TCL 的音量条）。
- 亮度：标准的 `screen_brightness` 被忽略（2026-10-25 读数 78，拖 TCL 的滑块时不变）。控制中心的「亮度」打开 TCL 的侧边设置面板：`startService(Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings"))`，打开后默认就在「图像」页，第一项是亮度。户主更喜欢这样：不弹 TCL 的亮度条。
- 亮度：TCL 自己怎么调（2026-10-25 抓日志，户主用遥控器快捷键和 TCL 面板各拖了一轮）：两条路都由 `com.tcl.settings` 的 `PictureImpl` 调 `TTvPictureManager.setBacklight(int)`，即视频背光 `grp_video_backlight`（`sita.video: tos_picture_set_backlight e_mode=0`），刻度 0–100，当时在 16–27 之间。快捷键先经 system_server 的 `TclShortCutKeyManager.adjustBrightness`，再 `startService` 给 TCL 设置（`ITEM=picture_backlight`, `MOTION=DOWN/UP`, `MOTION_VALUE=1`），会弹 TCL 的亮度条。TCL 设置里另一套 `PictureForTROM5Impl`（`com.tcl.tv.pq.TvBacklightManager`，binder 服务 `tcl_tv_display`）在这台不用，服务列表里没有 `tcl_tv_display`。
- 亮度：第三方程序调不了。`com.tcl.tvmanager.TTvPictureManager` 在 boot classpath 里（`/product/framework/com.tcl.tvmanager.jar`），任何进程都能调，但在 `com.tcl.settings` 以外的进程里（Aerial 的 1.48-guard、shell 的 `app_process`）：`getBacklight()` / `getGraphicBacklight()` / `getBrightness()` 都返回 0，`tsdkNative` 记 `ret=0`，`sitatvservice` 那边没有任何 `tos_picture_get_*` 记录；`setBacklight(23)` 返回成功（`tos_ret=0`），但同样没有 `tos_picture_set_backlight`，背光没动。也就是说，固件把非 TCL 进程的请求在本地挡掉了，而且不报错。所以 Aerial 不显示滑块（`TclPicture.backlight` 只在读数 1–100 时用）。75V2（m7221，Android 8）上同一个 SDK 第三方能读写，1.41 起那里有滑块，报告人已验证（#363）。
- 亮度：唯一能让第三方改亮度的办法是像快捷键那样 `startService` 给 TCL 设置，但每次都弹 TCL 的亮度条，所以没做。

## 信号源

- TCL 的信号源在自己的中间件里，`TvInputManager` 一个都没有（`dumpsys tv_input` 是空的），所以 Aerial 列不出信号源，也不能把信号源固定到主屏幕。
- 遥控器的信号源键由 `TclShortCutKeyManager.startTVInputService`（`/apex/com.tcl.tcore/javalib/tcl-service.jar`）处理：有 `com.tcl.software.systemui_custom_tv_source` 功能时，用 `startService` 打开 SystemUI 的 `com.tcl.tvstatusbar.action.SHOW_WINDOW`（extra `TvStatusBar_Type=Source`、`TvStatusBar_StartFrom=input`），否则打开 TCL 设置的 `com.tcl.insrc.SHOW_WINDOW`。两个服务都是 exported。1.15 起控制中心的「信号源」在列不出信号源时直接打开这个面板（`SystemProbe.openInputPicker`）。
- 这台电视在截图（`screencap`）时会弹出「截图成功，请扫码分享」的二维码卡片，有人在看电视时不要截图。

## 屏保

- 使用 Android 标准 Dream，空闲 `screen_off_timeout`（120000 = 2 分钟）后启动。原组件是 `com.tcl.appreciate.art/.service.screensaver.ScreenSaverBootService`（「艺术画报」，带 VIP 会员推销）。
- `install.sh` 会把 `secure screensaver_components` 换成 `app.orchard.tv/app.orchard.tv.dream.AerialDream`；`restore.sh` 会换回 `screensaver_default_component`。
- 手动测试：`adb shell am start -n com.android.systemui/.Somnambulator`。
- 1.14 及以前，按键结束屏保时 Aerial 会崩溃：Android 11 起屏保跑在 Activity 里，窗口先被移除（生命周期变成 DESTROYED），然后才调用 `onDreamingStopped`，往回设成 CREATED 会抛异常。崩溃后无障碍服务处于「已启用但没绑定」，守护失效，按 HOME 就进了 TCL 桌面。1.15 修复了崩溃，并在复活时也检查一次「已启用但没绑定」。
- 遥控器按一下可能到达两次（户主反馈：从屏保按「下」唤醒后焦点跟着进了 App 列表，小米蓝牙遥控器没有），唤醒后 400ms 内的按键都当作唤醒（2026-10-23）。
- 系统 dream 里，第一帧之后才加进窗口的 SurfaceView 一直拿不到 surface（没有 `surfaceCreated`，SurfaceFlinger 里也没有它的层），影片解码到 ExoPlayer 的占位 surface 上，屏幕全黑（#609，1.49–1.53 的「视频」屏保）。放影片的 view 要在第一帧就组合出来，数据之后再给（1.54 `VideoSaver`）。
- 1.42：Aerial 的 Dream 是系统屏保时，主页按返回和主页计时到点都通过 `Somnambulator` 启动这个 Dream，不再另起主页里的屏保。以前两套并存：返回键起的是主页屏保（接着放壁纸那段 4K SDR），2 分钟后被系统 Dream（杜比视界）顶掉，`FLAG_KEEP_SCREEN_ON` 挡不住。实测返回键起 Dream，解码器是 `OMX.MS.DOLBY_VISION.DVHE.STN.Decoder`（2026-10-24）。
- 1.42：只有一个 4K 解码器。主页航拍壁纸（4K HEVC）在播时起 Dream，Dream 的杜比视界 `DECODING_FAILED`，屏保降成 HEVC 且整个进程都降着（户主看到的「按返回只是默认格式」）。换窗口起 Dream 即使 4K 也会黑一下。现在壁纸和屏保都是航拍时不起 Dream，在主页里由壁纸的播放器接着放；`FLAG_KEEP_SCREEN_ON` 能挡住 TCL 的 2 分钟系统屏保（实测 2 分 40 秒没出来），主页计时提前到 1 分 45 秒。别的情况起 Dream 前、Dream attach 时都先让主页壁纸放开解码器（2026-10-24）。
- 1.42：「从屏保按一下下键焦点连跳好几行」。关 Dream 时杜比视界解码器释放会卡住主线程，这一下的重复键 2.7 秒后才到主页，过了 400ms 的唤醒窗口。现在唤醒 Dream 的那个键整次按键（重复和松开）都由主页吞掉。注意 Dream 失焦时 Android 会给它补一个**被取消的** UP，不能把它当成松开。`input keyevent --longpress DPAD_DOWN` 复现、修复后焦点留在第一排（2026-10-24）。
- 1.42：守护监听 `screensaver_components`，固件改回「艺术画报」时立即改回 Aerial 的。个性化选「系统内置」时把系统屏保还给 `screensaver_default_component`（艺术画报），返回键起的就是它；选回别的再改回 Aerial 的。都在 MT5879 上验证过（2026-10-24）。
- 1.42：遥控器信号源键在有 TCL 信号源面板的固件上交给固件（PR #334），实测只弹 TCL 自己的面板。
- 屏保里按主页键没反应（2026-10-29 修，未发版）：Android 11 守护一直过滤按键，主页键在系统之前被接住，守护「拉回 Aerial」，可 Aerial 本来就在屏保下面，系统又没收到这次按键，屏保就一直开着。现在守护接住主页键时，Aerial 的屏保（`AerialDream`）开着就直接 `finish()`，回到主页；这次按键不算进「连按两下」，免得红外遥控器一次按键到两次时打开最近任务。uinput 实测：单按关屏保回到主页；屏保里间隔 120ms 按两下只关屏保；没有屏保时按两下照旧打开最近任务。「系统内置」屏保（艺术画报）不是 Aerial 的，关不了，仍然没反应。
- 主屏幕的航拍壁纸（4K SDR，SurfaceView）PixelCopy 能读到，`screencap` 也截得到（2026-10-23）。
- 但 PixelCopy 复制一帧 4K 要 0.6–1.7 秒（渲染线程被占住，按键积压）；用 MediaMetadataRetriever 从 Apple 的 1080p 文件解帧，会卡死在 TCL 的 `TclMPEG4Extractor` 里，占住 mediaserver 的取帧锁，后面的请求全部排队。请求一多，`MediaCodecList` 也会卡，任何 App 查解码器都会挂住，只能重启（2026-10-30，bugreport 里 mediaserver 有 17 个 binder 线程卡在 `MetadataRetrieverClient::getFrameAtTime`）。所以这台电视不从文件取影片的帧，玻璃也不用帧（见 ARCHITECTURE「航拍壁纸取不到帧的电视」）。
- **截图截不到视频画面**（视频走独立的硬件图层，截出来是黑的），但字幕和时钟能截到。地名字幕出现，说明视频已经在渲染。

## 视频解码器

- 4K 硬解通道不够两个播放器同时用。2026-10-30 实测：航拍壁纸在后台没放掉 HEVC 解码器，夸克网盘播 4K AVC 时自己的硬解被系统收回，退回软解（CPU 247%），播放卡顿；航拍的出错重试还会再抢一个解码器。
- 原因：这台进 App 时 onPause 到 onStop 只隔约 260 ms，中间没有一帧，`LaunchedEffect(play)` 要等重组才放解码器，Activity 停了就不再重组（小米 MFTR0 隔 450–860 ms，有一帧，所以没事）。现在 `rememberStops` 在 ON_STOP 当场释放，后台出错不再重试；修复后夸克保持硬解，CPU 约 23%。
- 航拍片源没有音轨，Aerial 在后台不占音频（只有按键音的 SoundPool）。

## 其他

- `/sdcard` 截图在 shell 下可以正常写入。
- TCL 的 `com.tcl.guard`（安全卫士）是大部分 App 的 installerPackageName，其中的 `PackageInstallerService` 是 Binder 接口，shell 用不了。
- `persist.tcl.installapk.enable=1` 和 `package_verifier_enable=0` 这些属性对上面的校验链都没有影响。
