# OPPO K9 65 寸（ColorOS TV 2.4，`OPPOCNM652`）

一台 65 寸 OPPO K9：ColorOS TV 2.4（基于 Android 9），MediaTek m7642，2 GB 内存，只能装
32 位应用（`PPR2.180905.006.A1`，固件 2023-01-12）。下面写的都是这台机器上实测的结果，
不是从别的 OPPO 机型推断的。

- 原桌面：`com.heytap.tv.launcher`（这台已禁用，系统的兜底是 FallbackHome）。
- 现在用的桌面：Aerial 的 `standard` 版，改成 `com.oneplus.tv.launcher2` 后安装。
- 实测版本：**1.47 起**（1.48 是 versionCode 56）。

## 先打开 ADB

1. 设置 → 系统 → 关于本机 → **设备型号**，在遥控器上按 上、上、右、右、确定。
   页面会提示「还需执行 N 步」，按对了才有开发者选项。
2. 设置 → 系统 → **开发者选项** → 打开「USB 调试」。
3. 电脑上执行 `adb connect <电视IP>:5555`。

- 不用配对码，也没有「无线调试」（Android 9 没有）；开一次就一直是开的。
- **待机（熄屏）时电视会断网，ADB 跟着断**，唤醒后重新 connect 一次。
  如果重启后 `adb connect` 报 `No route to host`，但网络是通的，先 `adb kill-server` 再连。

## 为什么不能直接装

OPPO 只允许三个固定包名的应用声明「我是桌面」。别的包名只要带了 HOME，安装就会被拒绝：

```
INSTALL_FAILED_DUPLICATE_PERMISSION: App installation is banned, because of launcher categories
```

这句报错既不是权限冲突，也不是签名问题。能用的包名只有
`com.heytap.tv.launcher`、`com.oneplus.tv.launcher2`、`com.oppo.tv.launcher2`。

所以要把 Aerial 的 `standard` 版改成其中一个包名再签名安装。`guard` 版不声明桌面，
可以用原包名直接装（实测），但它接管桌面的效果没在这台机器上验证过。

### 改包名时，另外两处内部名字也要改

用源码编译时，改 `applicationId` 就够了；如果是用 apktool 拆开改 `AndroidManifest.xml`，
下面两处要手动一起改，否则会出问题：

1. **广播权限** `<新包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。
   不改的话，一打开就闪退，报 `Permission … is required`。
2. **ContentProvider** `app.orchard.tv.androidx-startup`（AndroidX 启动组件）。
   不改的话，官方包和 `guard` 版会装不进来，报
   `INSTALL_FAILED_CONFLICTING_PROVIDER … is already used by …`。

这两处都是打包时按 `applicationId` 生成的，所以源码改包名不用管。

## 不要停用原桌面（#862）

另一台 K9（`OPPOCNM632`，ColorOS TV 2.4 / Android 9，m7642，2026-10-29 报告人实测）**原桌面 `com.heytap.tv.launcher` 保持启用**也能开机直进 Aerial：改包名、在 HOME 里加下面的 `MODERN_HOME` 标记，然后
`cmd package set-home-activity com.oneplus.tv.launcher2/app.orchard.tv.MainActivity`，`resolve-activity` 显示 `isDefault=true`。

- 停用原桌面后，如果第三方桌面没被系统认可，系统只剩 FallbackHome，会一直停在开机动画（系统其实已经起来了，`adb` 还能连）。所以先 `cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME -c com.heytap.tv.launcher.category.MODERN_HOME` 确认 Aerial 在里面，再设默认桌面；原桌面一直留着当兜底。
- 想退回原桌面：`adb shell cmd package set-home-activity com.heytap.tv.launcher/com.oneplus.tv.launcher2.HomeLauncher`（`...modern.ModernLauncher` 不能当桌面）。
- 用 apktool 改包名时漏了下面说的广播权限，守护每 30 秒崩一次，默认桌面过 40–50 秒被系统清掉、弹出「选择桌面」，表现是电视卡、遥控器没反应。

## 开机要认的桌面标记

OPPO 开机时只认带 `com.heytap.tv.launcher.category.MODERN_HOME` 的桌面。没这个标记的
第三方桌面，系统会一直等「该起来的桌面」，电视卡在开机动画里，不会自己进桌面。

把它加进 HOME 的 intent-filter 就好。加好之后，1.47 起实测：进程启动到第一帧 0.7–0.8 秒，
第一帧和开机动画结束几乎同时（相差 0.06–0.4 秒）；开机约 16 秒出第一帧，约 19 秒桌面可用。

（这是正常冷启动的数。刚升级完的第一次会慢一些，系统要重新编译、重画磁贴。）

## 装好之后

需要授权这几项，否则守护、开机拉起和亮度都不工作：

```bash
adb shell pm grant com.oneplus.tv.launcher2 android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.oneplus.tv.launcher2 SYSTEM_ALERT_WINDOW allow
adb shell appops set com.oneplus.tv.launcher2 WRITE_SETTINGS allow
adb shell cmd notification allow_listener com.oneplus.tv.launcher2/app.orchard.tv.guard.RevivalListener
adb shell cmd package set-home-activity com.oneplus.tv.launcher2/app.orchard.tv.MainActivity
```

无障碍里的「Aerial 桌面守护」由 Aerial 自己打开（依赖上面的 `WRITE_SECURE_SETTINGS`）。
如果没开，去 设置 → 辅助功能 里手动打开。

确认装对了：

```bash
adb shell dumpsys package com.oneplus.tv.launcher2 | grep -E 'versionName|lastUpdateTime'
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

第二行应该返回 `com.oneplus.tv.launcher2/app.orchard.tv.MainActivity`。

## 开机时的两个老问题（1.47 起已修）

### 解锁前崩溃，HOME 被重启两次

1.46 及以前，Home Guard 在用户解锁前就绑定了，它读设置时碰到还不能读的凭据加密存储，
整个进程崩掉。Android 把 HOME 重启，再崩一次，第三次才起来。表现是开机时黑屏闪两下。

上游在 #481 里修了：解锁前不读这部分设置，等解锁后再补上。1.47 起冷启动都是
0 次崩溃、Aerial 只启动一次。

### 解锁时黑屏 2–3 秒

以前 MainActivity 在解锁时会 `recreate()`：旧窗口销毁，新窗口 1.5 秒后才建出来，
中间那 2–3 秒没有任何画面。上游在 #469 里改成在同一个窗口里继续启动。1.47 起冷启动都没有黑帧，肉眼也看不到闪；
解锁前后是同一扇窗口。

## 实测结果

除注明外都是 1.47 起在这台上实测的。

| 项目 | 结果 |
|---|---|
| 安装 | 成功；同签名覆盖安装，壁纸、照片、图标排序都保留 |
| 授权 | `WRITE_SECURE_SETTINGS`、通知监听、无障碍、悬浮窗都正常 |
| 开机 | 自动进 Aerial，不卡开机动画；1.47 起冷启动 0 崩溃、1 次进程启动、无黑屏 |
| 待机唤醒 | 回到 Aerial；熄屏几分钟后的长待机没测（待机断网，ADB 跟不上） |
| 按 HOME | 直接回 Aerial；原桌面已禁用，不会先闪原桌面 |
| 被强制停止后 | 约 0.5 秒被通知监听拉回来，8 秒回到前台，15 秒左右守护重新连上（1.11 前后实测） |
| 航拍屏保 | 正常；Apple 的影片走国内 CDN，换片不黑屏 |
| 天气 | 正常 |
| 手机配置页 | 正常打开（扫码） |

## 升级

和第一次安装一样，换成覆盖安装：

```bash
adb install -r -g Aerial-<版本>-oppo.apk
```

升级要保留原来的签名，否则装不上；壁纸、照片和排序会保留。装完按「装好之后」里的命令
确认一次。1.46 及以前的版本在开机时有上面两个问题，升到 1.47 及以后就不需要再打补丁。

## 已知问题

- **升级一版后，桌面守护偶尔不会自己回来。** 按 HOME 或开机时不再自动拉起 Aerial，
  手动打开一次就恢复。最简单的修法是重启电视；不想重启就执行
  `adb shell am force-stop com.oneplus.tv.launcher2`，通知监听会把它拉回来，守护也会重新连上。
- 装第二个「桌面」应用会清掉默认桌面的选择。装完把默认桌面指回 Aerial：
  `adb shell cmd package set-home-activity com.oneplus.tv.launcher2/app.orchard.tv.MainActivity`。
  这台机器的设置里也有「设置主屏幕」一项，可以当开关用。

## 「原桌面」误开工厂模式（#1028，OPPOCNM632，m7332）

CVTE 代工固件里的工厂测试 `com.cvte.factory.at/.job.StartActivity` 声明了 `android.intent.category.CVTE_AT_HOME`，守护按「以 `_HOME` 结尾的类别是厂商桌面」把它算进原桌面，点「原桌面」磁贴就进了工厂模式（左上角黄底 K，`persist.sys.konka_mode` 等置 1，报告人用固件的退出接口关掉）。1.75 之后排除 `_AT_HOME`。报告人用的是改了包名的构建，没在官方包上复现。
