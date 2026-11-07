# 当贝盒子 H3S / Max1（RK3566 / RK356x，安卓 11）

- 固件在按主页键时强停第三方 App（`Force stopping app.orchard.tv ... from pid 425`），守护随进程一起没了，
  靠通知监听复活后再拉回（#849 #1245 #1276）。1.85 起守护回来时会把 Aerial 拉回；1.86 起通知监听一连上就先打开 Aerial
  （`Aerial started before the guard binds`）。Max1 上报告人 1.86 测 23 轮：这条快路径命中 6 轮（3.1–3.3 秒），其余 11 秒左右，中位数和 1.85 一样（#1245）。
- **默认桌面白名单**（#1245，报告人在 DBBOX5C 上取证）：`PackageRuleProvider`（`content://dangbeipackagerule/package_rule`）里标 `rule=8` 的几个包
  （`com.qsp.superlauncher`、`tv.wan8.launcher`、`com.shafa.launcher`、`com.baidu.tv.bdlauncher`）也写在 `services.jar` 里，
  只有这些第三方桌面能出现在 HOME 候选里、能在「高级设置」里选成默认启动器（#1335）。Aerial 不在名单里，`set-home-activity` 返回 `Failed to set default home`。
- 有用户把包名改成 `com.dangbei.tvlauncher` 后能设成默认桌面。改包名进白名单和项目原则冲突（只装 Aerial 自己、官方包要能覆盖升级），不写进说明，也不支持。
