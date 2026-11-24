# 长虹 65D6SMini（ZH120，hisi660 / Huanglong，Android 12）

来自 [#1751](https://github.com/pengchujin/aerial-tv/issues/1751) 报告人的实测（Aerial 2.16 standard），维护者手上没有长虹。同平台的 D6H Mini 有「打开 Aerial 卡住」的反馈，见 [#1409](https://github.com/pengchujin/aerial-tv/issues/1409)。

- 系统：`ro.product.model=Smart TV`，product `full_changhong_hisi660`，platform `huanglong`，固件 `V1.00200-ZH120`。
- **开发者选项「此用户无法使用」**：出厂时 `device_provisioned=0`（不是访客用户，也没有 `no_debugging_features`）。报告人在电视自带设置的「添加配件」（蓝牙配对）页打开蓝牙后，设置把 `device_provisioned` 写成 1，开发者选项就能用了。之后打开 USB 调试，`adb connect <IP>:5555`。重启后电脑的授权不一定保留，要在电视上再允许一次。
- **默认桌面**：长虹的 `PackageManagerService.chooseBestActivity()` 经 `ChPackageManager.getDefaultLauncher()` 读 `persist.sys.default_launcher`（原值 `com.changhong.system.launcher.v2`）。用长虹自己的系统服务改它：
  ```sh
  adb shell service call ChSystemService 3 s16 persist.sys.default_launcher s16 app.orchard.tv
  adb shell getprop persist.sys.default_launcher   # app.orchard.tv
  ```
  之后 HOME 解析直接是 Aerial（`isDefault=true`），冷启动直接进 Aerial，主页键回 Aerial，原桌面不用停用。transaction 编号 3 只在这台固件上核对过，别的长虹先 `adb shell service list | grep -i ChSystemService` 确认有这个服务，再读回属性核实，不要只看返回值。还原：同一条命令把最后的 `app.orchard.tv` 换成原值 `com.changhong.system.launcher.v2`。
- 守护（无障碍）和长虹自己的三个无障碍服务并存，都保留。
- 没有 `tv_input` 列表，信号源没验证；待机唤醒、音量、亮度没测。
