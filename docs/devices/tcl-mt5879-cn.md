# TCL tcl_mt5879_cn（国行，雷鸟系统）

- Android 11（SDK 30），build RQ3A.210705.001，MediaTek MT5879，32 位 ARM，约 4 GB 内存。
- 1920×1080，**240dpi**（1280×720dp）。Aerial 的画布缩放会让它和小米显示得一模一样。
- 显示模式：1080p 下 50 / 60 / 100 / 120Hz，平时是 60Hz。Android 11 说不出哪些切换不黑屏，所以 Aerial 不切。1.15 导航帧耗时中位数 8ms、90 分位 10ms（2026-10-21）。
- 240dpi 下 `loadIcon` 给的是 72px 的图标，所以磁贴图要取 App 自带的最高密度。
- 原桌面：`com.tcl.cyberui/.MainActivity`（唯一的 HOME 候选）。
- 安装的 flavor：`guard`。

## 安装限制：系统校验链

`adb install` 总是返回 `INSTALL_FAILED_VERIFICATION_FAILURE`。系统必经的校验器是 TCL 改过的 `com.android.packageinstaller/.verifier.VerifierReciver`，策略链（`InstallStrategyManager`，配置由云端下发，见 `InstallConfigManager.registerOnlineConfig`）：

| 策略 | 作用 |
|---|---|
| `PmStrategy` | 通过安装标志识别 ADB / pm 安装，**一律拒绝** |
| `LauncherStrategy` | APK 声明了 MAIN + HOME，签名就必须和 `com.tcl.cyberui` 或平台签名一致，否则拒绝 |
| `ThridStrategy` | 只放行由系统应用或 `com.android.packageinstaller` 发起的「显式安装」 |
| `BlackStrategy` / `OverDueStrategy` / `SafeStrategy` | 黑名单 / 过期 / 放行 |

还试过但不行的办法：
- `pm install -i <各种 installer>`：同样被拒。
- 从 shell 调起 `InstallStart`：报错 `uid 2000 needs to declare REQUEST_INSTALL_PACKAGES`，然后直接关闭。
- TCL 应用商店 `com.tcl.appmarket2` 的 `android.intent.action.APPSTORE_INSTALL_APK` 广播（`ThirdAppManagerReceiver`）：只按包名从商店升级，`Fileurl` 以 `apk` 结尾的直接忽略，装不了本地文件。
- 媒体中心 `com.tcl.ui_mediaCenter`（系统 App，自己的文件浏览里能装 APK，见 `PackageInstallUtils`）：可以从 adb 直接打开某个目录（`-n com.tcl.ui_mediaCenter/.main.FileListActivity --es extra_title Download --es extra_device_path /storage/emulated/0 --es extra_path /storage/emulated/0/Download --ei extra_device_type 3 --ei extra_media_file_type 64`），但内置存储的目录一直卡在加载，只对 U 盘有效。
- 安全卫士 `com.tcl.guard` 能装 U 盘里的 APK（户主确认过），没有找到能传本地路径的入口。

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

**方法二：当贝**（多屏互动不在或 6553 端口不通时，`install.sh` 会自动回退到这条）
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
- 试过但没用的办法：`settings put system boot_app_mode boot_launcher` 加上 `boot_app_componentname app.orchard.tv/.MainActivity`（TCL「开机进入」的设置，由 `com.tcl.ttvs` 的 MMMService 管理），重启后还是进 TCL 桌面，已改回空值。
- TCL 开机时有可能把 `screensaver_components` 写回「艺术画报」（实测出现过一次）。所以 `install.sh` 接管屏保时，会通过 ConfigReceiver 发送 `own_screensaver=true`，之后 Aerial 每次启动都会检查，被改回去就再改成 Aerial（`GuardSetup.keepScreensaver`）。

## HOME 与守护

- 和小米一样，靠桌面守护接管。待机唤醒后会自动回到 Aerial（实测）。
- 同样是 cyberui、多屏互动装 guard 版的 TCL tc8000_ay30a2（Amlogic，Android 11）：守护运行时，遥控器关机后再也唤不醒，只能拔电；关掉守护就正常（#371，报告人做了对照实验）。怀疑是守护的按键过滤让唤醒的电源键经过了 Aerial 的进程。1.41 起屏幕关闭时不过滤按键，亮屏后再开，等报告人复测。1.43 复测仍唤不醒，关掉应用冻结也一样（#371），同型号刷回 V472 固件正常、V745 才有（#132）。1.44 加了可选的「待机时守护退出」：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez guard_off_in_standby true`。息屏时守护用 `disableSelf()` 把自己移出无障碍列表（Android 7 以下改写设置），亮屏时由挂在进程上的接收器加回，加回后照常检查前台、拉回原桌面；进程在待机中被杀时，下一次启动（通知监听重绑、同步）发现还在息屏就只挂接收器，不提前加回。我们的 MT5879 上实测：按待机后 0.5 秒内守护已移出、walleve 两个服务保留；遥控器唤醒后守护加回、walleve 仍在、前台是 Aerial（2026-10-24，测完把选项关回去了）。
- **升级 App 后，守护服务处于「已启用但没绑定」状态**。`GuardSetup.rebindIfStalled` 会先移除、1.5 秒后再加回，触发重新绑定（两次写入挨得太近会被系统合并，不起作用）。
- TCL 自己有两个无障碍服务（`com.tcl.walleve`，「小T助手」），改 `enabled_accessibility_services` 时一定要保留。
- 待机时网络 ADB 会断，但 adbd 不会被关；唤醒后能直接重连。

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
- 1.42：Aerial 的 Dream 是系统屏保时，主页按返回和主页计时到点都通过 `Somnambulator` 启动这个 Dream，不再另起主页里的屏保。以前两套并存：返回键起的是主页屏保（接着放壁纸那段 4K SDR），2 分钟后被系统 Dream（杜比视界）顶掉，`FLAG_KEEP_SCREEN_ON` 挡不住。实测返回键起 Dream，解码器是 `OMX.MS.DOLBY_VISION.DVHE.STN.Decoder`（2026-10-24）。
- 1.42：只有一个 4K 解码器。主页航拍壁纸（4K HEVC）在播时起 Dream，Dream 的杜比视界 `DECODING_FAILED`，屏保降成 HEVC 且整个进程都降着（户主看到的「按返回只是默认格式」）。换窗口起 Dream 即使 4K 也会黑一下。现在壁纸和屏保都是航拍时不起 Dream，在主页里由壁纸的播放器接着放；`FLAG_KEEP_SCREEN_ON` 能挡住 TCL 的 2 分钟系统屏保（实测 2 分 40 秒没出来），主页计时提前到 1 分 45 秒。别的情况起 Dream 前、Dream attach 时都先让主页壁纸放开解码器（2026-10-24）。
- 1.42：「从屏保按一下下键焦点连跳好几行」。关 Dream 时杜比视界解码器释放会卡住主线程，这一下的重复键 2.7 秒后才到主页，过了 400ms 的唤醒窗口。现在唤醒 Dream 的那个键整次按键（重复和松开）都由主页吞掉。注意 Dream 失焦时 Android 会给它补一个**被取消的** UP，不能把它当成松开。`input keyevent --longpress DPAD_DOWN` 复现、修复后焦点留在第一排（2026-10-24）。
- 1.42：守护监听 `screensaver_components`，固件改回「艺术画报」时立即改回 Aerial 的。个性化选「系统内置」时把系统屏保还给 `screensaver_default_component`（艺术画报），返回键起的就是它；选回别的再改回 Aerial 的。都在 MT5879 上验证过（2026-10-24）。
- 1.42：遥控器信号源键在有 TCL 信号源面板的固件上交给固件（PR #334），实测只弹 TCL 自己的面板。
- 主屏幕的航拍壁纸（4K SDR，SurfaceView）PixelCopy 能读到，`screencap` 也截得到（2026-10-23）。
- **截图截不到视频画面**（视频走独立的硬件图层，截出来是黑的），但字幕和时钟能截到。地名字幕出现，说明视频已经在渲染。

## 其他

- `/sdcard` 截图在 shell 下可以正常写入。
- TCL 的 `com.tcl.guard`（安全卫士）是大部分 App 的 installerPackageName，其中的 `PackageInstallerService` 是 Binder 接口，shell 用不了。
- `persist.tcl.installapk.enable=1` 和 `package_verifier_enable=0` 这些属性对上面的校验链都没有影响。
