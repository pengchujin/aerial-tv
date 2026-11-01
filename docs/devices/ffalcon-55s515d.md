# 雷鸟 FF55S515D-E674（tcl_m7642，Android 9）

2026-10-31 用户实机记录，Aerial **1.75-guard**。无需 root，未卸载或停用原厂桌面及其他系统应用。

## 设备

- 零售型号：用户提供 `FF55S515D-E674`。
- ADB product / model / device：`tcl_m7642`。
- Android 9，SDK 28；ABI：`armeabi-v7a,armeabi`。
- 原厂桌面：`com.tcl.cyberui/.MainActivity`。
- 未采集完整固件版本；不将型号后缀 E674 当作固件版本。

## ADB 与安装

`adb mdns services` 发现 `_adb._tcp`，端口 5555。初次连接为 `unauthorized`，用户在电视上允许调试后变为 `device`，无需配对码。本次未实测开发者选项的开启路径。

直接安装 standard APK 被固件拦截，输出（省略电脑本地 APK 路径）：

```text
Get install_switch_flag: 0
install apk has be disabled from pm by system default!
```

改用官方 `aerial-guard.apk`，按 [TCL 多屏互动流程](tcl-mt5879-cn.md)安装：电脑提供本地 HTTP 下载，`adb reverse tcp:8765 tcp:8765` 转发，运行项目 `tools/tcast-push.py`。电视右上角提示出现后，用户先按遥控器「右」选中「同意」，再按确认。脚本返回 `Installed.`，版本检查为 `versionName=1.75-guard`。结束后关闭临时下载服务、移除 reverse。

未测试 standard 版通过多屏互动安装，也未修改固件安装开关。

## 授权与桌面守护

按安装说明授权；`APP_AUTO_START`、`APP_ASSOC_START` 未报错，以下操作返回不支持：

```text
Error: Unknown operation string: ACCESS_RESTRICTED_SETTINGS
Error: Unknown operation string: AUTO_START
Error: Unknown operation string: ASSOC_START
```

启动 Aerial 返回 `Status: ok`，`cmd package compile -m speed -f app.orchard.tv` 返回 `Success`。守护自动加入无障碍列表，原有小T助手服务保留；`dumpsys accessibility` 中 Aerial 标签计数为 `1`。

保持 ADB 常开的配置广播返回：

```text
Broadcast completed: result=0, data="keep_adb=true guard_off_in_standby=false"
```

## 主页键、重启与待机

系统 HOME 解析结果仍为原桌面，由守护接管：

```text
priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
com.tcl.cyberui/.MainActivity
```

无障碍服务列表：

```text
com.tcl.walleve/com.tcl.walleve.first.accessibility.AiAccessibilityService:app.orchard.tv/app.orchard.tv.guard.HomeGuardService
```

经用户同意执行一次 `adb reboot`。之后曾黑屏且原 IP 暂时不可访问；用户被提示按电源键，随后反馈亮屏后先出现旧桌面，再自动进入 Aerial。未精确计时，不能把这次记录当成全程无人操作的冷启动验证。

亮屏后可重新连接 ADB 5555，`sys.boot_completed` 为 `1`，当前前台为 Aerial。再次模拟主页键并等待 8 秒，原始输出为：

```text
  mCurrentFocus=Window{417b1a8 u0 app.orchard.tv/app.orchard.tv.MainActivity}
```

用户另反馈：日常待机唤醒时，偶尔短暂显示旧桌面，随后自动进入 Aerial。「闪一下」指唤醒后的桌面切换，不是待机黑屏期间自行亮屏。

## 验证边界

- 已验证 guard 安装、守护运行、模拟主页键返回、上述重启亮屏后的自动接管及 ADB 重连。
- 未实现或验证完全跳过旧桌面；开机、部分唤醒场景会先显示原桌面。
- 待机表现来自用户反馈，未做多轮受控测试。
- 未验证断电冷启动、多轮重启、长时间待机、HDMI/CEC、4K 播放或实际功耗。
- 屏保接管配置已写入，但未实测屏保播放。
- [#82](https://github.com/pengchujin/aerial-tv/issues/82) 是同系列 **65S515D** 的另一份记录，不是本机测试。
