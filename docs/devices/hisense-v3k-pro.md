# Vidda V3K_PRO / MT9653，Android 11

实测固件：02.02.70.01，SDK 30，2026-11-01。系统型号属性为通用的 `VIDAA_TV`，不能据此给所有海信电视自动开启相同控制；目前通过这台电视自己的 device profile 启用。

## 音量和背光

这台电视忽略 Android 的 `screen_brightness`：数值写入成功，实际发光亮度不变。Android `STREAM_MUSIC` 和电视原生音量也可能不同步。控制中心应读取原生音量，并通过电视的 `TvManager.getAudioManager(context)` 设置音量/静音。这个管理器异步绑定 ConfigurationService 和 AudioService，不阻塞主线程等待连接；连接未就绪或写入被拒绝时，不写无关的 Android 镜像值冒充成功。

电视图像菜单通过 boot classpath 中的 `com.jamdeo.tv.common.ConfigOptionManager` 读写 `EnumConstants` 里的背光选项。原生背光范围实测为 0–30，Aerial 限制到 1–30，避免关闭面板。调节时除 `TIL_BACK_LIGHT_CUSTOM` 外，还要遵循原生菜单的模式处理：需要手动模式时设置 `TIL_BACK_LIGHT_MODE=0`；新式环境光感应开启时保留感应模式。所有接口和枚举按名称反射，不写死 Binder transaction 或枚举数字。

在诊断报告的现有 profile 基础上加入（整个 JSON 会替换这台电视原来的 profile，注意保留其他修正）：

```json
{"hisenseNativeVolume":true,"hisenseBacklightMax":30}
```

只有核实了本机原生接口、范围和物理效果后才开启；其他固件不要直接套用 30。关闭这条原生路线时，在 profile 中移除这两个字段。不能直调背光时仍可用 `ignoresScreenBrightness=true` 打开电视原生图像设置。此固件的 `DISPLAY_SETTINGS` 是旧的屏保/校准页面，真正的入口是 `com.android.tv.settings/com.hisense.systemsettings.MainActivity`。

诊断报告显示原生音量、背光比例、校准上限和背光模式，便于对照系统镜像值。没有新增权限、SDK、其他应用或应用内 ADB 通道。

## 开机和配置入口

原生设置中把「通用 → 开关机设置 → 开机信号源」选为「主页」，不要保留 HDMI。电视自己的启动管理入口是 `hisense.tv.assistant.APP_START_MANAGER`，应明确把 Aerial 关闭再开启保存一次；开关显示开启不等于固件保存了自启动许可。已启用的其他应用保持原样。

启动管理可能停用 Aerial 的 ConfigReceiver，导致配置广播立即返回 `result=0`、没有 `data`，而设置实际上没生效。Aerial 已有的接收器恢复机制现同时恢复自己的 BootReceiver 和 ConfigReceiver，前提仍是户主授予 WRITE_SECURE_SETTINGS；ConfigReceiver 的发送方权限限制不变。不恢复其他应用的组件。

并装原版与不同包名测试版时，还观察到：两份主界面均标记 directBootAware，而另一个桌面仅在解锁后可用，开机早期的候选集合变化会清除首选 HOME，尽管 HOME role 仍指向测试版。单独调整测试副本为解锁后进入可避免这个现象。此 PR 不修改正式版包名、签名、版本、更新机制或 directBootAware；不要据此全局修改所有机型的启动时机。

## 验证范围

实现基于 1.76 的独立 release 测试包，执行了 speed 编译，原版和数据均保留：

- 原生菜单 30→15，户主确认实际变暗；控制中心 30→15，户主同样确认明显变暗。较小的 30→25 调节最初看不出变化，没有把那次记作通过。
- 控制中心音量 15→8，户主确认重复提示音明显变小。音频输出方式按户主要求保留。
- 手动调节后背光模式从预设 1 变为 0；最终音量恢复 15、背光恢复 30。
- 原生自启动许可重存、测试副本开机时机修正后，真实重启的首分钟保持在 Aerial，随后 HOME 解析及按主页键后的窗口焦点仍是测试副本。
- 未卸载或停用系统应用，未改变已有 CEC 助手。没有在其他海信固件上验证；新环境光感应模式和外接音频设备的独立音量还没有真机覆盖。

原生控制测试覆盖显式启用、反射选项名称、音量/背光上下限、原生拒写读回，以及不写 Android 亮度镜像。提交版移植到最新 main 后另跑单元测试和两个 release 构建；移植后的官方包名版本未覆盖安装到户主电视（签名不同），真机结论仅指上述同一原生控制实现。
