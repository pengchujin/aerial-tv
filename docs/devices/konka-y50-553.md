# 康佳 Konka Android TV 553（Y50）

来自用户反馈（#918，报告人用 AI 代理在自己的电视上核对），维护者手上没有。

- Hi3751V553，Android 5.1.1（YIUI），983 MB，`ro.config.low_ram=true`。出厂预装 `app.orchard.tv`，经应用内升级到 1.69。
- 没有 AOSP 设置（`android.settings.SETTINGS` 解析不到）。设置分两个包：`com.konka.tvsettings` 4.2（没有 V563 的 `MainSettingsActivity`，也没有 `SettingService`）和 `com.konka.systemsetting`（真正的设置界面，`.MainActivity`）。1.71 起设置入口在 V563 那条之后试它（完整版和 Lite）。
- 信号源：`KonkaSources` 的门槛（`tvsettings/.RootActivity` 导出）能过，切换正常。`getAvaliableInputSourceList()` 空载时也只报 HDMI 1，不是连接状态，1.71 起安卓 6 以前不显示「已连接」。
- 同为老康佳的 828（Android 5.0.1，#710）：`RootActivity` 不看 `source` 参数，只显示当前信号源；遥控器切源是 tvsettings 进程里 `TvManager.setInputSource(23/24/…)` 完成的（HDMI1 = 23）。要修得让 Aerial 直接调 `KKCommonManager`，需要诊断包验证方法名，未做。
