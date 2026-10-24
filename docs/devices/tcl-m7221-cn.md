# TCL m7221（国行，75V2 / sugarcane）

> 和 [tcl-mt5879-cn.md](tcl-mt5879-cn.md)（Android 11 / MT5879）、[tcl-mt9655-cn.md](tcl-mt9655-cn.md)（Android 11+ / MT9655）是**三个不同平台**，拦安装的代码根本不在同一处，不要照着另一篇的方法试。

- 零售型号：TCL **75V2**；`ro.product.device` / `ro.product.model` = `sugarcane`；固件 `V8-S48CT05-LF1V142`（MS848C 机芯）。
- 平台 MStar **m7221**（`ro.board.platform` / `ro.hardware`），1920×1080，240dpi；Android **8.0.0**（SDK 26），`OPR5.170623.014 dev-keys`。
- 原桌面：`com.tcl.cyberui/.MainActivity`。
- 安装的 flavor：**`standard`**。在机上跑过 1.31 / 1.32 / 1.33 / 1.36 / 1.37 / 1.39 / 1.40 / 1.41 与自编译 1.41；**1.41 起官方自带 TCL 亮度滑块**（`TTvPictureManager`，见下「亮度」一节，来自 issue #363）。注意 `standard` 包内**同样带 guard 组件**（`guard/BootReceiver`、`guard/HomeGuardService`、`guard/RevivalSyncService`、`guard/RevivalAccountService`），并非只有 `guard` flavor 才有；本机 `enabled_accessibility_services` 里就有 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`，`dumpsys activity processes` 也能看到它对 system_server 的 `FGSA` 绑定，即守护在运行。
- 开机直达：固件 `persist.sys.launcher.component` 能直接拉起 Aerial，**但设了它之后第三方应用全都打不开**（固件会改写目标应用的包名，见「开机直达 / A.」一节）。现在这台用的是「属性留空 + Home Guard 抢前台」。守护仍要留着。
- 全程只用 `adb shell` 权限，**没用 root**。测试日期 2026-10-22 ~ 24（恢复出厂后 Magisk 已丢，`su: not found`）。

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

所以 MT5879 文档里的 `persist.tcl.installapk.enable=1`、`package_verifier_enable=0` **在这台无效**。

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

## 开机直达：三套机制，别只对齐一套

这台固件上有**三条**互不相干的「开机先起谁」通道，踩过的坑几乎都来自只对齐其中一条。

### A. `persist.sys.launcher.component` —— 固件属性，拉起指定组件

TCL 在 AMS 里加了逻辑：开机时读这个属性直接拉起指定组件。

```bash
adb shell setprop persist.sys.launcher.component app.orchard.tv/app.orchard.tv.MainActivity
```

- 格式必须是**完整类名** `包名/类名`。写成 `app.orchard.tv/.MainActivity`（相对类名）会黑屏。
- **它不校验组件有没有声明 HOME**，给任何 Activity 都能用。

⚠ **2026-10-24 新发现：这个属性是「装第三方桌面后所有 App 打不开」的根因。**

`ActivityStarter.startActivity()` 被 TCL 改过（`services.vdex`，`.line 413-416`）：

```smali
:cond_267
const-string v5, "persist.sys.launcher.component"
invoke-static {v5, v7}, Landroid/os/SystemProperties;->get(...)      # 读属性
invoke-virtual {v45, v5}, Ljava/lang/String;->split(...)             # 按 "/" 切
aget-object v7, v46, v7                                              # 取包名部分
iput-object v7, v5, Landroid/content/pm/ApplicationInfo;->packageName  # ← 改掉目标应用的包名
```

只要该属性非空，**每一次 Activity 启动，目标应用的 `applicationInfo.packageName` 都会被就地改成属性里的包名**。接着：

1. `ContextImpl.<init>` 里 `mBasePackageName = LoadedApk.mPackageName`，uid 非 1000 → `mOpPackageName` 也成了 `app.orchard.tv`
2. `ContentResolver` 构造时 `mPackageName = mContext.getOpPackageName()`
3. TCL 又在 `PhoneWindow.<init>` 加了一行 AOSP 没有的 `Settings.Global.getInt("force_resizable_activities")`（`.line 327-328`）
4. 这次调用带着 `app.orchard.tv`，uid 是目标应用自己的 → `AppOpsManager.checkPackage` 抛
   `SecurityException: Package app.orchard.tv does not belong to 10038`
5. 崩在 `Activity.attach`，进程还没起来就死了 → **表现为「从桌面点应用，闪一下就回桌面」**

同一份崩溃还出现在 `app.adbkeep`（uid 10053）、`com.mytvb`（10052）、`com.tcl.browser`（10047）……**只要目标 uid ≠ 1000 且不是 `com.tcl.tv` 就会中招**。那段代码里对 `com.tcl.tv` 有 `equals` 特判，TCL 自家系统和 uid 1000 的应用不受影响。

**所以这个属性和「第三方桌面可用」是互斥的**：
| 属性 | 结果 |
|---|---|
| 设为 Aerial | 开机直达 Aerial、不闪 TCL 桌面，但**所有第三方应用都打不开** |
| 留空 | 应用正常打开，但开机先起原桌面（`getCurLauncher` 读到 `android` 就返回 false，走默认路径） |

### B. `persist.tcl.bootappmode` + `persist.SDK.boot.userapp` —— 固件自带的「开机起第三方 App」通道

AMS 里 `startThirdApk(userId)` 读这两个属性，直接按 ComponentName 起应用，**完全不看 `launcher.component`**：

```bash
adb shell setprop persist.tcl.bootappmode 1
adb shell setprop persist.SDK.boot.userapp app.orchard.tv
adb shell setprop persist.SDK.boot.useractivity app.orchard.tv.MainActivity
```

⚠ **实测这条路在这台没走通**，卡在上游的门禁（见下一节）。属性值能写进去，但 AMS 没进 `startThirdApk` 分支。

### C. Android 层的默认桌面（preferred activity）

`persist.sys.launcher.component` 只管「开机拉谁」，Android 认为谁是默认桌面是另一套：

```bash
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
# 正常：app.orchard.tv/.MainActivity，isDefault=true
# 不正常：com.android.internal.app.ResolverActivity，isDefault=false
```

`isDefault=false` 时开机画面会落到空的 ResolverActivity 上，也就是**看到的那几秒黑屏**。

### 为什么直通通道在这台不触发（反编译结论）

`startHomeActivityLocked()` 的第一道门：

```smali
invoke-direct {p0}, ActivityManagerService;->getCurLauncher()Z   # 返回 false → 永远走不到 startThirdApk
```

`getCurLauncher()` 的头两条：

```smali
resolveActivity(MAIN + HOME)
if (packageName == "android") return false        # ← ResolverActivity 就是 "android"
if (persist.sys.launcher.component 非空) 按它设 ComponentName
```

这台重启后 `resolve-activity -c HOME` 恰好是 `ResolverActivity`（`isDefault=false`），**第一条 return false 就把 B 通道整条堵死**。

### `set-home-activity` 的记录为什么重启就丢

```
设 cyberui → dumpsys package 里 mMatch=0x100000  → 重启后保住
设 Aerial  → mMatch=0x0                          → 重启后丢，变 ResolverActivity
```

两者 manifest 的 HOME filter 都是 `HOME + DEFAULT`，`query-activities` 里两者 `match=0x108000` 完全一样，`getHomeFilter()` 源码也确实加了 `DEFAULT`——**所以不是 filter 的问题**。差别在签名：cyberui 在 `/system` 分区（system_app），Aerial 是三方 debug 签名。PMS 给三方 launcher 记 preferred 时算不到匹配位。

**结论：没有 root 的情况下，这一步修不掉。** 每次重启需手动补一次：

```bash
adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity
```

（`adb install -r` 覆盖升级后也要补，PMS 会重算。）
## 亮度：TCL 的画质 SDK 对三方 App 开放

Aerial 1.41 起自带滑块，来自 issue #363。反汇编确认，入口是 TCL 自己的 TV SDK：

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
- 范围 1..100（上限与 TCL 面板同域）；1.41 的实现每次调用都重新探活，因为**刚开机时 SDK 可能还没就绪**，一次性缓存「不支持」会让整场都没有滑块。
- 排掉的死路：`/sys/class/backlight/backlight/` 是 `max_brightness=1` 的哑设备；`settings put system screen_brightness` 读回成功但 PWM 不动；`Settings.Global/System/Secure` 无 `picture_backlight` 键；`tcl_tv`（`ITclTvService`）不是画质通道。

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
与 flavor 无关，1.31 / 1.32 都必现。

**早期尝试（都没归零，记录在此避免重复踩）**：
- 用 `UserManager.isUserUnlocked` 判断：不可靠，user 状态与 CE 挂载不同步，崩溃点挪到了 `HomeViewModel`。
- 直接 try/catch 探测 CE 存储可用性：崩溃 2 次 → 1 次。

这两次「只挡一层」的失败正是下面三条修法的由来：**崩溃点会一路往后挪**（Activity → ViewModel → 无障碍服务）。

**2026-10-24 已根治（自编译版验证）：** 三条崩溃点都要挡，只挡一条会挪到下一个：

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

它**显示一张保留的画面**，等解锁后 `recreate()`；本机这套是**不画任何东西、等解锁后原地继续**。前者看到的是上一次的壁纸，后者是黑屏（所以本机表现为「开机黑一会儿」）。要跟上游一致、连黑屏都不出现，就照 `showLocked()` 那条走。

上游 1.46 只修了 `MainActivity` 这一层；`HomeGuardService.onServiceConnected` 在解锁前仍然会跑 `refreshStockHomes()` 和 `GuardSetup.ensureAdb()`，但后者第一步就 `if (!isUserUnlocked(context)) return`，`wasHomeInFront()` 又在 `if (!isUserUnlocked) { … return }` 之后——所以第 3 层在上游不一定复现，本机是踩到了。

### 2. 开机黑屏几秒：HOME 解析到 ResolverActivity + TCL 桌面抢前台

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

⚠ **2026-10-24 复测：这条修复每次重启都失效。** 设好之后 `mMatch=0x100000`、`isDefault=true`，
但真重启或 `ctl.restart zygote` 之后又变回 `mMatch=0x0` / `ResolverActivity`。原因见「开机直达 / `set-home-activity` 的记录为什么重启就丢」——
三方签名 launcher 的 preferred 记录算不到匹配位，没 root 修不掉。**现在每次重启后要手动补一次**，这也是开机黑屏几秒的直接原因。

**真重启复验**（2026-10-23 09:36，`adb reboot` 完整冷启动）：

| 指标 | 修复前（08:42） | 修复后（09:36） |
|---|---|---|
| `com.tcl.cyberui` 的 `am_create_activity` | 1 次（抢前台 1.2 秒） | **0 次** |
| `com.tcl.cyberui` 的 `am_crash` | 1 次（`setIs4KEnv`） | **0 次** |
| `am_proc_start` | 有 | 只有 1 次 `[added application]`，空进程 |
| Aerial 被 force-crash | 有 | 无（只剩 CE 存储那 2 次） |
| 前台采样 | 闪一下 TCL 再回 Aerial | 10 次连续采样全是 `app.orchard.tv/.MainActivity` |

重连过程：`adb reboot` → 35 秒后 adb 恢复（uptime 26.85s）→ 前台已是 Aerial。

这条解决的是「cyberui 抢前台」。**当时还剩的「CE 存储未解锁导致前 2 次创建崩溃」已于 2026-10-24 另行修掉**（见「已知问题」第 1 条），但即便修掉，开机到出画面之间**仍会黑一段时间**——因为 HOME 解析到 `ResolverActivity`（见「已知问题」第 2 条与「开机直达 / C」）。

### 3. 从桌面点开第三方应用，闪一下就回桌面

**这是本机最容易被误判成「Aerial 的 bug」的一条**，实际是固件行为：

```
java.lang.RuntimeException: Unable to start activity ComponentInfo{com.dangbei.screencast/...AppListActivity}:
  java.lang.SecurityException: Package app.orchard.tv does not belong to 10038
    at Settings$Global.getInt(Settings.java:10308)
    at com.android.internal.policy.PhoneWindow.<init>(PhoneWindow.java:327)
    at android.app.Activity.attach(Activity.java:7003)
```

崩溃的是**被打开的那个应用**，不是 Aerial。根因是 `persist.sys.launcher.component` 非空时，
`ActivityStarter` 会把目标应用的 `applicationInfo.packageName` 改写成该属性的包名（见「开机直达 / A.」一节的 smali）。

实测对照（同一份包、同样的按键序列）：

| `persist.sys.launcher.component` | 结果 |
|---|---|
| `app.orchard.tv/app.orchard.tv.MainActivity` | 当贝投屏 / 快捷方式创建器 / TCL 浏览器 / mytvb **全部** `does not belong to 100xx` 崩溃 |
| 空 | `com.kookong.tvplus`、`com.tcl.MultiScreenInteraction_TV`、`com.tcl.ui_mediaCenter`、`com.tencent.qqmusictv` **都能正常打开** |

**排查时别被误导**：这个崩溃与「桌面守护」无关（守护关掉照样复现）；uid 1000 系的应用（`com.tcl.tv`、`com.tcl.ui_mediaCenter`）不会中招（那段代码对 `com.tcl.tv` 有 `equals` 特判）；用 `adb am start`（uid 2000）启动同一批应用也不崩。

### 4. 信源面板固定列出 4 个 HDMI（实际 2 口）

固件侧正确（`com.tcl.tv` 只列 TV / HDMI1 / HDMI2 / AV，`source_support` 也只有 2 口），
多出的入口来自 Aerial 的 TCL 硬编码分支（`HDMI1..4` + DTV + AV1），因为这台没有 TvInputService
（`dumpsys tv_input` 为空）走了厂商兜底。已单独提 issue #279。

## 系统精简：28 个可停包与依赖关系

2026-10-24 实测清单，全部 `pm disable-user`（**只停用、不删文件**），可 `pm enable` 恢复。共 28 个：
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

2026-10-23 实测：只 `pm disable-user com.tcl.gamecenter`（T2 游戏中心）→ `com.tcl.walleve` **每秒崩一次**：

```
com.tcl.walleve.common.appControl.GameCenterUtil.initGameCenterId(GameCenterUtil.java:30)
  → 读 com.tcl.gamecenter 的 PackageInfo.versionCode → 该包 installed=false → NPE
```

2026-10-24 实测：**先停 walleve、再停 gamecenter，30 秒内 0 条崩溃**，系统正常。两者可以都停，顺序是「先 walleve 再 gamecenter」。

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
| `com.tcl.shutdowndialog` | 关机/待机流程 |
| `com.tcl.videoplayer` | 统一化视频播放器（媒体中心要用，停过又开回来了） |
| `com.tcl.factory.view` | 工厂菜单（调屏、查码） |
| `com.tcl.audioplayer` | 蓝牙单独听 |
| `com.tcl.micmanager` / `com.tcl.autopair` | 麦克风、遥控配对 |
| `com.tcl.recognize.service` | 画面识别（投屏匹配） |
| `com.tcl.miracast` / `com.tcl.MultiScreenInteraction_TV` | 投屏 |

## 权限边界（全程未用 root）

| 能做 | 不能做 |
|---|---|
| `setprop persist.*` | `pm install`（被 compareSignatures 拦） |
| `adb install`（streamed） | `pm disable` / `pm enable` **整包**禁用/启用（SecurityException）—— 用 `pm disable-user` / `pm enable` 代替，这两个实测可用 |
| `pm disable-user` / `pm enable` | 改 `pm disable` 的组件状态（`Shell cannot change component state`） |
| `setprop ctl.restart zygote` | |
| `cmd package set-home-activity` | |
| `appops set <pkg> APP_AUTO_START allow` | |

## 命令速查

```bash
adb connect <tv-ip>:5555

# 首次部署
adb shell setprop persist.sys.matrix.mi.enable 1
adb shell setprop ctl.restart zygote                    # 软重启让开关生效
adb install aerial-standard.apk
adb shell cmd package set-home-activity app.orchard.tv/.MainActivity   # Android 层的默认桌面

# 开机直达（二选一，见「开机直达」一节的取舍）
#   A) 固件属性：开机第一帧就是 Aerial，但第三方应用全打不开
adb shell setprop persist.sys.launcher.component app.orchard.tv/app.orchard.tv.MainActivity
#   B) 固件自带的第三方 App 通道（本机实测未触发，留作对照）
adb shell setprop persist.tcl.bootappmode 1
adb shell setprop persist.SDK.boot.userapp app.orchard.tv
adb shell setprop persist.SDK.boot.useractivity app.orchard.tv.MainActivity
#   C) 不要直达、只要应用能用：属性留空，靠 Home Guard 抢前台
adb shell setprop persist.sys.launcher.component ""

# 每次重启后补一次默认桌面（PMS 会丢三方 launcher 的 preferred 记录）
adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity

# ADB 开机自启（属性 toggle 让 init 触发器点火）
adb shell setprop persist.service.adb.enable 0 && sleep 1
adb shell setprop persist.service.adb.enable 1
adb shell getprop init.svc.adbd          # 应为 running

# 还原到出厂桌面
adb shell setprop persist.sys.launcher.component com.tcl.cyberui/com.tcl.cyberui.MainActivity
adb shell cmd package set-home-activity com.tcl.cyberui/.MainActivity
adb shell setprop persist.tcl.bootappmode ""
adb shell setprop persist.SDK.boot.userapp ""
adb shell setprop persist.SDK.boot.useractivity ""

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

一个屡试不爽的技巧：**先让它失败一次，拿报错字符串当 grep 关键词**
（例如 `disable install launcher app not signature by tcl`）。
