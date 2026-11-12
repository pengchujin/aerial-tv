# 极米 Z9X 4K 高亮版（Android 13，GMUI）

单台设备的有限实测记录，测试日期为 2026-11-01。保留并启用极米原桌面，没有 root、刷机或停用硬件服务。本记录不代表其他 Z9X 或其他 GMUI 版本均已验证。

## 设备与版本

| 项目 | 实测值 |
| --- | --- |
| 设备信息页型号 | Z9X 4K 高亮版 |
| GMUI | 6.18.201.02_735 |
| Android | 13，SDK 33 |
| `ro.build.version.incremental` | `v6.16.24` |
| `ro.product.model` | `XGIMI TV` |
| `ro.product.device` | `goat_elegant` |
| `ro.product.cpu.abilist` | `armeabi-v7a,armeabi` |
| 内存 / 存储 | 2 GB / 64 GB |
| Aerial | 1.76 standard，versionCode 97 |

设备是 Android 13，但本次实测用户空间 ABI 只有 32 位。不能从 Android 版本或“4K”名称推断它运行 64 位应用。

## 已验证的安装和守护

官方 `aerial-standard.apk` 可以通过 ADB 正常安装，安装响应为 `Success`，随后执行：

```sh
adb shell cmd package compile -m speed -f app.orchard.tv
adb shell am start -W -n app.orchard.tv/.MainActivity
```

实际前台与截图均为 Aerial。已有极米语音无障碍服务 `com.xgimi.duertts/com.xgimi.duertts.MonitorService` 保留；`HomeGuardService` 出现在实际 `Bound services` 中，不能只用 Enabled 列表代替绑定验收。

这台设备上，安装和首次启动后的检查已显示 `WRITE_SECURE_SETTINGS` 获授、守护已加入无障碍列表，但本次安装命令没有使用 `-g`，也没有执行通用安装脚本。**这些初始授权的来源未能确定**，因此不能据此说所有 Z9X 安装后都会自动获得所需权限。

## package 级悬浮窗授权与 UID 级授权

首次只设置 package 级 `SYSTEM_ALERT_WINDOW allow` 后，正常重启检查显示它回到了 `ignore`。当时守护仍绑定，实际前台为极米屏保；不能把“守护绑定”当成“开机接管已经成功”。

随后针对 Aerial 的应用 UID 设置了同一个 app-op：

```sh
adb shell appops set --uid app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell appops get --uid app.orchard.tv SYSTEM_ALERT_WINDOW
```

在这台设备的后续正常重启中，读回可能同时包含：

```text
Uid mode: SYSTEM_ALERT_WINDOW: allow
SYSTEM_ALERT_WINDOW: ignore; ...
```

这不是两层互相矛盾。Android 13 的 [AppOpsService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/services/core/java/com/android/server/appop/AppOpsService.java) 优先使用非默认 UID mode；shell 输出也注明 UID mode 覆盖 package mode。本次 UID allowance 跨重启保留。

**UID mode 影响同一 UID 下的所有包。** 操作前先保存原 app-op 状态，确认 Aerial 没有使用系统或其他应用的共享 UID；不要硬编码本记录设备的 UID，也不要把这条命令批量应用于所有极米机型。恢复时只恢复原来这一个 UID app-op；原先没有 UID override 才使用 `default`，不要全量 `appops reset`。

## 正常重启的隔离检查

一次后续对照在重启前先把原生默认 HOME 恢复成：

```text
com.xgimi.home/com.xgimi.module.cellview.home.ui.HomeActivity
```

保持已绑定的 Aerial 守护和 UID overlay allowance，再执行正常 `adb reboot`。观察期间没有发送启动 Aerial、Home、唤醒或权限写入命令。约 42.5 秒后观察到：

- 系统报告前台为 `app.orchard.tv/.MainActivity`；
- `Bound services` 中包含 Aerial 桌面守护；
- UID 级 `SYSTEM_ALERT_WINDOW` 仍为 `allow`。

这证明了上述配置在该次正常重启中的系统前台恢复。该次即时截图全黑，不能作为首页已经正常绘出的图像证据，也不能仅据截图判断真实投影画面黑屏；开机画面的可见性仍待实体观察。没有测试断电启动或长期连续运行，也没有证明单独关闭守护时 UID allowance 就足够。

## 默认 HOME 查询的注意事项

检查应同时包含 HOME 与 DEFAULT：

```sh
adb shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN \
  -c android.intent.category.HOME \
  -c android.intent.category.DEFAULT
```

本机 `set-home-activity` 当场返回 `Success`，带 DEFAULT 的查询也曾解析到设置的目标。但正常重启后，查询回到了：

```text
com.xgimi.xgimiframeworkui/.resolver.ResolverActivity
```

因此不能把命令当场成功等同于默认 HOME 能跨重启保留。当前实测接管依靠已有守护和 UID allowance，极米原桌面仍保留。旧款 Android 9 极米的 Binder 事务号或双规则方案没有在这台 Android 13 上验证，不应照搬。

## 本次没有验证的项目

- 实体遥控器 Home 键延迟与是否短暂闪出原桌面；ADB 造键不能替代实体遥控器验收。
- 深度待机后的唤醒、网络重连、断电启动。
- HDMI 视频输出、CEC、对焦和梯形校正、真实光机亮度调节。
- 4K/HDR 视频解码、4K UI 性能以及屏保的长时间播放。
- 未来新版的无人值守升级、升级后的开机与待机回归。

轻量特效和 1080p 航拍质量已通过公开 CONFIG 接口设置，但没有进行帧时间基准，不能据此声称性能提升了某个比例。网络 ADB 只在户主授权的可信家庭局域网使用；本记录没有修改设备认证机制，也不建议把 ADB 暴露到公网。

感谢维护者提供 Aerial，以及已经整理的极米机型笔记。希望这份有成功结果也保留限制的实测记录，能帮助其他同固件用户少走弯路。

## RS20 Pro Max（XGIMI TV DOHKO，mt9952 / horse_popular，Android 14，#1483，报告人实测 1.94 standard）

和 RS20 Ultra Max（#1379）同一个 DOHKO 平台。默认桌面留给极米，守护接管：`selftest.sh` 的 guard、home_on_aerial、待机（唤醒 0.9 秒回 Aerial）、重启（开机第 1 秒前台就是 Aerial）都通过。

- 没有 `android.settings.SETTINGS` 的 Activity，自检的「从别的 App 按主页键」退到桌面 App 列表时挑中了 `com.android.newsettings.mock.MockActivity`（起不来）。2026-11-12 起脚本先找 TV 的 App、跳过 mock。报告人手工从 SmartTube、文件管理等按主页键都 0.7–1.4 秒回 Aerial。
- 信号源：`com.xgimi.tvinput` 列出 HDMI1 / HDMI2，磁贴能切（HDMI1 实测出画面）；passthrough VIEW 没有 Activity 接，`open_source` 原来直接报 `ActivityNotFoundException`，2026-11-12 起和磁贴一样退到极米设置服务的信号源页。
- `logcat -d` 读不出来（`Logcat read failure`），要看 Aerial 的日志用控制中心「反馈问题」或 `dumpsys activity app.orchard.tv/.MainActivity`。
- 重启后 `SYSTEM_ALERT_WINDOW` 变 ignore、`REQUEST_INSTALL_PACKAGES` 变 default，UID 级的 allow 也没了（和本页 Z9X 不同）；开机接管照样成功。
- HOME 有时解析到极米的选择器 `com.xgimi.xgimiframeworkui/.resolver.ResolverActivity`，没写过首选记录，再重启又回极米桌面。
- 亮度条调的是光机档位（`getDlpLumensLevel`，1–10 档 ×10），Boost 档只能在极米自己的亮度页进，Aerial 显示 `10+`，和当贝 X7 Max 一致。
- 抖 4K（DLP472TP，1080p 微镜 XPR），安卓只有 1080p60，`no 4K UI plane` 正常。

