# 电视果 6（tvguo，Amlogic s4，Android 11）

一台爱奇艺电视果 6 投屏器，Aerial 1.72 `standard`，用
`cmd role add-role-holder --user 0 android.app.role.HOME app.orchard.tv` 设为默认 Home。

| 项 | 值 |
|---|---|
| `ro.product.model` | `tvguo` |
| `ro.product.brand` | `Droidlogic` |
| 系统 | Android 11（API 30），`RD2A.211001.002.444 dev-keys`，安全补丁 2021-10-01 |
| 板 / ABI | `s4` / `armeabi-v7a`（32 位用户空间） |
| 内存 / 分辨率 | 2 GB / 1920×1080 @ 240 dpi |
| 其他 | `ro.boot.verifiedbootstate=green`，无 root |

## 固件会强停上一个前台应用

新 Activity 来到前台时，system_server 的 `WindowManager` 会 `forceStopPackage` 掉**上一个前台应用**。
Aerial 在 6 毫秒内就被杀，进程都没了，所以 App 侧的守护拦不住。

```
18:52:50.507 START u0 {act=android.settings.SETTINGS cmp=com.android.settings/.homepage.SettingsHomepageActivity} from uid 10066
18:52:50.525 I/WindowManager: do forceStopPackage app.orchard.tv
18:52:50.525 D/WindowManager: AcName: com.android.settings/.homepage.SettingsHomepageActivity index:1

18:54:15.423 I/WindowManager: do forceStopPackage app.orchard.tv
18:54:15.423 D/WindowManager: AcName: com.android.systemui/.Somnambulator index:1        # 屏保启动

18:57:55.813 I/WindowManager: do forceStopPackage app.orchard.tv
18:57:55.813 D/WindowManager: AcName: com.tencent.start.tv/com.tencent.start.ui.EntryActivity index:1
```

紧接着 `ActivityManager: Force stopping app.orchard.tv … Killing …:app.orchard.tv (adj 0)`。
`AcName` 是新到前台的那个 Activity，被杀的是上一个前台应用。

**对照实验**：让腾讯视频（`com.tencent.start.tv`）在前台，再打开系统设置 —— 腾讯视频的进程同样消失，
日志里出现同样的 `do forceStopPackage`，只是包名换成了腾讯视频。⇒ 这是 2 GB 机器的内存回收策略，
对所有应用一视同仁，不是针对第三方 Launcher。

**已排除按包名写死**：`framework.jar`、`services.jar` 以及 `/system`、`/vendor` 下所有 `.jar` / `.apk`
都没有 `orchard` 字样（只有地址正则的误报）。

## 实际体验

- 开任何 App → Aerial 被回收（进程直接消失）；
- **按主页键 0.25 秒回 Aerial**（3 轮实测，每轮 Aerial 进程数都是 0）；
- 开机直达 Aerial；
- 空闲 30 秒以上不被打断。

这台上不需要守护再做别的处理：`INVISIBLE_LAUNCHERS` 那条用量统计轮询在这里跑不起来，
因为 Aerial 6 毫秒内就被杀，`foregroundCheck` 没有机会执行。

## 开机后 `adb_enabled` 被复位

每次开机 `Settings.Global.ADB_ENABLED` 都被复位成 `0`；`persist.adb.tcp.port` 出厂为空，
`service.adb.tcp.port` 又是运行时属性、重启即丢 ⇒ 重启后 5555 一定不通，必须去开发者选项手动打开。

两步修好：

```sh
adb shell setprop persist.adb.tcp.port 5555
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
```

第 2 步就是 `tools/install.sh` 里 `KEEP_ADB=1` 做的事。**手动 `adb install` 装 APK 会漏掉它**：
`ensureAdb` 因为 `keepAdb()==false` 直接 return，看起来像守护失效。

实测：重启后 65 秒 5555 自动可连，日志
`I/AerialGuard: ADB debugging had been switched off; turned it back on`。

**不是 `checkAdbd.sh`**：`/system/bin/checkAdbd.sh`（由 `vendor/etc/init/hw/init.amlogic.board.rc`
以 `oneshot` 启动）是 adbd 的 CPU 看门狗，每 10 秒 `top` 一次，adbd 占用 >60% 就 `kill -9`；
而且它跑在 `u:r:cons_channel:s0` 下被 SELinux 拦住（每 10 秒一条
`avc: denied … checkAdbd.sh … /system/bin/awk`），实际不生效，是日志噪音。

## 其他

- `pm disable-user --user 0 com.tvpdsg.launcher` 不持久：重启后被固件重新启用（`enabled=0`，
  即 `COMPONENT_ENABLED_STATE_DEFAULT`）。Aerial 是默认 Home，厂商桌面不会自启，所以影响不大。
- 应用数据在 `/data/user/0/app.orchard.tv`，`allowBackup=false`，**卸载即丢**（壁纸、上传的照片、
  图标排序、家长密码），没有 root 备份不了。换成自己签名的包只能卸载重装，升级前要先知道这一点。
