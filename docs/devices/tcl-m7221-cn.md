# TCL m7221（国行，75V2 / sugarcane）

> 和 [tcl-mt5879-cn.md](tcl-mt5879-cn.md)（Android 11 / MT5879）、[tcl-mt9655-cn.md](tcl-mt9655-cn.md)（Android 11+ / MT9655）是**三个不同平台**，拦安装的代码根本不在同一处，不要照着另一篇的方法试。

- 零售型号：TCL **75V2**；`ro.product.device` / `ro.product.model` = `sugarcane`；固件 `V8-S48CT05-LF1V142`（MS848C 机芯）。
- 平台 MStar **m7221**（`ro.board.platform` / `ro.hardware`），1920×1080，240dpi；Android **8.0.0**（SDK 26），`OPR5.170623.014 dev-keys`。
- 原桌面：`com.tcl.cyberui/.MainActivity`。
- 安装的 flavor：**`standard`**（`versionName 1.33`，`md5 fe1e1a8bb455abb9217eb56d7b956ff4`）。注意 `standard` 包内**同样带 guard 组件**（`guard/BootReceiver`、`guard/HomeGuardService`、`guard/RevivalSyncService`、`guard/RevivalAccountService`），并非只有 `guard` flavor 才有；本机 `enabled_accessibility_services` 里就有 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`，`dumpsys activity processes` 也能看到它对 system_server 的 `FGSA` 绑定，即守护在运行。
- 开机直达的主路径不依赖守护：固件的 `persist.sys.launcher.component` 直接拉起 Aerial（见下）。守护的作用是 HOME 被抢后切回来。
- 全程只用 `adb shell` 权限，**没用 root**。测试日期 2026-10-22 / 23。

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

## 开机直达：`persist.sys.launcher.component`

> ⚠ **设了这个属性，从 Aerial 打开别的 App 会崩**（#590，75V2 和 65C6 都复现）：固件在启动 Activity 时把被启动应用的包名换成属性里的包名，第三方 App 在 `Activity.attach` 里抛 `SecurityException: Package app.orchard.tv does not belong to <uid>`，表现为点开云视听极光、凤凰秀、TCL 浏览器闪一下就回桌面。要从 Aerial 正常打开 App，就不要设它（`setprop persist.sys.launcher.component ""` 清空）。65C6（`V8-S48CT02-LF1V172`）清空后改用固件的「开机起第三方 App」通道，完整重启后开机进 Aerial、主页键也回 Aerial，打开 App 正常：
>
> ```bash
> adb shell setprop persist.tcl.bootappmode 1
> adb shell setprop persist.SDK.boot.userapp app.orchard.tv
> adb shell setprop persist.SDK.boot.useractivity app.orchard.tv.MainActivity
> adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity
> ```
>
> 75V2 上这条通道没走通（PR #476），所以下面的方法只在不需要从 Aerial 打开第三方 App 时用。

TCL 在 AMS 里改了 `setDefaultLauncher()`，开机时读这个属性直接拉起指定组件：

```bash
adb shell cmd package set-home-activity app.orchard.tv/.MainActivity
adb shell setprop persist.sys.launcher.component app.orchard.tv/app.orchard.tv.MainActivity
```

两点要注意：

- 格式必须是**完整类名** `包名/类全名`。写成 `app.orchard.tv/.MainActivity`（相对类名）会黑屏。
- **它不校验组件有没有声明 HOME**，给任何 Activity 都能用。

实测（真重启，从 adb 恢复起每 0.5 秒采样 48 次）：

```
T+0s   发出 reboot
T+27s  adb 恢复，前台已是 app.orchard.tv/.MainActivity
T+27s ~ T+57s  48 次采样全部为 app.orchard.tv/.MainActivity
```

`mResumedActivity` 全程没出现过 `com.tcl.cyberui`；连按 3 次 Home 也停在同一 task。
比 guard 守护干净：不需要无障碍、不需要通知监听、不受 `APP_AUTO_START` / `APP_ASSOC_START` 限制。

**但固件属性只管「开机拉谁」，不管「Android 认为谁是默认桌面」——两者是两套机制，必须都对齐。** 判断后者：

```bash
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
# 正常：直接输出 app.orchard.tv/.MainActivity，且 isDefault=true
# 不正常：输出 com.android.internal.app.ResolverActivity，isDefault=false
```

出现 `ResolverActivity` 说明 HOME 有多个候选、没有唯一默认（这台两个候选 `com.tcl.cyberui` 和 `app.orchard.tv` 都声明了 HOME）。这时按 Home 键可能弹选择框，开机启动路径也可能先把原桌面拉起来——`com.tcl.cyberui` 起来后会读「当前桌面是谁」，读到 `app.orchard.tv` 又不是自己，在 `FKPageConfigManager.setIs4KEnv` 里抛 `SecurityException: Package app.orchard.tv does not belong to 1000` 自杀（见「已知问题」第 3 条）。

`cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity` 就是把这条补上：执行前 `dumpsys package | grep -A10 'Preferred Activities'` 里 `mMatch=0x0`，执行后变 `mMatch=0x100000 mAlways=true`，`resolve-activity` 也从 Resolver 变成直接命中。
**注意**：`adb install -r` 覆盖升级后这条记录可能被 PMS 重算，升级完要复查一次。

建议其他 TCL 机型先探一下有没有默认值：`getprop persist.sys.launcher.component`，再核一次 `resolve-activity`。

### 75V2：留着属性，改用裸组件启动（1.76，2026-11-01 实测）

上面那条警告在 75V2 上是死结：`persist.tcl.bootappmode` 通道走不通（PR #476），而 `persist.sys.launcher.component` 一设，从 Aerial 打开 App 就崩。1.76 起不再需要二选一。

固件 `V8-S48CT05-LF1V142`，Aerial 1.76（versionCode 97），属性保持 `app.orchard.tv/app.orchard.tv.MainActivity`：

- **1.76 已有的那处修复不够**。`MainActivity` 在 SDK < 28 时移除 ActivityOptions 里的 `android:activity.packageName`，但点 CIBN酷喵 仍然崩：`java.lang.SecurityException: Package app.orchard.tv does not belong to 10079`，`dumpsys activity` 里 `launchedFromPackage=app.orchard.tv`。改写发生在 AMS 层，不在 options 里，所以 [上一节](#开机直达persistsyslaunchercomponent) 的警告照样成立。
- **解法是裸组件**。`AppEntry.launchIntent()` 在固件带 `persist.sys.launcher.component` 时只 `setComponent`（不带 `ACTION_MAIN` + `CATEGORY_*`），固件便不改写来源；`MainActivity.open()` 同样把 `ACTION_DELETE` / `ACTION_APPLICATION_DETAILS_SETTINGS` 先 `resolveActivity` 再转裸组件，否则长按菜单的「卸载 / 应用信息」一样崩。属性为空（固件不改写）时两者行为都不变。
- **实测**：CIBN酷喵（`com.cibn.tv`）、云视听极光（`com.ktcp.csvideo`）、Kodi、TiviMate 从 Aerial 打开全部正常，`logcat` 里零 `does not belong to`；Home 键回 Aerial，不出 `com.tcl.cyberui`；长按菜单六项齐全，选「卸载」弹出 `com.android.packageinstaller/.UninstallerActivity`，取消后包完好。桌面布局和设置在重装后完整保留（27 个瓷砖、7 个信号源）。
- **重装注意**：自己编译的签名和官方不同，要先 `adb shell pm uninstall -k app.orchard.tv`（`-k` 保住 `/data/data`，布局和设置都在），但 `-k` 会**连 disabled-user 状态一起留下**：装完 `dumpsys package` 显示 `enabled=3`，`cmd package set-home-activity` 报 `IllegalArgumentException: Component ... cannot be home on user 0`，`query-activities` 里也找不到 Aerial。先 `pm enable app.orchard.tv` 再设，就 `Success`。
- 触发长按菜单用 `input keyevent --longpress 23`；**触摸 `input swipe X Y X Y 900` 在 Aerial 上不触发长按**（和 CleanYourTV 桌面相反）。

## 已知问题

### 1. 冷启动时 MainActivity 崩 2 次（CE 存储未解锁）

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

尝试过的缓解（**都没能归零**，记录在此避免重复踩）：
- 用 `UserManager.isUserUnlocked` 判断：不可靠，user 状态与 CE 挂载不同步，崩溃点挪到了 `HomeViewModel`。
- 直接 try/catch 探测 CE 存储可用性：崩溃 2 次 → 1 次。

根治要走 `createDeviceProtectedStorageContext()` + `android:directBootAware="true"`，把开机路径的配置迁到 DP 存储。

### 2. 冷启动时 TCL 桌面偶发抢前台约 1.2 秒，随后自己崩

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

**真重启复验**（2026-10-23 09:36，`adb reboot` 完整冷启动）：

| 指标 | 修复前（08:42） | 修复后（09:36） |
|---|---|---|
| `com.tcl.cyberui` 的 `am_create_activity` | 1 次（抢前台 1.2 秒） | **0 次** |
| `com.tcl.cyberui` 的 `am_crash` | 1 次（`setIs4KEnv`） | **0 次** |
| `am_proc_start` | 有 | 只有 1 次 `[added application]`，空进程 |
| Aerial 被 force-crash | 有 | 无（只剩 CE 存储那 2 次） |
| 前台采样 | 闪一下 TCL 再回 Aerial | 10 次连续采样全是 `app.orchard.tv/.MainActivity` |

重连过程：`adb reboot` → 35 秒后 adb 恢复（uptime 26.85s）→ 前台已是 Aerial。剩余问题只有「CE 存储未解锁导致前 2 次创建崩溃」那条（见「已知问题」第 1 条），即约 2 秒黑屏。

### 3. 「下载并安装」自动升级不可用

`REQUEST_INSTALL_PACKAGES` 已是 `allow`，但点下去 `com.android.packageinstaller/InstallStart`
启动即崩：

```
09:45:33  am_crash: com.android.packageinstaller  SecurityException:
          Package app.orchard.tv does not belong to 10011
          at Settings.Global.getStringForUser
```

与上面第 2 条同一类：调用方把自身 uid 与 `app.orchard.tv` 一起当 opPackageName 送进 Settings 的 binder 调用，
被服务端按「包必须属于调用 uid」拒。**升级只能 `adb install -r`。**

### 4. 信源面板固定列出 4 个 HDMI（实际 2 口）

固件侧正确（`com.tcl.tv` 只列 TV / HDMI1 / HDMI2 / AV，`source_support` 也只有 2 口），
多出的入口来自 Aerial 的 TCL 硬编码分支（`HDMI1..4` + DTV + AV1），因为这台没有 TvInputService
（`dumpsys tv_input` 为空）走了厂商兜底。已单独提 issue #279。

## 两个坑

1. **别卸载 `com.tcl.common.weather`。**
   桌面和控制中心的「设置」都依赖 `com.tcl.weather.provider`，卸掉后 `SettingsMainDialog`
   初始化抛 `SecurityException`，设置面板再也打不开。HDMI 界面走另一条入口、不受影响，容易误判。
   恢复：`cmd package install-existing com.tcl.common.weather`（APK 在 `/vendor/app/TCL_ALL_WeatherForecast/`，免 root）。
2. **TCL 有两个自己的无障碍服务**（`com.tcl.walleve`，「小T助手」），改 `enabled_accessibility_services` 时要保留。

⚠ **别去 `pm disable` / `pm enable` TCL 的系统应用。** 实测误操作 `pm enable com.tcl.walleve`
之后 system_server 变僵尸、zygote64 进 D 状态，整机崩，只能 `setprop sys.powerctl reboot` 救回。

## 权限边界（全程未用 root）

| 能做 | 不能做 |
|---|---|
| `setprop persist.*` | `pm install`（被 compareSignatures 拦） |
| `adb install`（streamed） | `pm disable` / `pm enable`（SecurityException） |
| `setprop ctl.restart zygote` | 动 TCL 的 system uid 应用 |
| `cmd package set-home-activity` | |

## 命令速查

```bash
adb connect <tv-ip>:5555

# 首次部署
adb shell setprop persist.sys.matrix.mi.enable 1
adb shell setprop ctl.restart zygote                    # 软重启让开关生效
adb install aerial-standard.apk
adb shell cmd package set-home-activity app.orchard.tv/.MainActivity
adb shell setprop persist.sys.launcher.component app.orchard.tv/app.orchard.tv.MainActivity

# 还原
adb shell setprop persist.sys.launcher.component ""
adb shell cmd package set-home-activity com.tcl.cyberui/.MainActivity

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

## sugarcane_mi（MStar m7221，安卓 8.0，#1076）

这台对 App 的 HOME 查询什么都不返回（`cmd package query-activities` 也是 `No activity found`），连 `com.tcl.cyberui` 都不列，守护就不知道原桌面是谁，开机后一直停在 cyberui（报告人的 Agent 在 2.5 / 4.5 / 6.4 分钟采样），打开一次 Aerial 后主页键才正常（走主页键广播那条判断）。2026-11-01 起把 `com.tcl.cyberui/.MainActivity` 列进守护的 `FIRMWARE_HOMES_WITHOUT_FILTER`。没有真机验证。同为 sugarcane 的 #898 能正常解析 HOME。
