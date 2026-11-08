# 乐视超4 X55（f608，Android 6 eUI）

- 型号 X4-55（board `maserati`，MStar），Android 6.0 / SDK 23，EUI，build
  `V2401RCN02C080080B04121S`。
- 2026-11-07 实测，Aerial 1.86。同代的超3 X65 见 [letv-x3-65.md](letv-x3-65.md)。

## 安装与授权

- 这台**只测过改包名的版本**：`applicationId` 改成 `com.stv.tvhome`（Java namespace 仍是
  `app.orchard.tv`），官方包没有在这台上装过。改包名是为了进 EUI 的白名单，**不推荐**：
  它是另一个包、另一把钥匙签的，官方 Release 不能覆盖升级，换官方包得先
  `pm uninstall com.stv.tvhome`，会丢壁纸、照片和排序；这和项目「只装 Aerial 自己、官方包
  能直接覆盖升级」的原则也冲突。
- 本节的实测结果都来自这个改包名版。guard 版不声明 HOME，理论上不会遇到「不允许安装桌面类
  App」，但没有在官方包上验证过。网络 ADB 已开（`persist.adb.tcp.port=5555`）。这台
  **待机后整个网络会断**，连不上时先用遥控器唤醒并保持亮屏。
- 安装后需要这几项授权（`tools/install.sh` 也是做这些）：
  - `enabled_accessibility_services=app.orchard.tv/app.orchard.tv.guard.HomeGuardService`
  - `accessibility_enabled=1`
  - `enabled_notification_listeners=app.orchard.tv/app.orchard.tv.guard.RevivalListener`
  - `WRITE_SECURE_SETTINGS`、`GET_USAGE_STATS` 等 appops 也由脚本加上。
- 改包名版重启后约 30–45 秒（含开机广告）自动进 Aerial，无障碍仍绑定。

## 主页键与守护

- 主页键由守护接管：约 1 秒回 Aerial；先闪 `com.stv.appdaemon/.ui.DaemonActivity` 约 0.5 秒。

## 后台清理与返回键

- 切到别的应用约 3–4 秒后，EUI 的 `ActivityManager-Killer` 会强停 Aerial：
  `force stop third-part pkg when switched background: com.stv.tvhome`。通知监听随后把它复活，
  通常 0.3–1 秒；复活后守护约 5 秒绑定。
- 从 App 按返回（例如网易爆米花的退出确认）时，EUI 在退出时**再强停一次**，连任务一起删掉，
  所以返回先落在 `com.stv.deskplatform`，等通知监听复活后 Aerial 才回来；这次实测从退出到
  Aerial 显示 **4.2 秒**（强停 → 复活 3 秒 → `Displayed ... +1s478ms`）。
- 不要尝试「复活后在后台重建任务」：`moveTaskToBack` 本身就算一次「切到后台」，EUI 约 1.5 秒内
  再强停，形成重建→强停的死循环（实测连续 5 轮以上）。任务去留完全由固件决定，App 侧无解。
- 后台白名单在 system_server 的 `WhiteListUtil`（`mAvoidKillList` / `mKeep3rdAppList` 等），
  由内部 `ams_binder` 喂数据；电视管家（StvHelper）只有自启动、权限、清理，没有后台白名单入口。
  `/system` 只读，`pm disable-user` 对 uid 1000 的包也会被拒，ADB 改不了。
- 同步复活在这台上不可用：日志
  `could not set up the sync revival ... uid 10023 cannot explicitly add accounts of type:
  com.stv.tvhome`；复活只靠通知监听。

## 开机广告（两层）

- Android 层（数据下发的那部分）已被挡住：bootadmanager 等广告包处于 `hidden=true`，
  日志里桌面拉它是 `Unable to start service ... STARTED_BY_DESKTOP` /
  `GET_BOOTAD_PLAYINFO ... not found`。这是本机的额外配置，不是 Aerial 做的。
- 剩下的 15 秒乐视品牌视频是**固件原生**的：`/applications/bin/tvos` 里的
  `MSrv_Advert_Player`（日志 `tvos: boot-process:AdvertPlayerFinished no bootanimation`、
  `TvService: boot.video.finish`），播 `/tvconfig/video.ts`，开关是
  `/tvconfig/config/Flags` 的 `ADVERT_BOOTING_ENABLE=1`。冷启动和待机唤醒都会播（本机实测）。
- 无 root 移除不了，排查过的地方：
  - `/tvconfig` 是只读 ext4（`mmcblk0p17`），`video.ts` 和 Flags 都改不了。
  - bootadmanager 的跳过配置 `/fastplay/config_skip.conf`（`/fastplay -> /data/fastplay`，
    770 system:system）只有 `com.stv.commonservice` 的签名推送能写。
  - `/tvdatabase` 可写（文件 0666），但 `tbl_SystemSetting` 里只有 `adVideoOnly` 等字段，
    没有开机广告开关。
  - 设置里能找到的只有「开机视频音」「快速开机」这类选项，没有关闭开机视频/广告的开关。
  - `adb root` 不可用（user build，`ro.debuggable=0`，没有 su）。

## 调试时容易踩的坑

- 待机断网：`adb connect` 超时通常不是 ADB 关了，是电视待机了，先唤醒。
- 固件没有 `cmd` 命令（`cmd: not found`），`cmd package` / `cmd notification` 都用不了；
  用 `pm`、`settings`、`dumpsys`。
- `pm disable-user` 对 uid 1000 的系统包报
  `SecurityException: ... package uid=1000`，`pm hide` 也不拦系统侧的显式启动。
- 这台没有 root：`su` 不存在，`adb root` 报 `adbd cannot run as root in production builds`。

## 还原

本机装的是改包名版（`com.stv.tvhome`），卸载时按这个包名来；官方包对应 `app.orchard.tv`。

    adb shell pm uninstall com.stv.tvhome
    adb shell settings put secure enabled_accessibility_services ''
    adb shell settings put secure enabled_notification_listeners ''
    adb shell settings put secure accessibility_enabled 0

本机额外隐藏的广告包与 Aerial 无关，不在这份还原步骤里。
