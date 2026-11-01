# 乐视超3 X65（X65-168CQ 标签，Letv X3-65）

一台 2016 年的乐视超3 X65：MStar 6A928（board `guava`），Android 5.0.1（API 21），EUI，
build `V2301RCN02C080080B06141S`。下面都是这台机器上实测的结果。

## 先打开 ADB

1. 遥控器停在「信号源」界面，按数字键 **2580**，进工程菜单
2. **Other Options → ADB Debug** 打开（这台的开关在工厂菜单的「通用设置」里）
3. `ro.adb.secure=1`，首次连接电视上要同意 RSA 授权

## 安装：能装、能渲染

minSdk 21 正好卡线：standard 版（改包名，见下）在 API 21 上**渲染完全正常**
（Compose 1.9.5，航拍壁纸、天气、dock、应用格子都好），启动 2.5 秒。
首帧可能黑屏几秒（航拍视频在加载），按一下方向键就出来了。

## 改包名：防内存管理器强杀

EUI 的内存管理器会定向强杀非厂商包名的应用（events 日志 `am_kill: stop <包名>`）。
把 `applicationId` 改成厂商命名空间（这台用 `com.stv.tvhome`）可躲过；Java namespace
不用动，applicationId 和 namespace 分离即可，androidx 的权限/authority 会自动跟随。

这台是 Android 5.0.1（pre-M），`pm grant` 不可用，WRITE_SECURE_SETTINGS 授不进去，
守护服务无法自启用，要手动：

    settings put secure enabled_accessibility_services <包名>/app.orchard.tv.guard.HomeGuardService
    settings put secure accessibility_enabled 1

## 传 APK：这台缺一批工具

`/system/bin` 里没有 `base64`、`nc`、`md5sum`、`toybox`、`which`（有 `busybox`：
用 `busybox base64` / `busybox md5sum` 替代）。adb sync 直推很快：Wi-Fi 稳定后
2.9 MB 五秒推完；第一次可能碰到网络抖动卡住，重试即可。

## 为什么接管不稳定：框架层强杀（这台最终不建议）

events 日志的模式：守护拉起 Aerial → 紧接着 system_server 强杀
（`Force stopping ... from pid <system_server>`），且紧跟
`PhoneWindowManager.interceptKeyBeforeDispatching`（HOME 键拦截）——
EUI 5.0.1 的框架在处理 HOME 时会强杀任何非配置桌面的桌面类应用。
standard/guard 版、改不改包名都一样；强杀还会清空无障碍绑定（`services:{}`）。

结果：按 HOME 后先闪原桌面几秒，守护反复拉起，体验不稳定。这就是 README
「老乐视不支持」的真正原因——不是打不开 ADB，而是框架级桌面保护。

## 和超4 X55 的对照

同品牌较新的超4 X55（Android 6.0，[issue #510](https://github.com/pengchujin/aerial-tv/issues/510) 第一条评论）
改包名后完全稳定——两代 EUI 的内存管理器放行规则不同。

卸载还原：`pm uninstall com.stv.tvhome`

## 超3 X55（X3-55）的信号源（#818 #998）

同代的 X3-55（MStar monaco / guava，安卓 5.0.1，两位用户）没有 `tv_input` 服务（`service check tv_input` 为 not found，`service list` 里只有 MStar 的 `com.mstar.android.tv.ITvService`），Aerial 一个信号源都列不出。乐视自己的 `com.stv.signalsourcemanager/.MainActivity` 用 `am start` 能直接切到信号源画面，但只回到上次用的口，`sourceId` 之类参数不认（三张截图逐字节对比）。main 上（未发版）控制中心「信号源」在没有信号源时打开它（`SystemProbe.openInputPicker` 最后一条，Lite 同），没有真机验证。按主页键时 EUI 自己会拉起桌面和这个信号源应用，和守护抢（见上文），这次没改。

## 超4 X4-50 / X4-75（安卓 6.0 eUI）

- **应用内升级后守护要等约 1 分钟才回来**（X4-75，#1021）：固件在 Aerial 退到后台时就强停它（打开系统安装器那一刻），装好新版后应用仍是「已停止」，安卓不给已停止的应用发 `MY_PACKAGE_REPLACED`，只能等同步服务复活（安卓 5.0 模拟器上模拟强停 + 覆盖安装，60 秒回来）。App 改不了；更新后从应用列表打开一次 Aerial 即可。这台 `settings get secure enabled_accessibility_services` 读出来是空的，守护其实已绑定，以 `dumpsys accessibility` 为准。
- **退出最后一个 App 后黑屏**（X4-50，#1023）：固件不拉起任何桌面，`mCurrentFocus=null`。守护重新开启后问用量统计，原来只看最后一次「移到前台」，把已退出的 App 当成还在前台。main 上改成看它之后的「移到后台」（`UsageFront`），未在真机验证。这台的用量统计也会报一个早就不在前台的 App（`com.chinasoul.bt`），不可全信。
