# 康佳 KKTV U86V9 / Android TV 9652

- 用户提供的零售型号：KKTV U86V9；系统型号：`Konka Android TV 9652`。
- Android 9 / SDK 28，MT9652，armeabi-v7a，`ro.config.low_ram=true`；2026-10-22 实测。
- HOME、完整重启、天气、屏保使用官方 Aerial 1.22 standard；信号源适配使用并行 release 测试版，保留原版及其数据。

## 安装、首次启动与 ADB

- 用户报告内置文件管理器拒绝安装 APK；经其他途径安装后，安装器点“打开”提示“无法运行此应用”，原桌面“我的应用”也没有 Aerial。首次成功侧载的路线记录不完整，不能当作可复现教程；启动异常根因未确定。
- 活动启动器可显式启动 `app.orchard.tv.MainActivity`。**活动启动器 2.4.1 在本机无法用遥控器完成所需操作，用户连接 USB 鼠标才完成操作并打开 Aerial**，下述路线目前需要鼠标。
- 普通设置和活动搜索中未找到开发者/无障碍入口，DevEnabler 的“打开开发者选项”失败。
- 实测 ADB 入口：活动启动器 → `com.konka.kkfactory.FactoryHome` → **软件调试功能 → 打开adbd**。开启后可连接网络 ADB，**adb root权限保持关闭**；不修改面板、校准、复位或升级参数。
- 后续建议（未实现）：提供遥控器适配的活动启动器版本，支持焦点、方向键、确认和返回，减少鼠标依赖；本补丁不包含活动启动器改造。

## 应用内更新

2026-10-23 用官方 standard 版实测 1.40 → 1.41：控制中心「下载并安装」下载 APK，进入康佳原生安装器，确认安装后点「立即运行」可打开 Aerial。UID、首次安装时间和原有配置保留；无需卸载重装。首次侧载时的「无法运行此应用」没有在这次更新中重现。

本机没有可打开的「安装未知应用」设置页，安装授权最初为默认值，1.40 因而在下载前报错。连接 ADB 后，为 Aerial 补齐这项授权即可走通上述流程（`tools/install.sh` 已包含）：

```bash
adb shell appops set app.orchard.tv REQUEST_INSTALL_PACKAGES allow
```

该授权保留供后续更新使用，安装仍需在系统安装器中确认。撤销时将 `allow` 换为 `default`，`tools/restore.sh` 也会复原。1.41 已有「授权设置页不存在时继续交给安装器」的兜底，但本机没有验证撤销授权后能否直接安装，不能据此省略授权步骤。

## HOME 与一次完整重启

- HOME 解析仍为 `com.konka.livelauncher/.TrueLauncher`，设置默认桌面返回 Success 不代表接管成功。Aerial 现有 HomeGuardService 绑定后，用户确认实体 HOME 键返回 Aerial。
- 一次经用户同意的完整重启后，系统启动完成、Aerial 已在前台；用户确认未按遥控器或手动打开应用就自动进入。待机/唤醒及长期稳定性未测。
- 未停用或卸载系统桌面。此 MT9652 / Aerial 1.22 结果不能代表 #88 的康佳 V560 / 海思机型问题已修复。

## 天气与屏保

- 天气：通过 Aerial 现有手机配置页手动选择城市后显示正常，未修改天气代码；自动定位失败的根因未确认。
- 屏保：关闭康佳“设置 → 通用 → 屏幕保护”，`global/ScreenSaver` 从 1 变 0；`secure/screensaver_enabled` 仍为 1，Dream 仍为 AerialDream。视频屏保无输入观察 183 秒、19 次前台采样，未再被原生屏保覆盖。信号源补丁不自动修改此开关，可在原设置页恢复。

## 原生信号源

`TvInputManager` 和 `dumpsys tv_input` 均无输入，康佳原生界面则有六个。普通应用可反射调用系统自带 `com.konka.android.tv.KKCommonManager`，无需引入厂商 JAR 或新权限：

- `getInstance(Context)` 使用 application context。
- `getTVInputSourceList()` 读取实际端口；`getAvaliableInputSourceList()` 读取已连接端口（`Avaliable` 为 SDK 原拼写）；`getInputSourceName(EN_KK_INPUT_SOURCE)` 读取原生名称。

| 原生名称 | 枚举名 | 本机观测 ordinal |
| --- | --- | --- |
| 数字电视 | DTMB | 4 |
| 模拟电视 | ATV | 0 |
| AV | AV | 10 |
| HDMI1 | HDMI | 35 |
| HDMI2 | HDMI2 | 36 |
| HDMI3 | HDMI3 | 37 |

切换组件：`com.konka.tvsettings/com.konka.tvsettings.RootActivity`（已导出）；action 为 `com.konka.tvsettings.intent.action.RootActivity`，整数 extra `source` 取运行时枚举的 `ordinal`，不是芯片硬件 ID。

表中数字仅为本机观测，代码不硬编码；SDK 虽定义 HDMI4，本机实际列表没有，不能据完整枚举捏造端口。

### 信号源验证边界

- Aerial 右上角已有信号源页显示六个输入，仅 HDMI1 标为已连接；用方向键和确认键切到 HDMI2，再切回 HDMI1，均进入原生播放器，SDK 的当前及保存输入与选择一致，最后恢复 HDMI1。
- 原生播放器保持前台直到主动导航；HOME 仍回到原版 Aerial。测试前后 HOME 解析、无障碍服务列表、Dream、康佳屏保开关和原版版本一致；未添加独立信号源界面。
- standard / guard release 构建通过；本机信号源在并行测试版验证。官方安装拒绝不同签名的覆盖包，应保留原版及数据，不为测试而卸载或清除。
- 未验证：视频播放、CEC 名称、插拔实时刷新、其他康佳机型、固定信号源到首页、开机打开信号源，以及适配版作为日常桌面的开机/待机/唤醒。HDMI“已连接”不代表存在可播放视频。

## 亮度：未解决

- Aerial 实验后端通过 `KKPictureManager.getBacklight()` / `setBacklight(short)` 将背光从 100 调到 40，读回值改变，但用户未见变暗。**康佳原生背光滑条从 100 调到 39，用户也未见变化**；两者使用同一 SDK 路径，具体固件/驱动原因未定。
- 系统未报告光感能力。临时关闭动态背光、保持面板开启并设为 40 的另一轮对照，底层 `PictureImpl.setBacklight(40, true, true)` 返回 0；用户随后反馈“无法判断”，不能据此认定有效或无效。
- 已恢复并读回背光 100、动态背光开启、面板开启；Android 亮度/模式仍为 47/0，图像亮度/模式仍为 50/`SOFT`（原生界面为“自然”）。调用成功或保存值改变不等于面板实际生效；未用图像黑电平或软件遮罩代替背光。
- 本信号源补丁不包含失败的亮度后端，不宣称修复亮度。未修改工厂校准、PWM、固件、root 或系统应用，已按用户要求停止继续测试电视。

## 康佳 Konka Android TV 9652（锤子 Smartisan TNT，m7642，安卓 9，#1452，报告人 Agent 实测）

和上面这台不同：这套固件开机后把 `accessibility_enabled` 写回 0、`dumpsys accessibility` 是 `services:{}`，`enabled_accessibility_services` 的字符串还在，守护不绑定；无障碍设置页和应用详情页都被删了（`ACCESSIBILITY_SETTINGS` 解析不到），通知监听也是 `0 mentions`（`ro.config.low_ram=true`）。`set-home-activity` 返回 Success 但不生效：主页键由 `gnt-services` 的 `InfiniteLauncher` / `startHomeNewStrategy` 按组件直接打开 `com.konka.livelauncher/.TrueLauncher`，不走 HOME 解析。开机组件在 `custom_boot_app` / `sys_direct_boot_app` / `persist.sys.app.launch`（组件名格式）。报告人的 adb 恰好是 root，组件级停用了 TrueLauncher 才让主页键回 Aerial，这违反「不停用系统应用」，不进安装说明；这台主页键回 Aerial 目前 App 这边做不到。
