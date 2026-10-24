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

## 开机行为

- **1.19 / 1.20**：重启后停在极米原桌面，需要手动按一次 Home 才进 Aerial，`keep_adb` 已经下发也无效。
- **1.22**：重启后先显示极米桌面，几秒后自动切到 Aerial，全程不用按键。本条已由上游按修复关闭（#107）。
- 1.22 首次重启过程中出现过约 1~2 分钟黑屏，按一下电源键才亮起，起来后接管正常。1.19 没复现过；上游判断更像投影仪自身的待机策略，暂按个例记录。

## 其他实测

- 遥控器长按 Home 会触发系统级的后台清理，Aerial 也会被杀，随后由守护拉起，中间会闪一下原桌面。这是极米系统的既有行为，不是 Aerial 加的。
- 这台固件不允许 shell 读日志：`adb shell logcat -d` 报 `logcat read failure / uid=2000`，`pm grant com.android.shell android.permission.READ_LOGS` 被系统拒绝（提示该包未申请此权限），`-b all`、`-b events` 同样失败。需要取证时改用 `dumpsys activity activities`、`dumpsys window | grep mCurrentFocus` 定时采样。
