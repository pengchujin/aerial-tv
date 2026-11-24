# 海尔 / 统帅（YunOS、Huanglong 平台）

## 统帅 L65F8（LRH812_ALI7G_A，Huanglong，安卓 12，#1388，报告人实测）

- 原桌面是 `com.youku.taitan.tv`（酷喵，`/system/app/AliTvTitan`，system uid）。固件把主页键交给它；它不在时主页键什么都不做。守护靠「原桌面一出现就切回」接管，主页键先露酷喵约 2 秒。
- 遥控器 HaierTV-RC 的主页键是 KEY_HOMEPAGE（172），固件的键位表没有它（1.89 起守护当主页键处理，#1388 早先）。
- 设置：`com.haier.tv.menusetting/.RootActivity` 要 `com.huanglong.permission.COMMON_PERMISSION`（signature|privileged），第三方 App 启动会抛 SecurityException；`android.settings.SETTINGS` 解析到的系统设置页画不出来。1.95 之后选设置候选时跳过要权限的（Lite 同），这台的设置仍然打不开。
- logcat 三个缓冲区都是空的，只能看 Aerial 自己的诊断。

## 海尔 WATERFALL_T962（Amlogic gxbaby，YunOS 5.1 / 安卓 5.1，920 MB，#1491，报告人实测）

- 原桌面也是酷喵（HOME 解析到它）。从磁贴打开酷喵，守护放行；但固件反复杀 Aerial，复活的守护不知道刚才是有意打开的，约 1 秒后把酷喵拉回 Aerial。main 上（1.95 之后）有意打开原桌面记在磁盘上（本次开机），复活后接着放行，没在真机验证。
- 冷开机先停在 `com.haier.tv.menusetting/.RootActivity`，按主页键后守护接管。

## 海尔 75A70C（#1311）

TvInputManager 没有 HDMI。遥控器切到 HDMI 后前台是 `com.haier.tv.menusetting/.RootActivity`，由 `com.huanglong.tvsetting`（uid 1000）带 extras 打开；extras 的键名还不知道。当贝 DBF6（#1477）切 HDMI 也是 Huanglong 的 `com.huanglong.tvui/.MainActivity`。

## 海尔 Haier_T966_4Gen（YunOS 4.1.0-R-20180525，安卓 5.1，Amlogic，#1677，报告人 Agent 实测）

- `TvInputManager` 列出 `com.droidlogic.tvinput/.services.HdmiInputService/HW5`、`HW6`，但没有 App 接 passthrough 的 VIEW，磁贴提示「此电视不支持」。
- 海尔自己的信源菜单用 `com.haier.tv.menusetting/.RootActivity` 切源：int extra `isNewSource=1`、`sourceid=<HW 号>`（日志 `sourceid=6` → `.../HW6`）。这台的 RootActivity 导出、没有权限要求，第三方 App 能启动（报告人用一个本地小 App 实测 HDMI 1、2 都切过去）。
- 系统给的名字和机身口是反的：`HW5` 叫 HDMI1，实际是机身 HDMI 2；`HW6` 是机身 HDMI 1。名字用「信号源修正」改。
- main 上（未发版）：passthrough 打不开、RootActivity 能启动时，HDMI 磁贴走这条（`SystemState.haierRoute`）；统帅 L65F8 的 RootActivity 要签名权限，检测后不用。没有海尔真机，HDMI 3、AV 没验证。
