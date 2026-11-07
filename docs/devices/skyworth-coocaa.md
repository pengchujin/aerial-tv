# 创维 / 酷开（Skyworth / Coocaa）

来自用户反馈，维护者手上没有创维。以下都是报告人的实测。

| 机型 | 系统 / 平台 | 结果 | 来源 |
|---|---|---|---|
| 65G60 / 酷开 9S60 | Android 8.0，MStar MSD6A848 | 主页键能回 Aerial；开机一定进酷开桌面：`sky_launcher`（在 system_server 里）读 `/system/pcfg/9S60_G60/config/general_config.xml` 的 `DEFAULT_HOMEPAGE` 按组件启动，不走 HOME 解析；守护在开机时的 `startActivity` 被 `CooCaaCheckDialogPermission` 丢掉（调用方没有可见 Activity）；`skyRMService` 切前台时会 force-stop 上一个第三方 App；开机把第三方 App 置成 stopped。报告人用 root 改 `DEFAULT_HOMEPAGE` 为 `app.orchard.tv/app.orchard.tv.MainActivity` 才开机直达。开机广告是 `com.tianci.ad`，`pm uninstall --user 0` 就没了 | #316 |
| 7T641（mt9633） | Android 13 | 冷启动约 60 秒黑屏：Aerial 早已画完，但 SurfaceFlinger 里它的窗口 `crop=[0,0,0,0]`；遥控器「播放」键在这台上发的是 Home，看视频时按到会被守护拉回 Aerial，然后又是 60 秒黑屏 | #321、#326 |
| 7T634_P3H（mt9633 / mt9952，K3） | Android 13 | 1.57 黑屏：首页已画完，`focus false`，两次「没有焦点窗口」ANR；改包名也一样。同一台上 FreeTV 能显示，原因还没找到。报告人发现这台的按应用自启动 / 后台保留在 `skyresource` 服务里（`service call skyresource 5 s16 <包名> i32 0 i32 1` 允许自启动，`i32 1 i32 2` 后台保留，只在这台固件上核对过），还没对 Aerial 试 | [#864](https://github.com/pengchujin/aerial-tv/issues/864) |
| 8R721_A6FP（titanite，RTD2885P） | Android 13 | 1.67-guard 首页先显示出来，约 30 秒后两次「没有焦点窗口」ANR，回到 `com.skyworth.officehomepage`，和 8R710 / 8R734 一样 | [#839](https://github.com/pengchujin/aerial-tv/issues/839) |
| 75A6E（8R710_A6E，Realtek RTD2885P） | Android 13 | standard 版是唯一的 HOME 候选，按主页键整机卡死；创维电视应用 `com.skyworth.tv` 一直占着输入焦点，Aerial 的窗口 `NOT_VISIBLE`、`focus false`，guard 版也一样。目前没有办法 | #365 |
| 75A7D（8R134_A7D，Realtek RTD2885N） | Android 11，酷开 CCOS 9.0 | `TvInputManager` 一个信号源都没有；改为从 `com.skyworth.tv.sdk.provider` 读列表、用 `com.skyworth.tv` 的 `SkyTVMainActivity` 切换，报告人在真机上验证通过，接口见下（1.45 起用上） | #444 |
| 酷开 8R710_P8F（同一机芯 titanite） | Android 13 | 只有诊断报告，现象待补，估计同 #365 | #358 |
| 8R734_A7HP（titanite，RTD2885P） | Android 13，SELinux permissive | 画面在、没有焦点、反复 ANR：守护按组件拉起的 standard 任务在 SurfaceFlinger 里一直隐藏，见下「titanite 的 standard 任务」；以 HOME 方式拉起后正常。开机和主页键由固件按组件选，见下 | 户主实测，2026-10-30 |
| 松下 TH-55HX560C（创维代工，天赐系统，GX580C，m7221） | Android 8.0 | 主页键、返回都能回 Aerial；`TvInputManager` 一个信号源都没有，控制中心显示「没有可用的信号源」。还不知道天赐切信号源的接口 | #370 |
| 8R134 / 8R145、K3 Pro 等 | Android 11 | 见 #43、#155：开机先进自家桌面，切信号源时经过 `com.tianci.tv/.SkyLauncher` | #43、#155 |

## sky848_9s60 机芯（MStar m7221 / MSD6A848，Android 8.0）：看固件版本号

报告人在 #713 对比了这块主板上的几台：`ro.build.version.incremental` 是 `021.008.x` 的能接管（70A9，#461），`022.008.x` 起开机和主页键都在酷开桌面，要 root 改 `DEFAULT_HOMEPAGE`（65G60 / 9S60 #316、7S718_H30 #686、7S712_G32 #548，7S87_A20 #630、7S77_H4 #549 症状相同）。机制见上表 65G60 一行：开机由 `sky_launcher` 按 `general_config.xml` 的组件直启，守护在后台的 `startActivity` 被 `CooCaaCheckDialogPermission` 丢掉，`persist.service.homepage.*` 会被写回。样本只有四五台，有反例再改。

root 后把 `/system/pcfg/<型号目录>/config/general_config.xml` 里的 `DEFAULT_HOMEPAGE` 改成 `app.orchard.tv/app.orchard.tv.MainActivity`（必须是「包名/完整类名」两段），#316 里连续 10 次冷启动都直达。

## 75A7D 的信号源键和亮度（#566，报告人实测，1.49 起）

- 遥控器信号源键：固件弹自己的 Quick Panel（`com.coocaa.os.ccosservice/com.coocaa.os.controlcenter.quicksettings.service.FeatureService`），同时把按键交给前台 App。有这个服务时 Aerial 不再响应信号源键，和 TCL 的 `tclInputPanel` 一样。
- 亮度：`screen_brightness` 一直是 2/255，写它不改背光（画质中间件管）；`ACTION_DISPLAY_SETTINGS` 也解析不到。图像侧栏是启动 `com.skyworth.setting/.service.HalfScreenService`、带 `Key = "android.settings.PICTURE_SETTINGS"`（导出、无权限）。有这个服务时控制中心不画亮度滑条，改成打开它。两个包都要写进 `<queries>`。

## 75A7D 开机先出酷开桌面：bootintent.xml 的 modelhome（#894，报告人实测，未合入）

8R134_A7D（固件 `RP1A.200720.011` / `eng.www-da.20231205.132148`，Android 11，SELinux **permissive**）开机先出酷开桌面 3–6 秒。报告人查到原因：`services.jar` 在解析 HOME 之前按 `/system/ccos/etc/bootintent.xml` 选开机首页，其中 `modelhome` 一项在 `persist.service.model.pkg` / `persist.service.model.cls` 都非空时直接启动它们指向的组件；`com.tianci.system` 每次开机约 24 秒时把这两个属性清空。

- 手动 `setprop` 这两个属性只管下一次开机。报告人的改法是 Aerial 每次开机后 30 秒用 `/system/bin/setprop` 写回（只在 bootintent.xml 有这一项时；enforcing 的固件写不进去），实测断电、重启都直接进 Aerial。
- 没有合进 Aerial：要由 App 写固件的开机属性，卸载 Aerial 后下一次开机会指向不存在的组件，固件怎么处理没验证；`restore.sh` 也还不会清这两个属性。要不要做由户主决定。

## titanite 的 standard 任务：图层一直隐藏（8R734_A7HP，2026-10-30，户主实测）

Aerial 1.69 standard 版，固件 `TQ1A.230205.002 / eng.www-da.20260205.170347`。

- 现象：首页画出来了，AerialDiag 一直 `focus false, shown true, state RESUMED`；按键 5 秒后 `Input dispatching timed out (Application does not have a focused window)`，ANR、被杀、守护拉起，循环。第一次安装时整机卡死，要拔电。
- WindowManager 认为 Aerial 可见并有焦点（`findFocusedWindow: Found new focus`），SurfaceFlinger 里它的 Task 图层却是 `flags=0x00000001`、没有可见区域，InputDispatcher 回 `FocusRequests … result='NOT_VISIBLE'`。同一台上云视听极光（也是 standard 任务）的 Task 图层是 `flags=0x0`、焦点 OK。BufferStateLayer 的 `crop=[0,0,0,0]` 两者一样，不是原因；缩放也不是：`persist.cwctest.force4k=1`（下面「缩放」）后 Aerial 已是 3840×2160、无 `mGlobalScale`，仍然 `NOT_VISIBLE`。
- jadx 看 `services.jar` 的 `Task`：可被 organizer 接管的根任务创建时不 show（`showSurfaceOnCreation` 返回 `!canBeOrganized()`），`mLastSurfaceShowing` 初值为 true，`prepareSurfaces` 只在可见状态变化时 `setVisibility`；创维加的那半句 `2 == getActivityType() && isRootTask() && getChildCount() == 1` 只给 HOME 类型的根任务每帧补一次。`TaskOrganizerController` 里没有 organizer。第一帧就可见的任务因此一直不 show。被别的界面盖住再回来（户主发现：自然进入名画屏保再退出）会变化两次，图层就出来了。
- 守护的 `bringBackIntent` 和 `BootReceiver` 按组件启动，建出 standard 任务。`am start -a MAIN -c HOME` 启动同一个 activity 则落在 home 根任务下的 `type=home` 任务里，图层立刻显示、焦点 OK。现在 Aerial 是系统桌面时以 HOME intent（只设包名）拉回自己；被别人以非 HOME 方式新建成独立任务时（酷开桌面开机用 LAUNCHER 打开默认桌面，uid 1000），立即以 HOME 方式重开并移除那个任务。两次重启、强停后复活都没有 ANR。
- 同机芯的 8R734_Q7H（#421）、8R721_A6FP（#839）、8R710_A6E（#365）症状相同，可能是同一个原因，没在那几台上验证。

### 开机和主页键：固件按组件选（jadx `SkyBootIntent`）

- 开机第一次取桌面（`firstGetIntent`）用开机早期按 `bootintent.xml` 算出的 `initIntent`，其中 `modelhome` 读 `persist.service.model.pkg/.cls`，且先 `checkIntentExists`；之后系统自己的回桌面请求也在第 4 步优先用这两个属性（这里不检查组件是否存在）。都没有时用 `general_config.xml` 的 `DEFAULT_HOMEPAGE`（只读）。
- 遥控器主页键由 `com.tianci.system` 按 `persist.service.homepage.pkg/.cls` 按组件启动（uid 1000），不看 HOME 解析。
- 每次开机：`model.*` 被清空；`SkyBootIntent.initGeneralConfig()` 在 `DEFAULT_HOMEPAGE` 不包含 `homepage.pkg` 时把 `homepage.*` 改回酷开桌面。
- 这台 SELinux permissive，应用身份（`run-as` 实测 rc=0）也能 `/system/bin/setprop`。PR #992 第 2 部分让 Aerial 是系统桌面时在回到前台及开机后 40 s、90 s 把四个属性写成自己，原值存进 `Settings.Global` 的 `aerial_coocaa_homepage` / `aerial_coocaa_model` 给 `restore.sh` 写回；作者实测连续两次重启开机直接进 Aerial、主页键直接进 Aerial，8R720-P7F 上主页键不再先闪酷开桌面。**这部分没有合入**（户主 2026-11-04 定）：卸载前不跑还原会黑屏到重启（下一条），用户在电视上卸载时不会跑还原。现在开机和主页键仍由守护拉回（PR #1206 的 HOME 方式），会先露一下酷开桌面。
- （PR #992 的属性写法下）卸载而不先跑 `restore.sh`：开机那次有 `checkIntentExists`，会跳过不存在的组件，随后固件自己清空 `model.*`、改回 `homepage.*`；开机之后、清空之前的回桌面请求会指向不存在的组件。**已实测（2026-11-03，PR #992）**：`pm uninstall --user 0` 卸载后属性仍指向 Aerial，此时按主页键**黑屏、`mCurrentFocus=null`**，再按无效，`am start -n` 拉酷开 AIGC 桌面也无效（系统不崩、无 ANR，就是没有桌面）；**固件不自愈**。`reboot` 一次后固件把 `homepage.*` 回落为 `DEFAULT_HOMEPAGE`、`model.*` 清空，开机进酷开屏保/桌面，无 ANR。所以 8R734 上卸载前必须先跑 restore；8R720-P7F（@penguin868 实测）固件会自愈，8R734 不会。
- 开机自启：`SkyResourceManagerService.isProcessLocked()` 拦非系统应用（`Boot denial: startProcessLocked() from package=app.orchard.tv`、`is't allow to start settings by user`），守护和 `BootReceiver` 开机都起不来。`adb shell service call skyresource 5 s16 app.orchard.tv i32 0 i32 1`（`setProcessInfo`，开机自启）后不再拦；`i32 1 i32 2` 是后台保留。重装后要重设，`install.sh` 还没有做。
- 开机「重置」`homepage.*` 时，`SkyBootIntent.initGeneralConfig()` 写进去的是字面字符串 `home[0]` / `home[1]`（数组元素被写成了带引号的常量），过一会儿才有别的组件写回真实值；开机后约 20 秒读到的就是这两个字符串。所以原桌面先读 `DEFAULT_HOMEPAGE`，属性只当后备，且要求 activity 存在。

### 原桌面磁贴打不开（Android 13 的 intent filter 匹配）

- 1.69 点原桌面：`START {act=MAIN cat=[LAUNCHER] cmp=com.skyworth.officehomepage/.view.HomepageActivity} from uid <Aerial>`，PackageManager 报 `Intent does not match component's intent filter` / `Access blocked`；随后的 `pressHomeForStock` 按 Home，主页键已指向 Aerial，于是回到 Aerial。Android 13 起，target 33+ 的应用按组件启动别的应用也要匹配对方的 intent filter，酷开的几个桌面只声明自家类别。
- 而且磁贴指向的是 `com.skyworth.officehomepage`（`SKYWORTH_PREFERRED_STOCK_HOMES` 里为 skyT963 排第一），不是这台显示的桌面。这台固件的桌面是 `/system/pcfg/8R734_A7HP/config/general_config.xml` 的 `DEFAULT_HOMEPAGE`：`com.tianci.movieplatform/com.coocaa.aigc.home.home.rootpage.HomeActivity`（`exported=true`，filter：`coocaa.intent.aigc.home` + DEFAULT；`MAIN` + `coocaa.intent.category.MYAPP_DISMISS`，没有任何 Home 类别）。文件对所有人可读。
- 现在（PR #992 的这一部分，2026-11-04 合入）：`system/CoocaaHome.kt` 读出固件桌面（`DEFAULT_HOMEPAGE`，其次 `persist.service.homepage.*`，都要求 activity 存在，只读不写）；守护把它排在所属包的第一位，原桌面磁贴优先用它所在的包；打开时先照旧带 LAUNCHER，被拦就改用只带 `MAIN` 的 intent（`startStockDirectly`，只在安卓 13 起装了 `com.tianci.system` 的酷开上）。

### 缩放（与焦点无关，备查）

`CompatModePackages.getCompatScale()`：`ro.surface_flinger.max_graphics_width` 为 3840 时，不在 `/vendor/ccos/etc/4k_packages.txt`（包名包含其中一行即算）里的应用按 1080p 渲染、`mGlobalScale=2.0`；`persist.cwctest.force4k=1`（重启生效）时全部不缩放。

## 75A7D 的信号源接口

2026-10-24，报告人在 75A7D（`8R134_A7D`，RTD2885N，酷开 CCOS 9.0 / Android 11）上实测：

- `TvInputManager` 里没有任何输入，`dumpsys tv_input` 的 `mHardwareList`、`mHdmiDeviceList`、`mInputMap` 都是空的。
- 源列表在 `com.skyworth.tv`（uid 1000，系统应用）的 `TVSDKContentProvider` 里：`content://com.skyworth.tv.sdk.provider/system/source_list`，当前源是 `.../system/current_source`。provider 导出、无权限；**查询必须带 projection**，不带时返回 null cursor。列有 `sourceName`、`sourceIndex`、`displayName`、`signalState`、`sourceId`。
- 这台机器的 6 个源：`AV` / -1 / 视频、`VOD` / -1 / 电视、`HDMI` 0–3 /「4K HDMI1 2.0」「4K HDMI2 2.1/ARC」「4K HDMI3 2.1」「4K HDMI4 2.0」。`signalState` 全是 1，没接线也是 1，不能当「已连接」用。
- 切换：`Intent().setComponent("com.skyworth.tv/.activity.SkyTVMainActivity").putExtra("source", Bundle().apply { putString("name", …); putString("displayname", …); putInt("index", …); putInt("signal", …) })`。`SkyTVMainActivity.p(Intent)` 把这个 Bundle 交给 `SkyTVManager.J2`，`onCreate` 和 `onNewIntent` 都会读。activity 在系统 APK 里没有写 `android:exported`（targetSdk 26，带 intent-filter 默认导出），`cmd package resolve-activity` 能解析。
- 清单里本来就有 `QUERY_ALL_PACKAGES`，包可见性已经覆盖；补丁另外加了 `<queries>` 把 `com.skyworth.tv` 和这个 provider authority 显式写出来，免得以后去掉全量可见性时静默失效。

## 共同点

- 开机时桌面是固件按组件或配置文件拉起的，不看 HOME 解析。多数机型开机先出现原桌面，靠守护接管；70A9（7S70_A9，Android 8）用 standard 版、不改 `persist.service.homepage.*`、不 root，`adb reboot` 两次都直接进了 Aerial（#461）。遥控器断电再开时固件会切到上次的信号源，按主页键回 Aerial。
- logcat 常常看不到第三方 App 的日志，看 `dumpsys activity app.orchard.tv/.MainActivity` 末尾的 DiagLog。
- `adb install -r` 之后系统会删掉本包的无障碍登记，重装后要先打开一次 Aerial（#316）。

## 补充（2026-10-24）

- 9S52_Q4A（Android 6）：开机时固件按组件拉起 `com.tianci.movieplatform/com.ccos.cooui.movieplatform.MainActivity`（intent 带私有类别 `android.intent.category.HOME.CC`），Aerial 约 1.8 秒后接管（#397）。1.43 的 standard 版声明了 `HOME.CC`，是否能让固件开机直接选 Aerial 还没验证。
- 8R734_Q7H（titanite，Android 13）：和 8R710 一样，Aerial 拿不到窗口焦点，5 秒后 ANR 被杀（#421）。目前没有办法。
- 70A9（7S70_A9，MStar m7221，Android 8，低内存）：`TvInputManager` 没有输入，信号源走 `com.skyworth.tv.sdk.provider`（查询必须带 projection，`displayName` 是实时的，HDMI 从 0 编号，AV、VOD 是 -1）；遥控器「信号源」键被固件截走，打开原桌面；待机唤醒先经过 `com.tianci.system/...WakeLockActivity` 10–12 秒再到 Aerial（#461）。
- 7AA46_H5D、8A002_H5D（T963，Android 9）：Aerial 在前台、守护过滤按键时，音量键的抬起到不了守护，1.41–1.46 按一下音量就一直加到 100 或减到 0（#448 #451），1.47 修了（后来又出现，见下面「音量键连冲」）。把 Aerial 设为默认桌面（`cmd package set-home-activity`）后守护不过滤按键，音量正常，开机和主页键也正常（#449）。
- 酷开 aigc 系（C10 投影仪 3H352_C10 Android 9 #487、P60 Android 11、7T863_N6E Android 10 #106）：主页键在 WindowManager 里变成创维热键广播，`com.tianci.system` 的 SLauncher 按组件拉起 `com.coocaa.aigc.home/.home.rootpage.HomeActivity`，不走 HOME 解析；这个桌面不声明任何 Home 类别。1.48 起守护把它算原桌面（未在真机验证）。7T863_N6E 上改 `persist.service.model.pkg/.cls`（加上 `homepage.*`）后主页键进 Aerial，但每次开机被固件写回。
- 8H57_H5（#981，报告人的 logcat 和 `ps`）：酷开屏保开始时（`sky.action.dream.startSkyDream`），`com.skyworth.android.skyRMService`（system 用户）强停 Aerial，守护跟着停，之后按主页键先到酷开桌面。App 这边拦不住；变通是关掉电视自己的屏保 / 名画，只用 Aerial 的屏保。
- 8R145_P3DM（Android 11）：`SkyProcManager` 会强停「不可见的非系统应用」，从 Aerial 打开别的 App 后 Aerial 就被杀，这时按主页键先到酷开桌面，等复活约 20–30 秒才回 Aerial（#106 的日志）。目前没有办法。
- 创维/酷开 Whaley 55D2UK（helios，MStar，Android 5.0.2，arm64）：standard 版能装能接管，原桌面 `com.helios.guide` priority 1，设不成默认桌面，靠守护（主页键约 4 秒、先闪原桌面）；信号源走微鲸的 `com.whaley.tv.tvplayer.ui/.RootActivity`；亮度 `screen_brightness` 可调（#457）。

- 5S09_A3（Tianci，MStar maserati，Android 6，#895，报告人在电视前核对）：`screen_brightness` 能写、`mActualBacklight` 跟着变，但面板亮度不变；背光在 `com.tianci.setting/.activity.PictureSoundSetting`（图像与声音）里，酷开自己调背光时 Settings 不变。没有 `com.skyworth.setting`。1.71 起 devices.json 标 `ignoresScreenBrightness`，亮度按钮打开这个页面。信号源：TvInputManager 为空，控制中心「信号源」打开 `coocaa.intent.action.SOURCE_DIALOG` 能切；切换由 `com.tianci.tv/.SkyLauncher` 带 extras 完成，报告人试的 `source`/`sourceIndex`/`inputSource`/`index` 都不行；HDMI 1 = 23，HDMI 2 = 24。
- C10 投影（3H352_C10，Android 9，#915）：遥控器「对焦」（扫描码 742，KeyCode 1116 `QUICK_START6`）和「设置」（176）的按下被 `com.cvte.tv.hotkey` 消费，只把抬起给 App。1.71 起屏保收到没有按下的抬起也退出。

## 飞利浦 50PUF6192/T3（创维代工，海思 Hi3751V553，安卓 5.1，#1038）

遥控器关机走海思的 BlackScreen 待机，再开机时固件清空 `enabled_accessibility_services`，并连着强停 Aerial 两次（`is not system and isn't Visibility kill`，再由 `com.tianci.system` 深度清理），日志 `hisi is force killing, no way to start: app.orchard.tv`，这次开机内系统不再拉起它，回到酷开。`adb reboot` 开机正常。App 改不了。

## 音量键连冲（#937 8R134_A7D、#1091 8R145_P3DM，Realtek RTD2885N，安卓 11；#1058 8A002_A3，T963，安卓 9）

1.76 上按一下音量键，音量一路到 100 或 0，反方向键打断不了，按返回才停。三份诊断里 Aerial 自己的按键日志一样：`KEYCODE_VOLUME_UP down #0`，0.5 秒后 `#1`，之后每 50 ms 一次 `#2 #3 …`，一直没有 `up`，直到别的键按下。也就是守护开着按键过滤时，音量键的抬起没到 Aerial 窗口，系统一直补「还按着」，每次经 PhoneWindow 交给 MediaSessionService（`dispatchVolumeKeyEvent pkg=app.orchard.tv`），酷开的 `ccSystem` 每次调一格。关掉守护（#460 的对照、#937 评论里 7R301 的用户）或把 Aerial 设成默认桌面（#449，守护不再过滤）就正常。

2026-11-01 起（未发版时写）：守护在 `takeVolumeKey` 记下看到的抬起，`MainActivity` 丢掉抬起之后还在连发的音量键；按下 / 抬起都写进诊断。没有创维真机验证。如果诊断里守护也只有 `down` 没有 `up`，说明抬起在过滤之前就被固件吃掉了，这个办法不够，要再想。

1.78（`volumeLiftUnseen`）之后仍连冲（2026-11-02，#937 A7D 的诊断、#1113 C10 的报告）：
- A7D：守护记住了（三次按下都 `not passed on`），窗口却一直收到更早一次按下的 `VOLUME_UP #120…#152`，每 50 ms 一个。系统对等不到抬起的按下一直补发重复，直到有别的键进到分发器；守护留下的按下进不去，所以打断不了，每个重复都是一格。按返回能停，就是因为返回键进去了。
- C10：Aerial 是默认桌面，守护不过滤按键，诊断里没有一行 `guard:`。窗口里照样有抬起之后的 `VOLUME_UP #47`，`VOLUME_DOWN` 每三个重复夹一个抬起地一直来。报告人用 Agent 抓到每次按键 `ccSystem onRecvKey` 两次（+2），但这台有海思自己的无障碍服务 `HiRMService`，第二条通道不一定是守护传下去的。
- main 上（未发版）窗口这边丢掉：已结束的按下的重复（这里或守护见过它抬起、有更新的音量按下、或守护已经知道抬起到不了），以及到达窗口前音量已经变了的整次按下（`MainActivity.dropVolumeKey`）。小米 MFTR0 上音量键根本不进 Aerial 窗口，TCL MT5879 上走守护，两台行为不变（实测）。创维 / 酷开没有真机，发版后看诊断里的 `repeats a press that is over; dropped` / `the volume already moved`。

1.80 之后（2026-11-03，#1113 C10 报告人的 system_server 日志、#937 A7D 的诊断）：
- C10 的原因找到了：1.77 合入红米投影仪 Lite 的改动，装有 `com.cvte.factory.at`（CVTE 工厂测试）时，Aerial 是默认桌面也开按键过滤。C10 也是 CVTE 代工、同是 Hi3751V350，过滤开着时每次音量按下被固件分发两次（主线程 Local 一次、android.ui Broadcast 一次），抬起只回一次，另一条变成系统的自动重复，463 次重复每次一格。1.76 没有这个过滤，一按一格。main 上（未发版）有酷开的 `com.tianci.system` 时不再因 `com.cvte.factory.at` 过滤（e8f4155）。没有 C10 真机。
- A7D：1.80 后不连冲了，但按住也只走一格。守护拿走按下、固件不动时自己补一格，等 300 ms；按住时遥控器每 200 ms 发一次新的按下，每次都取消了上一次的补格，最后只补一格。main 上（未发版）新按下先把上一次没补的补上（1dc3357），按住应恢复连续调。没有 A7D 真机。

1.81 之后（2026-11-03 晚）：
- 8R145_P3DM（#1091，报告人的 system_server 日志，1.80）：短按一格了，按住 6.3 秒只走一格：固件在过滤之前动了音量，守护不把按下传下去，Android 只给分发出去的按下补重复，于是固件再也收不到重复。这台的抬起能到守护（日志末尾有 `down=false`）。main 上（未发版，8f9d65f）：守护见过一次「没传下去的按下的抬起」之后，按住时自己按系统的重复节奏调（和 TCL 一样，最多 8 秒、抬起即停）；进程内第一次按住仍是一格。TCL MT5879 上用 `hid` 造的遥控器回归：单按一格、按住 1.5 秒连调、松手停。没有 8R145 真机。
- A7D（#937，1.81 的诊断）：守护只记到按下、一条抬起都没有，按住期间也没有新的按下。守护分不清按住和短按，上面这条帮不了它；把按下传下去又会回到 1.76 的连冲。暂无办法；控制中心的音量条可以试（没在创维上验证）。

## 开机属性 `persist.service.model.*`（#1046 7AA49_D4 T963 安卓 9，#1096 65G650P / #1069 7S77_V40 sky848_9s60 安卓 8.0）

- 固件开机按 `persist.service.model.pkg / .cls` 直接启动组件；7S77_V40 上只改 `persist.service.homepage.*` 反而让 Aerial 被反复强停（#1069 实验 6）；65G650P 上清空 `model.*` 后固件写回 `com.tianci.movieplatform`。
- 7AA49_D4：第三方 App 能执行 `setprop` 写这几个属性；`adb install -r` 后酷开清掉本包的无障碍登记；`com.coocaa.*` 包名会被 SkyProcManager 放过。
- 65G650P：第三方收不到 `BOOT_COMPLETED`；`pm install` 报 `INSTALL_FAILED_USER_RESTRICTED`，要用 `cmd package install-create/write/commit` 会话装；`cmd notification allow_listener` 报 No shell command implementation，用 `settings put secure enabled_notification_listeners`。
- 报告人的办法是改包名或多装一个「壳」App 占住 `model.*`，都和原则冲突（只装 Aerial 自己、不冒充厂商包名），Aerial 不做。

## 9S89_S8A（MStar，安卓 6.0 / CCOS）：假待机唤醒后 Aerial 回不来（#1173，报告人实测）

- 遥控器电源键是「假待机」：待机时前台是 `com.tianci.system/.WakeLockActivity`，内核不重启，唤醒不发开机广播。
- 唤醒后固件把非 `com.coocaa.*` 的包置成停止态（`stopped=true`，报告人用两个空壳包对照），并把 Aerial 从 `enabled_accessibility_services` 里拿掉；被停止的包收不到广播、不会被绑定，Aerial 自己回不来。
- `system boot_app_mode` 取 0 / 1 / 2 加 `boot_app_componentname` 都没让固件在唤醒后打开 Aerial；`/data/ccos/customStart` 要 root。
- 报告人自编了改包名（`com.coocaa.aerial`）的版本：重启能进 Aerial，假待机唤醒后仍不会被打开。改包名进名单和项目原则冲突，不做。这台只能唤醒后手动打开一次 Aerial，或用 guard 版、别把 Aerial 设成默认桌面。

## 7S72_G25（m7221，安卓 8，预装当贝桌面，#1297）

Tianci 的 `HotKeyHandler` 按 `persist.service.homepage.*`（`com.cos.launcher/cos.launcher.Mac`）按组件开桌面，Mac 再开 `com.dangbei.tvlauncher.IndexActivity`，所以守护只认 Mac 时主页键回不来：现在 IndexActivity 也算原桌面（`HOME_SCREENS_BEHIND_TRAMPOLINES`）。报告人试过 ADB 写这组属性，当场好，但重启和待机后 root 进程 `coocaa_guard` 改回来，不是长久办法，也不进安装脚本。**没有真机验证**。

8S38_Q30 / 同款（#1235）：`SkyLowMem` 只在可用内存低（level ≤ 2）且 Aerial 不在前台时强停；复活时的 `startActivity` 被固件 `sky_dialog` 判后台启动拦下，放行看调用者 uid（shell 2000 放行，App 不放行，报告人 A/B 对照）。App 这边过不了这一关。

## m7221 安卓 8 的开机首页属性（#1235 #1297，报告人实测）

创维 7S77_A20、7S72_G25（m7221，安卓 8）：`persist.service.homepage.pkg` / `.cls`（原值 `com.tianci.movieplatform` / `com.coocaa.homepage.vast.HomePageActivity`，`persist.service.starthomepage=1`）shell 能写，写成 Aerial 后按主页键由 `ccSystem` 以 uid 1000 打开 Aerial（`< SLauncher >, startHomePage`），绕过 `sky_dialog` 对 App uid 的拦截；重启或待机后被改回原值。8S38_Q30 上有 root 进程 `/vendor/bin/coocaa_guard`，但二进制里没有这两个属性名，不能确定是它改回的。App 写不了 `persist.*`，安装脚本也不写；做实验要先记原值，卸载 Aerial 前改回去（#992 的 8R734 没改回就卸载，主页键黑屏到重启）。
1.86 起守护认 `com.cos.launcher/com.dangbei.tvlauncher.IndexActivity` 为原桌面（7S72_G25 开机 0.5 秒拉回）；低内存（`ro.config.low_ram=true`）时 Aerial 一到后台就被回收，从 App 按返回会落到固件开机时拉起的当贝任务，等复活后才回来。App 不能强停别的 App（要 `FORCE_STOP_PACKAGES`），这一步做不了。
