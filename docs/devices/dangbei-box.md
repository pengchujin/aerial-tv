# 当贝盒子 H3S / Max1（RK3566 / RK356x，安卓 11）

- 固件在按主页键时强停第三方 App（`Force stopping app.orchard.tv ... from pid 425`），守护随进程一起没了，
  靠通知监听复活后再拉回（#849 #1245 #1276）。1.85 起守护回来时会把 Aerial 拉回；1.86 起通知监听一连上就先打开 Aerial
  （`Aerial started before the guard binds`）。Max1 上报告人 1.86 测 23 轮：这条快路径命中 6 轮（3.1–3.3 秒），其余 11 秒左右，中位数和 1.85 一样（#1245）。
- **默认桌面白名单**（#1245，报告人在 DBBOX5C 上取证）：`PackageRuleProvider`（`content://dangbeipackagerule/package_rule`）里标 `rule=8` 的几个包
  （`com.qsp.superlauncher`、`tv.wan8.launcher`、`com.shafa.launcher`、`com.baidu.tv.bdlauncher`）也写在 `services.jar` 里，
  只有这些第三方桌面能出现在 HOME 候选里、能在「高级设置」里选成默认启动器（#1335）。Aerial 不在名单里，`set-home-activity` 返回 `Failed to set default home`。
- 有用户把包名改成 `com.dangbei.tvlauncher` 后能设成默认桌面。改包名进白名单和项目原则冲突（只装 Aerial 自己、官方包要能覆盖升级），不写进说明，也不支持。

## DBX5 Ultra（mt5879，安卓 11，#1525）

- 没有最近任务：SystemUI 没有（`mRecentsComponent=null`，`GLOBAL_ACTION_RECENTS`、`KEYCODE_APP_SWITCH` 都没反应），`com.dangbei.recentstask.action.SHOW_RECENTS_FOR_USER` 也没人接。1.97 打开过当贝桌面里的 `com.dangbei.leard.leradlauncher/.ui.secondary.childpattern.recent.ChildRecentActivity`，报告人复测它是「观看记录」影视海报墙，不是运行中的 App，所以 main 上去掉了（双击 / 长按主页键回到 1.96 的样子，什么都不开）。
- 第三方语音（夏杰 `com.peasun.aispeech/.AIMonitorService`）从 `enabled_accessibility_services` 里掉出：列表里 Aerial 是短格式 `app.orchard.tv/.guard.HomeGuardService`，这是安卓的无障碍服务自己写回的格式（Aerial 写的是完整类名）。安卓在 App 被强停时会把它的无障碍服务移出列表，当贝固件会定时强停后台 App（#195），所以是固件的问题，不是守护覆盖。Aerial 自己会把自己加回去，别人的服务它不动。

## 当贝超级盒子 B3（DBB3，galilei / Amlogic g12b，安卓 9，#1546 #1552）

- 固件在 system_server 里：按主页键时对全部后台 App 执行 `forceStop`，包括当前桌面 Aerial（报告人日志：`Force stopping app.orchard.tv ... from pid <system_server>`，和按键同一毫秒；一起被停的还有云视听小电视、搜狗输入法、当贝自己的几个服务）。所以从 App 里按主页键会先回 Aerial 再黑约 1 秒（Aerial 冷启动 `Displayed +907ms`），诊断里 `revivals in 10 min=3 or more`。停用当贝助手、改 `leradlauncher.launchermode.id` 都没用。
- 同一个钩子拦发给第三方 App 的静态广播（`renbo.gu: skip broadcast is: ComponentInfo{app.orchard.tv/app.orchard.tv.guard.ConfigReceiver}`），所以 `ConfigReceiver` 的所有开关（`device_profile`、`keep_adb` 等）在这台上都写不进去；直接写 `settings put` 是生效的。
- 报告人请求内置 `filterHomeAlways`（让守护在按键到达系统前接住主页键，系统就不走强停那条路）。没有实测，而且这个开关的代价（有的电视按住音量只走一格）一直是户主决定只按用户自己的配置打开，所以没有内置，交给户主决定。

## DBF6（tv_hi3751v811 / Huanglong，安卓 12，#1477 #1542 #1544）

- TvInputManager 没有信号源。切 HDMI 走 `com.huanglong.tvui/.MainActivity`（`/system/app/TvPlayerDemo`），int extra `SourceName`：9 = HDMI 1，10 = HDMI 2（报告人从 APK 读出，adb 切过两个口）。当贝的信号源选择页是同一个包的 `InputSourceChooserActivity`（action `com.dangbei.action.INPUTSOURCE_CHOOSER_ACTIVITY`），只有一个口有信号时自己切过去。main 上：有这个选择页的电视列出 HDMI 1 / 2（`dangbeiHuanglongSources`），选择器兜底也开它。没有真机验证。
- 应用内升级停在系统安装器的「已安装」页（Aerial 是默认桌面，安装器的完成广播被安卓 12 的后台限制拦下）：main 上升级后重新绑定的守护在 1.5 秒后把 Aerial 拉回一次。升级后 `enabled_notification_listeners` 里的 Aerial 被移掉，安卓 8 以后 App 自己补不回（要 `cmd notification allow_listener`）。
- 待机唤醒后画面上下倒置（#1542）：`/sys/class/projector/mirror` 被唤醒过程写成 0，`settings system projector_mode` 和 `persist.sys.mirror` 都没变，shell 写回 1 立刻恢复。原来由当贝自己的组件唤醒后调 `operateProjectorMode` 补回；换成 Aerial 当桌面后这条链断了。App 能不能写这个节点（SELinux）不知道，在等报告人的信息。

## DBF3 Air（MStar m7221，安卓 8.0，#1497）

- 关机流程里固件强停 Aerial，守护还绑着、`android.ui` 线程被 `AutoCancelOptionView` 堵了 1.8 秒时，system_server 在 `ViewRootImpl.sendAccessibilityEvent` 空指针崩溃，光机不亮、要拔电。强停前约 0.5 秒固件发 `com.dangbei.action.STR_SHUTDOWN`（non-protected，普通 App 能收）和 `com.dangs.workmode`（`mode=ScreenOff`）。1.98 起守护收到 `STR_SHUTDOWN` 就 `disableSelf()` 退出列表，亮屏或下次启动再加回。没有用 `com.dangs.workmode`：「关屏」不关机时也可能发它。
- 1.98 报告人复测仍崩（2026-11-15 时间线）：43.380 退出列表 → 43.784 强停 → 43.815 固件马上按 HOME 把 Aerial 拉起 → 44.283 新进程 `ensureEnabled` 把守护加回 → 45.117 熄屏 → 45.373 崩。也就是「下次启动加回」这一步就在关机途中。main 上（未发版）收到广播时把时间和 boot id 写进存储（`commit`），之后两分钟内、同一次开机里 `ensureEnabled` 不加回，等 SCREEN_ON（清掉记录再加回）；两分钟后没关机就自己加回。当贝的「关机」是 STR 待机，boot id 不变，所以要靠 SCREEN_ON 清记录。没有真机验证。
