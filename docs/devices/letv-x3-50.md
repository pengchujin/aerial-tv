# 乐视超3 X50（Letv X3-50）

一台 2016 年的乐视超3 X50：MStar 6A928，Android 5.0.1（API 21），EUI，
build `V2301RCN02C080080B06141S`。下面都是这台机器上实测的结果。
同代的超3 X65 见 [letv-x3-65.md](letv-x3-65.md)。

## 先打开 ADB

1. 遥控器停在「信号源」界面，按数字键 **2580**，进工程菜单
2. **Other Options → ADB Debug** 打开
3. `ro.adb.secure=1`，首次连接电视上要同意 RSA 授权

## 信号源：先预置 tags，否则 SignalSourceManager 必崩

乐视自己的 `com.stv.signalsourcemanager` 在 `SourceBase.enableSignalDesktop()`
里对 `mDesktopManager.getEnabledDesktopTags()` 返回的数组逐项调 `equals("signal")`：
数组本身是 null 就崩在 1300 行（取 null 数组长度），数组里含 null 元素就崩在
1301 行（对 null 调 `equals`）。数组内容来自原厂桌面（`com.stv.deskplatform`）
的 DataModel：`setDesktopShowTagAndName()` 按 `new String[screenInfos.size()+offset]`
预分配（全 null 起手），signal/live 槽位只有 `findPackage` 返回非 -1 才填；
恢复出厂后配置未重建，`findPackage` 极易返 -1，槽位留 null，而
`setEnabledDesktopTags` 不校验、原样存进 system service，此后
`getEnabledDesktopTags()` 一直返回带 null 的数组。

修法（已在 Aerial 里实现，见 `SystemProbe.primeDesktopTags`）：拉
SignalSourceManager 之前，先反射调
`letv.desktop.DesktopManager.setEnabledDesktopTags(["signal"])`，再读回校验。
这招照抄当贝的做法（当贝的 `SignalSourceUtil` 里就这一行 hack，三处都预置）；
预置收进 `openInputPicker()` 内部、LeTV 分支 `startActivity` 之前，所以遥控器
源键、控制中心信号源按钮都走这条路。Intent 去 `ACTION_MAIN`、加 `CLEAR_TASK`
（与当贝一致）。注意 `set` 是“后写者赢”，原厂桌面每次插件刷新都会重写 tags，
预置只保启动瞬间。

另外：这台固件没有 `tv_input` 服务，也没有 `com.mstar.tv.tvplayer`；
`TvCommonManager` 直切只改状态不出视频，`moveTaskToBack` 返回 true 但 Aerial
不离前台——这两条路都已证伪，别再试。

## 设置 / 网络 / 图片：走乐视自己的 globalsetting

原厂固件里根本没有 `com.android.settings`，所以 `ACTION_SETTINGS`、
`ACTION_WIFI_SETTINGS`、`ACTION_DISPLAY_SETTINGS` 全都不 resolve。乐视自己的
设置应用是 `com.stv.globalsetting`，`SettingActivity`（MAIN +
LEANBACK_SETTINGS + LAUNCHER）与 `NetworkActivity`
（action `com.letv.setting.NETWORK`）都已验证 exported，Aerial 的设置、网络、
图片设置都先走这两处。注意 `DisplayActivity` 未 exported，不能直接拉。

## 亮度：隐藏

这台的面板不跟系统 `screen_brightness`，滑杆动了也没用；“图片设置”那行只会
打开乐视设置主页、到不了亮度页，看着像坏了。所以控制中心在乐视上不显示亮度行，
亮度去乐视自己的设置里调。

## 主页键与守护

EUI 5.0.1 的框架在处理 HOME 时会强杀非厂商桌面，守护（HomeGuard）负责把
Aerial 拉回来，机制与强杀表现见 [letv-x3-65.md](letv-x3-65.md)
的“为什么接管不稳定”一节（同代同固件版本）。无障碍绑定与使用情况访问要手动开：

    settings put secure enabled_accessibility_services app.orchard.tv/app.orchard.tv.guard.HomeGuardService
    settings put secure accessibility_enabled 1
    appops set --user 0 app.orchard.tv android:get_usage_stats allow
