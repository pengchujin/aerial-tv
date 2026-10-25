# TCL m7221（国行，75V2 / sugarcane）

> 和 [tcl-mt5879-cn.md](tcl-mt5879-cn.md)（Android 11 / MT5879）、[tcl-mt9655-cn.md](tcl-mt9655-cn.md)（Android 11+ / MT9655）是**三个不同平台**，拦安装的代码根本不在同一处，不要照着另一篇的方法试。

- 零售型号：TCL **75V2**；`ro.product.device` / `ro.product.model` = `sugarcane`；固件 `V8-S48CT05-LF1V142`（MS848C 机芯）。
- 平台 MStar **m7221**（`ro.board.platform` / `ro.hardware`），1920×1080，240dpi；Android **8.0.0**（SDK 26），`OPR5.170623.014 dev-keys`。
- 原桌面：`com.tcl.cyberui/.MainActivity`。
- 安装的 flavor：**`standard`**。多个版本与自编译版都在机上跑过；官方从某个版本起自带 TCL 亮度滑块（`TTvPictureManager`，见下「亮度」一节，来自 issue #363）。注意 `standard` 包内**同样带 guard 组件**（`guard/BootReceiver`、`guard/HomeGuardService`、`guard/RevivalSyncService`、`guard/RevivalAccountService`），并非只有 `guard` flavor 才有；本机 `enabled_accessibility_services` 里就有 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`，`dumpsys activity processes` 也能看到它对 system_server 的 `FGSA` 绑定，即守护在运行。
- 开机直达：**四条命令**（三条 `persist.SDK.boot.*` 属性 + `set-home-activity`），开机约 10 余秒切到 Aerial，**不影响第三方应用**。`persist.sys.launcher.component` 出厂为空，**任何情况下都不要设**——它会让所有第三方应用崩（见「已知问题 3」）。
- 全程只用 `adb shell` 权限，**没用 root**（`su: not found`）。

## 开 ADB：固件开机不会把 adbd 拉起来

这台出厂就带 `persist.service.adb.enable=1`、`persist.adb.tcp.port=5555`，但**重启后 5555 连不上**。
原因不是固件把 adb 关了，而是 adbd 由 init 的**属性触发器**启动，而**触发器只在属性值发生变化时点火**：

```bash
$ adb shell getprop | grep -i adb
[persist.service.adb.enable]        [1]      # 真开关（OEM 属性）
[persist.sys.service.adbd.enable]   [1]
[persist.adb.tcp.port]              [5555]
[service.adb.tcp.port]              [5555]   # 非 persist，开机必丢
[persist.sys.usb.config]            [adb]
[init.svc.adbd]                     [running]
[ro.adb.secure]                     [1]
```

开机时 init 加载 `persist.*`，值从 1 到 1、**没有变化 → 触发器不触发 → adbd 起不来**。
这与「持久化 = 开机自动生效」的直觉正好相反。

判定是否真的活着，**只看 `init.svc.adbd`**：

```bash
adb shell getprop init.svc.adbd            # running 才算活着
```

⚠ `Settings.Global.ADB_ENABLED` 是诱饵：写 0 或 1 都启停不了 adbd，「开发者选项里开 USB 调试」在这台没用。

### 通用解法：把属性 toggle 0→1

制造一次变化让 init 点火，任何「触发器只认变化」的固件都适用，只需换成本机的真开关属性名：

```bash
adb shell setprop persist.service.adb.enable 0
sleep 1
adb shell setprop persist.service.adb.enable 1
adb shell getprop init.svc.adbd            # running 即成功
```

本机是用自建 App `app.adbkeep`（无障碍服务）在开机后 5/15/45/120/300 秒各试一次，
外加 10 分钟 watchdog 兜底。它做的不外乎上面这三行。

旁证：上游 `tools/install.sh` 的 `KEEP_ADB=1` 就是为同类现象准备的。

其他提醒：
- `ro.adb.secure=1`，adbd 走 RSA 认证，已授权公钥记在 `/data/misc/adb/adb_keys`，换电脑连要在电视上确认一次，重启不失效。
- `adbd` 的 `PPid=1`（`cat /proc/<pid>/status`），由 init 直接拉起，**与任何 App 都无关**，所以这类电视不需要装「ADB 保活」类工具去「守进程」，只需要制造属性变化。

### ADB 常开：需要两半，Aerial 的 `keep_adb` 只做了一半

Aerial 自带「保持 ADB」开关（`GuardSetup.ensureAdb`），但它只写标志位：

```kotlin
if (Settings.Global.getInt(ADB_ENABLED, 0) == 1) return   // 已是 1 就直接返回
Settings.Global.putInt(ADB_ENABLED, 1)                     // 只写这个
```

**它不启动 `adbd` 进程。** 这台固件的 `ADB_ENABLED=1` 只是「允许 ADB」，实际 adbd 由 init 的属性触发器驱动（见本节开头）。所以「ADB 常开」需要两者配合：

| 要做的事 | 谁来做 |
|---|---|
| 保住 `ADB_ENABLED` 标志 | Aerial 的 `keep_adb`，或手动 `settings put global adb_enabled 1` |
| **真正拉起 adbd** | 属性 toggle 0→1（init 触发器）|

打开 `keep_adb`：

```bash
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
```

本机的实际做法是 `app.adbkeep` 在开机后 5/15/45/120/300 秒各试一次属性 toggle，外加 10 分钟 watchdog，与 `keep_adb` 互补。

## 安装限制：PMS 的 compareSignatures，不是策略链

MT5879 那台是 `VerifierReciver` + `InstallStrategyManager`，`PmStrategy` 一律拒绝 adb/pm 安装
（`INSTALL_FAILED_VERIFICATION_FAILURE`）。

**这台 Android 8 没有那条策略链**，拦人的是 `PackageManagerService` 自己：

```
INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES:
disable install launcher app not signature by tcl
```

从 `/system/framework/oat/arm64/services.vdex` 反出来的逻辑
（注意：`/system/framework/services.jar` 只有 315 字节，是空壳，真身在 vdex）：

```java
// PackageManagerService.<clinit>，只在 system_server 启动时读一次
matrixInstallerEnalbe = SystemProperties.get("persist.sys.matrix.mi.enable", "0");

// compareSignatures()
if ("1".equals(matrixInstallerEnalbe) && launcherApp) return SIGNATURE_MATCH;  // 直接放行

// isTclLauncher(pkg)：签名与平台一致 && meta-data com.tcl.app.type == com.tcl.ui
```

### 三个安装开关，作用不同

TCL 在这台上有三组互不相干的安装开关，踩过的坑多半是只开了一组：

| 属性 | 作用 | 影响面 |
|---|---|---|
| `persist.sys.matrix.mi.enable` | `compareSignatures` 放行（上面那段）| **只影响 `adb install`（streamed）**；开了才能装非 TCL 签名的桌面 |
| `persist.tcl.debug.installapk` + `persist.tcl.installapk.enable` | `pm` 通道的开关。关着时报 `install apk has be disabled from pm by system default!`，开着时报 `install apk has be enabled from pm by user!` | **影响任何走 pm 的安装**：`adb install --no-streaming`、`adb shell pm install`、系统安装器界面 |
| `settings put global package_verifier_enable 0` | AOSP 的安装校验总开关 | 这台**没有**那条策略链（`VerifierReciver` / `InstallStrategyManager` 是 MT5879 的），设了也走不到 |

```bash
# 三个都开，覆盖所有安装路径
adb shell setprop persist.sys.matrix.mi.enable 1
adb shell setprop persist.tcl.debug.installapk 1
adb shell setprop persist.tcl.installapk.enable 1
adb shell setprop ctl.restart zygote     # mi.enable 是 static 字段，必须重启 framework
```

⚠ **只在 `adb install` 上测 `mi.enable`、在 `pm install` 上测那两个 tcl 属性，会得出「无效」的错误结论。** 三条路径要分别验证：

```bash
adb install app.apk                      # 受 mi.enable 管
adb install --no-streaming app.apk       # 受 persist.tcl.installapk.enable 管
adb shell pm install /data/local/tmp/app.apk   # 同上
```

用户侧看到的 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，通常就是 `persist.tcl.*` 那一组被关了。

### 免 root 安装

```bash
adb connect <tv-ip>:5555
adb shell setprop persist.sys.matrix.mi.enable 1   # 开签名放行
adb shell setprop ctl.restart zygote               # 软重启让 system_server 重读那个 static 字段
adb install aerial-standard.apk                    # Success
```

`matrixInstallerEnalbe` 是 `static` 字段、`<clinit>` 只在 system_server 启动时跑，**改完必须让 framework 重启**。
`ctl.*` 在这台 shell 可写，软重启约 4 秒、不熄屏，不必真重启：

| 实验 | 结果 |
|---|---|
| 置 0 + 软重启后安装 | 拒绝：`disable install launcher app not signature by tcl` |
| 置 1 + 软重启后安装 | `Success` |

⚠ **覆盖安装不触发这条校验**。装过官方版之后 `adb install -r` 升级直接 Success，连软重启都不用。
前提是**同签名**；换自编译签名版时首次安装仍需 `mi.enable=1`。

## 开机直达：四条命令，两件事

这台固件上「开机进 Aerial」需要两个独立条件，缺一不可。踩过的坑几乎都来自只满足其中一个。

### 四条命令

```bash
# 前三行：开机时把 Aerial 拉起来
adb shell setprop persist.tcl.bootappmode 1
adb shell setprop persist.SDK.boot.userapp app.orchard.tv
adb shell setprop persist.SDK.boot.useractivity app.orchard.tv.MainActivity

# 第四行：把 Aerial 设成默认桌面
adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity
```

三条 `persist` 属性**缺一不可**（逐项实测见下表），少任何一条开机都退回 TCL 桌面。第四条缺失时开机仍会切到 Aerial，但 `resolve-activity -c HOME` 解析到 TCL 桌面，按 HOME 键回 TCL。

### 三条属性分别做什么

`ActivityManagerService` 在开机后「没有可恢复的 Activity」时走这段（`services.jar` 反编译还原）：

```java
int bootappmode = SystemProperties.getInt("persist.tcl.bootappmode", 0);
String matrixitvstate = TvManager.getProperty().get("app.matrix.currentstate", "tcl");

if (bootappmode == 1) {                                    // 路径 A
    if (startThirdApk(userId)) return true;
}
if (bootappmode == 0 && "itv".equals(matrixitvstate)) {    // 路径 B
    if (startThirdApk(userId)) return true;
}
```

**① `persist.tcl.bootappmode 1`** —— 选路径。`1` 走路径 A，无条件调 `startThirdApk`；`0`（不设时的默认）走路径 B，额外要求 `app.matrix.currentstate == "itv"`。后者是非 persist 属性，由 `TvManager` 运行期写，冷启动时通常是空或 `tcl`，路径 B 在冷启动基本进不去。设 `=1` 的意义就是绕开那个额外条件。

**② `persist.SDK.boot.userapp app.orchard.tv`** —— 要启动的包名，`startThirdApk` 拿它当 `Intent.setClassName(packageName, activityName)` 的包名。框架对两个特殊值直接拒绝启动：`"Null"` 和 `"TV"`（后者表示回电视信源）；空字符串同样不启动。

**③ `persist.SDK.boot.useractivity app.orchard.tv.MainActivity`** —— 要启动的 Activity 全类名（`android:name` 的原值）。它和 ② 必须同时有值，`startThirdApk` 里任何一个为空就跳出不启动。

### 与 `persist.sys.launcher.component` 的区别

| | `persist.SDK.boot.*`（这三条） | `persist.sys.launcher.component` |
|---|---|---|
| 生效位置 | `AMS.startThirdApk()` | `ActivityStarter.startActivity()` |
| 做了什么 | 构造普通 Intent 拉起指定 Activity | **改写目标 Activity 的 `applicationInfo.packageName`** |
| 目标 App 感知 | 无，和普通启动一样 | 以为自己是 `app.orchard.tv` |
| 第三方 App | ✅ 正常 | ❌ `SecurityException` 崩溃 |
| 出厂默认 | 空 | 空 |

**这个属性出厂就是空，任何情况下都不要设它。** 它不是开机直达的开关，而是「所有第三方应用都打不开」的根因（见「已知问题 3」）。

### 逐项实验（软重启与整机重启各验一遍）

| 改动 | 结果 |
|---|---|
| 四条全设 | ✅ 开机进 Aerial，HOME 键回 Aerial，第三方 App 正常 |
| 只清 `persist.SDK.boot.useractivity` | ❌ 进 TCL 桌面 |
| 只设 `persist.tcl.bootappmode 0` | ❌ 进 TCL 桌面 |
| 只清 `persist.SDK.boot.userapp` | ❌ 进 TCL 桌面 |
| 三条属性有、缺 `set-home-activity` | ⚠️ 开机切到 Aerial，但 `resolve-activity` 仍是 TCL 桌面，HOME 键回 TCL |
| 把 `persist.sys.cyberui.boottoui` 改成 `on` | 无影响（它只管 TCL 桌面自己进不进 UI） |

### 时序：开机先出壁纸，不再黑屏

冷启动修复（三层 CE 存储 gate，见「已知问题 1」）把「开机后有一段黑屏」解决掉了。现在开机的实际顺序是：

```
开机 → TCL 桌面起来（无画面停留）
     → Aerial 冷启动，先画上次保留的 backdrop 壁纸
     → 约 10 余秒后 app 列表、磁贴就位，切到 Aerial 前台
```

日志里能看到对应几行：

```
onCreate before the user is unlocked; showing the kept backdrop
onCreate null restored=false; ...
Home loaded: 12 tiles, 7 inputs, 763 ms
```

也就是说**第一帧是壁纸，不是黑屏，也不是 TCL 桌面**——比早前记录的「开机黑屏几秒」好得多。

### 恢复出厂后的状态

这四条**都记不住**（`persist.*` 属性与 PMS preferred 记录都会清），需要重设。`adb install -r` 覆盖升级后 `set-home-activity` 的记录**不会丢**（见下节）。

## `set-home-activity` 的记录会丢吗：实测修正

本文早前记的「三方 launcher 的 preferred 记录重启就丢、每次重启要补一次」**不准确**。实测：

| 记录的 `mMatch` | 重启后 |
|---|---|
| `0x100000`（用 `cmd package set-home-activity` 设的） | ✅ **保住**，`isDefault=true`，`mAlways=true` |
| `0x0` | ❌ 丢，`resolve-activity` 变 `ResolverActivity` |

验证方式：把 Home 设成 `com.tcl.cyberui`（`mMatch=0x100000`），**关掉 Guard、软重启**，重启后记录仍是 `0x100000`；设回 Aerial 同样稳定。

也就是说：**用 `set-home-activity` 设的记录是稳的**。之前会丢，是因为 `persist.sys.launcher.component` 非空——那个属性会让 PMS 的 preferred 校验失败（`mMatch` 算不出匹配位）。属性留空后记录重启不丢，`adb install -r` 覆盖升级后也不用补。

## 原生桌面的「开机记忆」：与 Aerial 无关

`com.tcl.cyberui` 有一套自己的开机模式记忆（`BootModeProvider$TRomProvider`），查清后确认**它管不到 Aerial**：

```java
// strToMode()
"boot_launcher" → 1    // 开机进桌面
"boot_tv"       → 0    // 开机进电视信源
null / 其他     → 2/4  // 默认
```

主开关在 `Settings.System.boot_app_mode`（本机 `null`，落回 `Settings.Global.boot_app_mode_compat`，也是 `null`）。

| 属性 | 本机值 | 作用 |
|---|---|---|
| `persist.sys.cyberui.boottoui` | `off` | TCL 桌面三态：`on` = 开机自己进 UI，`off` = 不进，`default` = 跟随记忆。**实测改 `on` 对 Aerial 自启无影响** |
| `sys.cyberui.bootoui.flag` | 空 | 运行时记忆标记（非 persist，重启丢）|
| `persist.sys.storageSource` | `false` | 信源记忆 |
| `persist.sys.cyberui.curr_path` | `template1` | 上次停的页面路径 |

`SourcePersistManager.ifBootTvClearFlag()` 里读 `persist.sys.bootfromui`，`== "tv"` 时清掉「上次在 UI」的记忆：

```java
if ("tv".equalsIgnoreCase(SystemProperties.get("persist.sys.bootfromui", ""))) {
    Log.i("vicki-StartUpReceiver", "### bootfromui == tv, set persist.sys.storageSource false");
    setSourceBootPersist(false);
}
```

**这套机制只决定「开机先显示 TCL 桌面还是电视画面」，没有「启动指定 App」这一项**，所以不能用来开机记忆 Aerial。要开机进 Aerial 还是那四条命令。

## 亮度：TCL 的画质 SDK 对三方 App 开放

Aerial 自带滑块，来自 issue #363。反汇编确认，入口是 TCL 自己的 TV SDK：

| 调用 | 层 | 说明 |
|---|---|---|
| `com.tcl.tvmanager.TTvPictureManager.getBacklight()/setBacklight(0..100)` | 视频层 | HDMI / DTV 信源生效 |
| `TTvPictureManager.getGraphicBacklight()/setGraphicBacklight(0..100)` | 图形/OSD 层 | **桌面/App 前台时真正改 PWM 的是这层** |

- **零权限**：`Class.forName` 反射即可，不需要 root / 系统签名 / `WRITE_SECURE_SETTINGS`（uid 10009 的测试包实测通过）。
- **只写图形层就够了**：控制中心只在 Aerial 前台（STORAGE 信源）能打开，这时面板跟随图形层；只写图形层还能避免覆写用户在 TCL 面板里给 HDMI 设的画面亮度。
- TCL 自己的 `picture_backlight` 面板走同一条链，logcat 可验：
  ```
  BrightnessViewModel: is Show On Graphic
  → tsdkNative: setVideoCfgInt_native configType=grp_video_graphic_backlight, value_java=50
  → sita.video:  enter tos_picture_set_backlight e_mode=2, value=50, act=2
  → sitatvservice: fpp_system_pwm_set pwm_channel:2, duty:0x64 → 0x63
  ```
- 范围 1..100（上限与 TCL 面板同域）；实现每次调用都重新探活，因为**刚开机时 SDK 可能还没就绪**，一次性缓存「不支持」会让整场都没有滑块。
- 排掉的死路：`/sys/class/backlight/backlight/` 是 `max_brightness=1` 的哑设备；`settings put system screen_brightness` 读回成功但 PWM 不动；`Settings.Global/System/Secure` 无 `picture_backlight` 键；`tcl_tv`（`ITclTvService`）不是画质通道。

## 音量：固件自带音量条，`FLAG_SHOW_UI` 决定弹不弹

Aerial 控制中心有音量滑块。拖它的时候右侧会**多出一条 TCL 自己的竖向音量条**，和 Aerial 的滑块重叠。

### 根因：与 Aerial 无关，是固件行为

弹条的是 `com.tcl.settings.module_volume.VolumeDialogNew`（persistent 进程，uid 1000，`/vendor/app/SystemSettings/SystemSettings.apk`），**任何音量变化都会触发**：

```bash
adb shell input keyevent 25      # 纯注入、Aerial 完全不参与，一样弹
```

窗口形态：

```
mOwnerUid=1000  appop=SYSTEM_ALERT_WINDOW  ty=2006   # TYPE_SYSTEM_OVERLAY
mFrame=[1800,340][1888,740]
```

uid 1000 + `SYSTEM_ALERT_WINDOW` 的窗口，**应用侧拦不掉**。

### 触发条件只看 `FLAG_SHOW_UI`

| 路径 | flags | 结果 |
|---|---|---|
| Aerial 滑块（`adjustStreamVolume(3, ±1, flags)`） | 带 `SHOW_UI`（1）→ 弹 | `VolumeDialogNew: show:` |
| `media volume --adj lower` | 0 | **不弹**（窗口 0 条）|

对照日志：`VolumeBarService.VC: flags:0` → `onVolumeChangedW 1`，没有 `showUI`、没有 `VolumeDialogNew`。

### 关掉条之后，声音照样变

上游 issue #288 里作者问「flags=0 之后实际声音变了吗」，本机实测过：

```
media volume --adj lower   （flags=0，与去掉 SHOW_UI 后的滑块同一条路径）
  → AudioInvoke.setVolume
  → sita.audio: tos_audio_set_volume_ex volume=20/19
  → fpp_audio_set_volume(volume curve) ret = 0        ← 功放层确实收到

对照 input keyevent 24（flags=4097）
  → 同一个 fpp_audio_set_volume ret=0，另外多出 showUI + VolumeDialogNew: show:
```

**这台固件里 flags 只决定 OSD，音量值照常下到功放。** 所以去掉 `SHOW_UI` 是安全的。

### Aerial 侧怎么改（已并入上游 #288）

Aerial 里两处 `adjustStreamVolume(3, ±1, flag)`，那个 flag 由 `y2.k.d(ctx, "com.tcl.settings") ? 1 : 0` 决定——**只要装了 TCL 设置就给 `SHOW_UI`**。改成固定 0 即可。

免 root 的唯一路径是改 Aerial 的 flags（当前实现已经不带宽屏音量条需求）。**遥控器音量键是系统路径，应用改不了，仍会弹**——要接管得用 `MediaSession` 的 `VolumeProvider`。

### 试过但关不掉原厂音量条的（都是本机实测）

| 手段 | 结果 |
|---|---|
| `appops` deny 相关权限 | 无效（窗口属 uid 1000）|
| `pm disable` 音量组件 | `Shell cannot change component state` 拒绝 |
| `stop-service` | 被 persistent 进程重新拉起 |
| `force-stop com.tcl.settings` | 对 persistent 无效 |
| 整包 disable | 不可行（persistent + 系统设置依赖它）|
| `shared_prefs` 找开关 | 10 个文件里没有 volume / osd 开关 |
| manifest 找独立音量组件 | 没有 |

**结论：免 root 无解，只能改 Aerial 自己的 flags。**

## 已知问题

### 1. 冷启动时 MainActivity 崩 2 次（CE 存储未解锁）—— 已修复

开机 logo 消失后黑屏约 2 秒才出画面。system_server 把 MainActivity 创建了 3 次，前 2 次崩：

```
13:00:17.003  am_proc_start: [0,3166,10009,app.orchard.tv,activity,app.orchard.tv/.MainActivity]
13:00:17.388  am_crash: java.lang.IllegalStateException,
              SharedPreferences in credential encrypted storage are not available
              until after user is unlocked, ContextImpl.java,389
13:00:17.536  am_proc_start: [0,3275,...]     ← 系统重试
13:00:17.989  am_crash: [3275,...]            ← 又崩
13:00:18.195  am_proc_start: [0,3345,...]     ← 第三次成功
```

根因：TCL 的 `setDefaultLauncher()` 在 user 0 还是 `RUNNING_LOCKED` 时就把 launcher 拉起来了，
比标准 Android TV 早；而 `MainActivity.onCreate` 的初始化链一进来就读 CE 存储的 SharedPreferences。

⚠ 复现前提（不满足就复现不出来，排查时容易误判为「已修复」）：
必须**开机桌面由固件直接拉起** + **冷启动**。`setprop ctl.restart zygote` 软重启不重走解锁流程，**复现不出来**。
与 flavor 无关，多个版本都必现。

**早期尝试（都没归零，记录在此避免重复踩）**：
- 用 `UserManager.isUserUnlocked` 判断：不可靠，user 状态与 CE 挂载不同步，崩溃点挪到了 `HomeViewModel`。
- 直接 try/catch 探测 CE 存储可用性：崩溃 2 次 → 1 次。

这两次「只挡一层」的失败正是下面三条修法的由来：**崩溃点会一路往后挪**（Activity → ViewModel → 无障碍服务）。

**已根治（自编译版验证）：** 三条崩溃点都要挡，只挡一条会挪到下一个：

| 崩溃点 | 出现时机 | 修法 |
|---|---|---|
| `MainActivity.onCreate` → `DiagLog.init` → `getSharedPreferences` | 第 1 层 | `onCreate` 只搭窗口；等 `ACTION_USER_UNLOCKED` 再跑真正的启动 |
| `MainActivity.onStart` → ViewModel 懒加载 → `HomeViewModel.init` → `LayoutStore` | 挡掉第 1 层后出现 | `onStart`/`onResume` 在解锁前直接 return；解锁后由启动路径补调一次 |
| `HomeGuardService.onServiceConnected`（系统在解锁前就绑定了无障碍服务） | 挡掉前两层后出现 | `GuardSetup.prefs()` / `CecHome.prefs()` 解锁前返回 `null`：读取走默认值、写入丢弃 |

冷启动复验（01:24，`adb reboot`）：`dropbox` 里 0 条 Aerial 崩溃，前台序列只有一条 `MOVE_TO_FOREGROUND: app.orchard.tv`，进程年龄与 cyberui 同时刻。

⚠ **写这类「解锁门」时留意一个坑**：批量把 `context.getSharedPreferences(...)` 替换成自己的 `prefs(context)` 时，容易把 `prefs()` 内部的实现也替换成 `prefs(context)`——变成自己调自己，开机时 `StackOverflowError: stack size 8MB`（日志里是 `StackOverflowError at java.util.HashMap.hash` 一路重复同一个方法）。用 R8 的 `mapping.txt` 能直接把混淆后的方法名映射回源码行号，定位极快。

**上游从 1.43 起改了自己的写法（PR #334），与这里的做法不同但目的相同：**

```kotlin
// 1.46 的 MainActivity
private fun showLocked() {                       // 解锁前：只画 Wallpapers 保留的一张图
    window.setBackgroundDrawable(lockedBootBackdrop ?: ColorDrawable(Color.BLACK))
    registerReceiver(unlocked, IntentFilter(ACTION_USER_UNLOCKED))
    if (GuardSetup.isUserUnlocked(this)) startUnlocked()   // 检查与注册之间解锁了
}
private fun startUnlocked() { … recreate() }     // 解锁后重建整个 Activity
```

它**显示一张保留的画面**，等解锁后 `recreate()`。本机这套早期是「不画任何东西、等解锁后原地继续」，所以那会儿会黑一会儿；后来改成跟上游一致（解锁前先画保留的 backdrop 壁纸），现在开机第一帧是壁纸，见「开机直达 / 时序」一节。

上游 1.46 只修了 `MainActivity` 这一层；`HomeGuardService.onServiceConnected` 在解锁前仍然会跑 `refreshStockHomes()` 和 `GuardSetup.ensureAdb()`，但后者第一步就 `if (!isUserUnlocked(context)) return`，`wasHomeInFront()` 又在 `if (!isUserUnlocked) { … return }` 之后——所以第 3 层在上游不一定复现，本机是踩到了。

### 2. 开机时 TCL 桌面抢前台（cyberui 以 uid 1000 撞校验自杀）

```
08:42:24.8  am_create_activity: com.tcl.cyberui/.MainActivity   ← 前台被抢
08:42:25.9  am_crash: com.tcl.cyberui  SecurityException:
            Package app.orchard.tv does not belong to 1000
            at FKPageConfigManager.setIs4KEnv
08:42:25.9  am_finish_activity: app.orchard.tv/.MainActivity, force-crash   ← Aerial 被连带结束
08:42:26.0  Aerial 重建，恢复前台
```

**不是「默认桌面掉了」，而是从来没在 Android 层设过唯一默认。** 固件的 `persist.sys.launcher.component` 只管「开机拉谁」，HOME 的解析结果另算：两个包都声明了 HOME，`resolve-activity` 落到 `ResolverActivity`、`isDefault=false`（见「开机直达」一节的自检命令）。这种状态下某些启动路径会先把原桌面拉起来。

cyberui 起来后以 uid 1000 去读 Settings 里的「当前桌面包」，读到 `app.orchard.tv` 又不是自己，在 `FKPageConfigManager.setIs4KEnv` 撞 uid 校验自杀，顺手把 Aerial force-crash 一次。属固件侧行为，Aerial 无过错。

**修复**：`cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity`（执行后 `isDefault=true`）。

**这条修复是稳的**：设好之后 `mMatch=0x100000`、`isDefault=true`，真重启或 `ctl.restart zygote` 之后仍然保持（见「`set-home-activity` 的记录会丢吗」一节）。早前记的「每次重启都失效」，实际原因是 `persist.sys.launcher.component` 非空——那个属性会让 preferred 校验算不出匹配位；属性留空后记录不丢。

**真重启复验**（`adb reboot` 完整冷启动）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `com.tcl.cyberui` 的 `am_create_activity` | 1 次（抢前台 1.2 秒） | **0 次** |
| `com.tcl.cyberui` 的 `am_crash` | 1 次（`setIs4KEnv`） | **0 次** |
| `am_proc_start` | 有 | 只有 1 次 `[added application]`，空进程 |
| Aerial 被 force-crash | 有 | 无（只剩 CE 存储那 2 次） |
| 前台采样 | 闪一下 TCL 再回 Aerial | 10 次连续采样全是 `app.orchard.tv/.MainActivity` |

重连过程：`adb reboot` → 约半分钟后 adb 恢复 → 前台已是 Aerial。

这条解决的是「cyberui 抢前台」。「CE 存储未解锁导致前 2 次创建崩溃」另见「已知问题 1」。

### 3. 从桌面点开第三方应用，闪一下就回桌面

**这是本机最容易被误判成「Aerial 的 bug」的一条**，实际是固件行为：

```
java.lang.RuntimeException: Unable to start activity
    ComponentInfo{com.mytvb/com.mytvb.ui.activity.SplashActivity}:
  java.lang.SecurityException: Package app.orchard.tv does not belong to 10052
    at Parcel.readException(Parcel.java:1942)
    at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2896)
    at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3004)
```

**崩溃的是被打开的那个应用，不是 Aerial。** 根因是 `persist.sys.launcher.component` 非空时，`ActivityStarter` 会把目标应用的 `applicationInfo.packageName` 改写成该属性的包名：

```smali
:cond_267
const-string v5, "persist.sys.launcher.component"
invoke-static {v5, v7}, Landroid/os/SystemProperties;->get(...)      # 读属性
invoke-virtual {v45, v5}, Ljava/lang/String;->split(...)             # 按 "/" 切
aget-object v7, v46, v7                                              # 取包名部分
iput-object v7, v5, Landroid/content/pm/ApplicationInfo;->packageName  # ← 改掉目标应用的包名
```

接着：

1. `ContextImpl.<init>` 里 `mBasePackageName = LoadedApk.mPackageName`，uid 非 1000 → `mOpPackageName` 也成了 `app.orchard.tv`
2. `ContentResolver` 构造时 `mPackageName = mContext.getOpPackageName()`
3. TCL 在 `PhoneWindow.<init>` 加了一行 AOSP 没有的 `Settings.Global.getInt("force_resizable_activities")`（`.line 327-328`）
4. 这次调用带着 `app.orchard.tv`，uid 是目标应用自己的 → `AppOpsManager.checkPackage` 抛
   `SecurityException: Package app.orchard.tv does not belong to 10052`
5. 崩在 `Activity.attach`，进程还没起来就死了 → **表现为「从桌面点应用，闪一下就回桌面」**

实测对照（同一份包、同样的按键序列）：

| `persist.sys.launcher.component` | 结果 |
|---|---|
| 设为 `app.orchard.tv/app.orchard.tv.MainActivity` | mytvb（10052）、TCL 浏览器（10047）**全部** `does not belong to 100xx` 崩溃 |
| 空 | **都能正常打开** |

**排查时别被误导**：

- 与「桌面守护」无关（守护关掉照样复现）
- uid 1000 系的应用（`com.tcl.tv`、`com.tcl.ui_mediaCenter`）不会中招——那段代码对 `com.tcl.tv` 有 `equals` 特判
- 用 `adb am start`（uid 2000）启动同一批应用也不崩
- 崩溃记录里的 `Package:` 字段是**崩溃进程自己的包名**，不是启动意图的包名；两者不一致就说明是框架层改写的

**上游针对这点做过一次修复（9e27938，进 1.50），在本机无效**（issue #590）：那个提交去掉的是 Aerial 传给 `startActivity` 的 `ActivityOptions` 里的包名，而这台的改写发生在 `ActivityStarter` 里，早于 `ActivityOptions` 生效。装 1.50 后重新抓日志，`Package: app.orchard.tv ...` 依旧出现，`does not belong to 10052` 一字不差。

### 4. 信源面板固定列出 4 个 HDMI（实际 2 口）

固件侧正确（`com.tcl.tv` 只列 TV / HDMI1 / HDMI2 / AV，`source_support` 也只有 2 口），
多出的入口来自 Aerial 的 TCL 硬编码分支（`HDMI1..4` + DTV + AV1），因为这台没有 TvInputService
（`dumpsys tv_input` 为空）走了厂商兜底。已单独提 issue #279。

## 系统精简：28 个可停包与依赖关系

实测清单，全部 `pm disable-user`（**只停用、不删文件**），可 `pm enable` 恢复。共 28 个：
开机后进程数 266 → 258，前台正常，walleve/gamecenter 相关崩溃 0 条。

**广告 / 商店 / 预装推荐（11）**

```
com.tcl.bootadservice      开机广告          com.tcl.tshop              TCL 商城（Shop）
com.tcl.appmarket2         应用商店          com.tcl.appreciate.art     预装推荐
com.tcl.ffkaraoke          卡拉OK（雷咚咚）  com.tcl.tliveplay          直播回看
com.tcl.convergehome       首页聚合推荐      com.tcl.usercenter         用户中心
com.tcl.c2dm.client        推送通道          com.tcl.playskill          玩机技巧
com.tcl.waterfall.resource 瀑布流资源
```

**统计 / 搜索 / 内容类（10）**

```
com.tcl.bi             数据上报        com.tcl.usagestats      使用统计
com.tcl.wholenetsearch 全网搜          com.tcl.vod             VOD
com.tcl.ffeducation    雷咚咚少儿      com.tcl.common.weather  天气
com.tcl.common.viewer  图片播放器      com.tcl.tvhealthcheck   产品体检
jd.video.basecomponent 京东            com.tencent.qqmusictv   QQ音乐
com.tcl.weixin         微信互联        com.kookong.tvplus      机顶盒设置（出厂即 disabled）
```

**语音中台（2，先停）**

```
com.tcl.walleve          Walleve（含讯飞语音链路）
com.tcl.walleve.aidaemon AiDaemon
```

**升级组件（2）**

```
com.snm.upgrade          com.tcl.versionUpdateApp
```

⚠ 停掉升级组件后 TCL 固件不会自动 OTA。用自编译 Aerial 时本来也不该让它自动升级固件。

⚠ **停 `gamecenter` 之前先停 `walleve`，反过来会崩溃风暴。**

实测：只 `pm disable-user com.tcl.gamecenter`（T2 游戏中心）→ `com.tcl.walleve` **每秒崩一次**：

```
com.tcl.walleve.common.appControl.GameCenterUtil.initGameCenterId(GameCenterUtil.java:30)
  → 读 com.tcl.gamecenter 的 PackageInfo.versionCode → 该包 installed=false → NPE
```

实测：**先停 walleve、再停 gamecenter，30 秒内 0 条崩溃**，系统正常。两者可以都停，顺序是「先 walleve 再 gamecenter」。

另有历史记录：误操作 `pm enable com.tcl.walleve` 后 system_server 变僵尸、zygote64 进 D 状态，只能 `setprop sys.powerctl reboot` 救回——`pm enable` 一个本该停用的包也可能出事，动之前先记录当前状态（`pm list packages -d`）。

**必须保留**

| 包 | 原因 |
|---|---|
| `com.tcl.providers.config` | 内容提供者（`ConfigProvider`），别的 TCL 组件读配置要用 |
| `com.tcl.packageinstaller.service.renew` | 装 APK 走它 |
| `com.tcl.initsetup` | 恢复出厂后的开机向导 |
| `com.tcl.settings` | 设置面板（亮度/网络入口） |
| `com.tcl.tv` | 信源切换（HDMI1/2、DTV、AV） |
| `com.tcl.cyberui` | 法定 HOME |
| `com.tcl.guard` | 电视卫士（自启管理在它里面） |
| `com.tcl.shutdowndialog` | 关机/待机流程。**同时是这个固件的 ADB 授权弹窗宿主**（见「权限边界 / ADB 授权弹窗的归属包」）——停掉它，新的 ADB 密钥就再也弹不出确认窗 |
| `com.tcl.videoplayer` | 统一化视频播放器（媒体中心要用，停过又开回来了） |
| `com.tcl.factory.view` | 工厂菜单（调屏、查码） |
| `com.tcl.audioplayer` | 蓝牙单独听 |
| `com.tcl.micmanager` / `com.tcl.autopair` | 麦克风、遥控配对 |
| `com.tcl.recognize.service` | 画面识别（投屏匹配） |
| `com.tcl.miracast` / `com.tcl.MultiScreenInteraction_TV` | 投屏 |

## 权限边界（全程未用 root，全部为本机实测）

`adb shell` 是 uid 2000（shell），下面每一条都是在这台上跑过的结果。

**能做**

| 操作 | 说明 |
|---|---|
| `setprop persist.*` | 包括 `persist.service.adb.enable` 这类 init 触发器属性 |
| `setprop ctl.restart zygote` | 软重启，约 4 秒、不熄屏 |
| `adb install`（streamed） | 需 `persist.sys.matrix.mi.enable=1` |
| `adb install --no-streaming` / `pm install` | 需 `persist.tcl.debug.installapk` + `persist.tcl.installapk.enable` 都为 1 |
| `pm disable-user` / `pm enable` | 停用/启用整包（`pm disable` 走不通，见下） |
| `cmd package set-home-activity --user 0` | 设默认桌面，`mMatch=0x100000` 能扛重启 |
| `appops set <pkg> APP_AUTO_START allow` | TCL 自启白名单 |
| `appops set <pkg> REQUEST_INSTALL_PACKAGES allow` | |
| `pm grant <pkg> <permission>` | 危险权限，如 `WRITE_SECURE_SETTINGS`、`ACCESS_FINE_LOCATION` |
| `settings put secure <key>` | 包括 `enabled_accessibility_services` |
| `dumpsys package` / `dumpsys dropbox` | 读 preferred 记录、抓崩溃栈 |

**不能做**

| 操作 | 现象 |
|---|---|
| 写 `Settings.Global.ADB_ENABLED` 来启停 adbd | 写 0 或 1 都不影响 `init.svc.adbd`（见「开 ADB」一节） |
| `pm disable` / `pm enable`（不带 `-user`）整包 | `SecurityException`；改用 `pm disable-user` |
| `pm disable` 改组件状态 | `Shell cannot change component state` |
| 写 `/data/misc/adb/adb_keys` | `Permission denied`——目录 `2750 system:shell`、文件 `640`，组无写权限 |
| `stop adbd` / `start adbd` | `must be root` |
| `adb root` | `adbd cannot run as root in production builds` |
| 读 `/data/data/<其他应用>/` | 只有 shell 组成员的目录可读 |

## 命令速查

```bash
adb connect <tv-ip>:5555

# 首次部署
adb shell setprop persist.sys.matrix.mi.enable 1
adb shell setprop ctl.restart zygote                    # 软重启让开关生效
adb install aerial-standard.apk
adb shell cmd package set-home-activity app.orchard.tv/.MainActivity   # Android 层的默认桌面

# 开机直达（四条，详见「开机直达」一节）
adb shell setprop persist.tcl.bootappmode 1
adb shell setprop persist.SDK.boot.userapp app.orchard.tv
adb shell setprop persist.SDK.boot.useractivity app.orchard.tv.MainActivity
adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity
adb shell setprop persist.sys.launcher.component ""     # 必须为空，否则第三方应用全崩

# ADB 开机自启（属性 toggle 让 init 触发器点火）
adb shell setprop persist.service.adb.enable 0 && sleep 1
adb shell setprop persist.service.adb.enable 1
adb shell getprop init.svc.adbd          # 应为 running

# 还原到出厂桌面
adb shell cmd package set-home-activity --user 0 com.tcl.cyberui/.MainActivity
adb shell setprop persist.tcl.bootappmode ""
adb shell setprop persist.SDK.boot.userapp ""
adb shell setprop persist.SDK.boot.useractivity ""
adb shell setprop persist.sys.launcher.component ""

# 误卸天气导致设置打不开时
adb shell cmd package install-existing com.tcl.common.weather
```

## 找同类属性的方法（换机型时照做，别直接套结论）

1. `getprop | grep -iE "launcher|home|boot|adb|install|verif|sign|matrix"` —— 零成本枚举。
2. **从 `services.vdex` 里捞 system_server 用到的全部 persist 属性名**（免 root，最能说明问题）：
   ```bash
   adb shell grep -a -ohE "persist\.[a-zA-Z0-9._]+" /system/framework/oat/*/services.vdex | sort -u
   ```
3. 验证固件认不认：`getprop` → `setprop` → `ctl.restart zygote` → 看前台/看安装是否放行。
4. 反编译只作最后手段：pull vdex → 偏移 28 取 dex → 重算 SHA1(32:) 与 Adler32(12:) → jadx/androguard。
5. 读不到 `.rc` 时用**行为实验**：toggle 属性看 `init.svc.<svc>` 是否变化——adbd 的「变化才触发」就是这么测出来的。

6. **崩溃记录里的 `Package:` 是崩溃进程自己的包名，不是启动意图的包名。**

   ```
   Process: com.mytvb            ← 实际崩的进程
   Package: app.orchard.tv       ← 它以为自己是这个包
   ```

   两者不一致 → 框架层改写了包名。不要在应用层找，直接去 `ActivityStarter` / `PackageManagerService` 里 grep 相关属性。
7. **单变量实验**：一次只改一个属性，软重启和整机重启各验一遍。同时改多个会分不清谁生效——我最初测 `persist.SDK.boot.*` 时把 `bootappmode` 也设成了 0，误判为「无效」，后来单独设 1 才发现它是必需条件。
8. **软重启与整机重启行为不同**：非 persist 属性（如 `app.matrix.currentstate`）整机重启后丢，软重启保留。测开机逻辑两种都要验。
9. **区分「被 set 的属性」和「被 get 的属性」**：反编译时看属性名出现在哪个方法、是 `SystemProperties.get` 还是 `set`。

   | 出现方式 | 性质 |
   |---|---|
   | 只有 `set` | 状态记录（前台应用、上次路径等）|
   | 只有 `get` + 分支 | 真正的开关 |
   | `get` 后跟 `set` | 读后写的兜底逻辑 |

   `persist.sys.foregroundapp`、`persist.sys.bootfromui`、`persist.sys.bootpackage` 都是以 `set` 为主的记录，**设它们不会改变行为**。
10. **用现象判断，不用属性值判断**：`getprop` 只能说明属性写进去了，不能说明固件用了它。判断标准是开机后 `dumpsys window | grep mCurrentFocus` 是什么、`resolve-activity -c HOME` 解析到谁。**属性有值 ≠ 生效。**
11. **文档里的旧结论要重验**：这篇里「`set-home-activity` 重启就丢」「`persist.SDK.boot.*` 未触发」都是后来被实测推翻的。同一属性在不同固件版本上行为可能不同。

一个屡试不爽的技巧：**先让它失败一次，拿报错字符串当 grep 关键词**
（例如 `disable install launcher app not signature by tcl`）。
