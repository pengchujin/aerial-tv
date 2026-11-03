# 雷鸟 75S78A Plus（FFALCON T966D5，Android 14）

来自 #817 报告人的实测（2026-10-28），我们手上没有这台。

- `ro.product.model` = `Smart TV Pro`，`ro.product.device` = `T966D5`，`ro.product.name` = `T966D5_4K_CN`；品牌 TCL、厂商 FFALCON。
- Amlogic `t6x`，Android 14（SDK 34），固件 `AU01`；界面 1920×1080 / 320dpi，面板 4K（`ro.feature.graphics_4kui = false`）。
- 原桌面 `com.tcl.cyberui/.MainActivity`；`com.tcl.settings` 没有无障碍入口（`ACCESSIBILITY_SETTINGS` 只解析到 framework stub）。
- 安装：`adb install` 被拒（`INSTALL_FAILED_VERIFICATION_FAILURE`），只能多屏互动推 guard 版。ADB 明文 5555，重启后能自动重连。

## 守护开不了

- 固件大约每 52 秒把 `enabled_accessibility_services` 清成 `null`、`accessibility_enabled` 改回 0，Aerial 写回去（日志 `Home Guard had been switched off; turned it back on`）马上又被清。shell 写也一样。所以主页键回 TCL 桌面，没有放行办法。
- TCL 自启动管理（`TclAppBoot`）拒绝 `BootReceiver`、`RevivalSyncService`、`RevivalListener`，理由 `callee_does't_have_OP_AUTO_START_permission`。这台的 AppOps 叫 **`AUTO_START` / `ASSOC_START`**（MT5879 上叫 `APP_AUTO_START` / `APP_ASSOC_START`）。电视「权限管理 → 应用自动启动」或 `appops set` 能打开，但**每次开机前被固件改回 `ignore`**。`install.sh` 1.67 起两套名字都设，只管到下次重启。

## 开机进 Aerial：`boot_app_componentname`

TCL 自己的「开机进入」设置在这台上有效（MT5879 上试过无效，见 [tcl-mt5879-cn.md](tcl-mt5879-cn.md)）：

```bash
adb shell settings get system boot_app_componentname          # 原值 com.tcl.cyberui/.MainActivity，先记下
adb shell settings put system boot_app_componentname app.orchard.tv/.MainActivity
# 还原
adb shell settings put system boot_app_componentname com.tcl.cyberui/.MainActivity
```

报告人连续重启 3 次，开机 26 秒内前台都是 Aerial，之后没被打回。主页键仍然回 TCL 桌面（守护被清）。

## 雷鸟鹤 6 Ultra 2027 款 85 英寸（同为 T966D5 / AU01，#885）

报告人 2026-10-29 用 1.69 standard 跑完 `selftest.sh STANDBY=1 REBOOT=1 INPUTS=1`，全部通过：

- Aerial 是默认 HOME（`role holder: app.orchard.tv`，`resolve-activity` 解析到 Aerial），守护绑着，**没有**出现上面每 52 秒被清的情况。主页键从 App 回来 0.9 秒，待机唤醒 0.7 秒，重启后 2 秒采样就是 Aerial。
- 信号源 7 个（HDMI 1–4 = HW15–18、TV = HW2、AV = HW3、USB）都走 `com.tcl.tv/.TVActivity`（USB 走媒体中心），报告人确认画面对应正确，不用设备配置。
- 安装：普通 `adb install` 同样被拒；报告人临时改了 TCL 的桌面安装校验，用多屏互动推送装上后又改回了原样（细节在 #885，我们没有验证，也不建议照做）。
- 和 #817 那台差在哪里没查清：同一个 device 和固件号，一台能当默认 HOME、一台守护被清。单台结果，长期是否稳定没有验证。

## 雷鸟鹤 6 2027 款 55 英寸（T966D5 / AU01，guard 版，#1185）

2026-11-02 报告人实测（1.78-guard，多屏互动推送安装）：原厂开机组件是 `com.tcl.workmode/.portal.main.MultiModeActivity`。不改时重启后停在 Workmode，Aerial 进程不在、守护没绑定（无障碍列表里还有），`AUTO_START` / `ASSOC_START` 被改回 ignore。只把 `system boot_app_componentname` 改成 `app.orchard.tv/.MainActivity` 后 `adb reboot`，33 秒时前台是 Aerial、82 秒时守护绑定。只试了一次重启，没试断电冷启动和长期稳定；这台没出现 75S78A Plus 那样每分钟清无障碍。还原：写回备份的原值。

## 信号源编号：用系统登记的硬件编号（#792）

TCL Q9L Pro（安卓 14）和这台一样，系统登记 HDMI 1–4 = HW15–18、TV = HW2、AV = HW3，但没有 Activity 接标准的 passthrough 意图。Aerial 原来给 `com.tcl.tv/.TVActivity` 带 MT5879 的编号（HDMI 1–4 = 8–11），Q9L Pro 上 TCL 底层报 `thal_tvinput_get_info: devicde_id:0 is invalid`、无信号；报告人用 adb 试出 `--ei targetSourceId 15–18` 能切到 HDMI 1–4。TV、AV 的 2、3 和 MT5879 一致，所以 `targetSourceId` 就是硬件编号。2026-11-01 起系统登记了的输入一律用硬件编号，没登记的（MT5879）照旧。TCL 75Q10L（MT9655，安卓 14，#1107）同样是 HW15–18，报告人用 16–18 切到了 HDMI 2–4；那台装了 Solid Explorer，它能「解析」passthrough 的 `content://` 意图（按 provider 的 cursor 类型），磁贴就打开了文件管理器。所以 `com.tcl.tvinput` 的输入先走 TCL 播放器，再轮到通用意图。**本页 #885 那台报过 8–11 能切，这次改成 15–18 后没有复测**，发版后要请 T966D5 的用户确认。
