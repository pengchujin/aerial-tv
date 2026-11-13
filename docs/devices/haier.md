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
