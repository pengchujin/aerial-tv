# 极米 Z7X（国行，GMUI）

- 设备型号 `Z7X`，`ro.product.model` = `XGIMI TV`。
- GMUI 6.11.101.01_499（PG0017V6.12.68），Android 9（SDK 28），2 GB 内存 / 16 GB 存储。
- 测试日期 2026-10-21，系统为出厂状态，未 root、未刷机。

## 开 ADB：系统里没有入口

这台固件的设置里没有开发者选项（连点版本号无效），也没有可用的工厂模式：

- 在「设置 → 系统信息 / 设备信息」页长按遥控器 **左 / 上 / 菜单 / 返回 / 返回+主页**，均无反应，进不去工厂模式。
- 默认状态下 `adb connect <IP>:5555` 返回 `failed to connect ... Connection refused`。

可行的做法是用第三方 APK [code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)（v1.1.0 实测）：
U 盘安装后打开运行一次，它会写入 `persist.adb.tcp.port=5555`、`service.adb.tcp.port=5555`、`persist.sys.usb.config=adb` 并重启 adbd。普通 APK 权限即可，不需要 root、系统签名，也不进工厂模式。

App 里的「开机自动恢复」默认关闭，**关着的话重启后 5555 不会自己回来**，需要再打开 App 执行一次。需要长期保留 ADB 时把它打开。

第三方工具自行评估风险；Aerial 本身不依赖它，它只是这台机器开 ADB 的唯一入口。

## 安装

- 安装的 flavor：`standard`。
- `adb install -r aerial-standard.apk` → `Success`。这台固件不拦 adb 安装，没有 `VERIFICATION_FAILURE` 之类的问题。
- `cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity` → `Success`。
- 无障碍：原值 `com.xgimi.duertts/com.xgimi.duertts.MonitorService`，追加为 `...:app.orchard.tv/app.orchard.tv.guard.HomeGuardService`，可正常生效。
- 屏保：`screensaver_enabled=1` 时接管为 `app.orchard.tv/app.orchard.tv.dream.AerialDream`。

## 信号源

- 标准 passthrough intent（TvInputManager 输入自带的 `content://android.media.tv/...`）在这类固件上没有处理器：`am start` 报 `unable to resolve Intent`，主屏的信号磁贴和控制中心里的输入源都打不开，只弹一句「此电视不支持」。Z7X（GMUI 6.11）如此，`pig_chivalrous`（GMUI 9）实测一样。
- `Settings://com.xgimi.settings.signalSource/hdmi_misckey` 走 `ACTION_VIEW` 也没有 Activity 接。信号源页由导出的 `com.android.newsettings/com.xgimi.newsettings.SettingService`（action `com.xgimi.settings.SETTINGS`，extras `data` / `packageName` / `unlocked`）拉起，实测前台变成 `com.xgimi.xhplayer/.InputActivity`。

## 亮度

- 真实亮度在私有 DLP 光机 HAL `com.xgimi.gmpf.api.DisplayManager.getDlpLumensLevel()`（byte，1–10，10 = 满亮）。普通 `Class.forName` 即可加载，实例用 `getInstance()` 拿。系统 `screen_brightness` 不跟随它：拖系统亮度时这个值不变，所以不能拿系统设置当真实亮度。
- 1.42 及之前，控制中心那一行拖的是系统亮度：数字从 0 到 100 会动，画面一点不变。
- `setDlpLumensLevel(byte)` 在这台 GMUI 9（`pig_chivalrous`）的普通 App 进程里写入有效：实测 10 → 2 后 `getDlpLumensLevel` 立刻返回 2，跨进程一致，画面同步变暗，不黑屏（最低 1）。issue #241 在 Z7X 上判定的「第三方只能读」在这台固件上不成立，所以 1.42 之后这一行直接步进光机自己的档位。
- 极米亮度页是覆盖窗：打开时 Aerial 的 MainActivity 仍是 RESUMED，不触发 onPause/onResume，`SCREEN_BRIGHTNESS` 的 ContentObserver 也不触发。从极米页面改完亮度回到控制中心，只能靠控制中心自己轮询 HAL（每 700 ms）。
- 长按时按键每几十毫秒一次，而光机写入后要过一会儿才落到新档，读回拿到的还是上一档。所以控制中心这一行不按键读 HAL：按屏幕上的值本地步进，写入交给后台协程按顺序做、只保留最新档；每次步进后 400 ms 内忽略轮询结果，否则上一次读回会把滑块拉回旧档（数字已经变了、条还在原地）。

## 开机行为

- **1.19 / 1.20**：重启后停在极米原桌面，需要手动按一次 Home 才进 Aerial，`keep_adb` 已经下发也无效。
- **1.22**：重启后先显示极米桌面，几秒后自动切到 Aerial，全程不用按键。本条已由上游按修复关闭（#107）。
- 1.22 首次重启过程中出现过约 1~2 分钟黑屏，按一下电源键才亮起，起来后接管正常。1.19 没复现过；上游判断更像投影仪自身的待机策略，暂按个例记录。

## 其他实测

- 遥控器长按 Home 会触发系统级的后台清理，Aerial 也会被杀，随后由守护拉起，中间会闪一下原桌面。这是极米系统的既有行为，不是 Aerial 加的。
- 这台固件不允许 shell 读日志：`adb shell logcat -d` 报 `logcat read failure / uid=2000`，`pm grant com.android.shell android.permission.READ_LOGS` 被系统拒绝（提示该包未申请此权限），`-b all`、`-b events` 同样失败。需要取证时改用 `dumpsys activity activities`、`dumpsys window | grep mCurrentFocus` 定时采样。

## 默认桌面保不住、主页键慢 5 秒（m7642，Android 9：神灯 L1 #767、Z6X Pro #44）

两台都是 `MediaTek/m7642_db/m7642:9/PPR2.180905.006.A1/...`。以下是报告人的分析和实测，手上没有这类机器。

- `set-home-activity` 返回 `Success`，但默认桌面保不住：极米的 `com.android.newsettings/com.xgimi.newsettings.base.FallbackHome`（priority -1000）只声明了 HOME、**没有 DEFAULT**。`set-home-activity` 按 HOME + DEFAULT 记下两个候选（极米桌面、Aerial），而 FallbackHome 开机解锁后用不带 DEFAULT 的查询解析 HOME，候选变成三个，Android 9 的 `findPreferredActivity` 认为集合变了（`Result set changed, dropping preferred activity`），把 Aerial 降成非「始终」；之后落到极米的选择框，它读 `ro.vendor.lock.home=com.xgimi.home.immersed` 自动选回极米。
- 不带 DEFAULT 的 `cmd package resolve-activity` 也会触发同一件事，所以 `install.sh`、`agent.md`、`report.sh` 等的验收查询从这一版起都带 `-c android.intent.category.DEFAULT`。
- 默认桌面不是 Aerial 时，主页键先到选择框，Android 随后 5 秒内挂起后台启动（`stopAppSwitches`），守护 1.5 秒时的拉回要等到 5 秒（#44：每次 4965–4987 ms）。1.61 起守护认出选择框并拉回，不会再卡死，但这 5 秒 App 这边去不掉。
- 报告人的解法（#767，神灯 L1 固件 v1.3.237 实测，跨真实重启有效）：用 shell 调 `IPackageManager.replacePreferredActivity` 写两条都选 Aerial 的规则：HOME + DEFAULT（极米、Aerial 两个候选）和只有 HOME（再加 FallbackHome，三个候选，priority 1）。他用的是 `service call package 70`，事务号是这一版 framework 的，**换固件或换 Android 版本不能照搬**。没有停用任何系统应用。
- #44 报告人另一个办法是停用 `com.xgimi.home`，这和本项目「不停用原桌面」的原则冲突，文档和脚本都不采用。
- **极米 H5（AG122，`Product_xiaobai`，固件 `xgimi02292304`，FallbackHome 6.00.04.022）上双规则不持久，而且 install.sh 写的那条会让开机黑屏**（#1357，报告人实测，手上没有这台）：`set-home-activity` 写的 HOME + DEFAULT 记录在开机宽查询（三个候选）时被丢掉，并且这次开机就落到极米的 `ResolverActivity`，进程都没起来，`sys.boot_completed` 一直为空，FallbackHome 不结束，屏幕黑着（5 次复现）；ADB 打开一次极米桌面就恢复。双规则时，**宽规则在前**能开机，DEFAULT 在前照样黑屏；而重启后固件会把 DEFAULT 那条作废或把两条顺序翻过来，下一次开机又黑屏。报告人最后只留宽规则（`set="3"`）加守护兜底：开机进 Aerial，代价是主页键先闪 4–8 秒极米的「打开方式」再被守护拉回。H3S（`xgimi01062113`，FallbackHome 6.02.02.025）上两条规则持久，`xgimi-h3s-dual-home.py` 写入顺序是 DEFAULT 在前，**在 H5 上不能照搬**。install.sh 目前还会在这类固件上写 DEFAULT 记录，改之前要在 H5 上验证。
