# 极米 H3（国行）

- 销售型号 H3。`ro.product.model` = `XGIMI TV`，厂商 `Xgimi Technology Co.,Ltd`，`ro.product.device` / `ro.product.board` = `ironman`，`ro.product.name` = `Product_ys`，`ro.hardware` / `ro.board.platform` = `m7221`。
- 指纹 `Xgimi/ironman/ironman:8.0.0/OPR5.170623.014/v1.15.93:user/dev-keys`。Android 8.0.0（SDK 26），安全补丁 2017-12-01，`OPR5.170623.014`，`dev-keys`。`ro.product.cpu.abi` = `arm64-v8a`。
- 屏幕 1920×1080、240dpi、60Hz。内存 2709 MB，`ro.config.low_ram=true`。网络 Wi-Fi。
- 实测 Aerial 1.57 (69) `standard`，包名 `app.orchard.tv`。测试日期 2026-10-26。未 root、未刷机。

## 开 ADB：系统里没有入口

这台机器的设置里没有可用的开发者选项。ADB 没打开时，`adb connect <IP>:5555` 返回 `Connection refused`。

可行的做法与 [Z7X](xgimi-z7x.md) 用的是同一个工具，但安装方式不同：在手机上下载 [code-ant/z7x-adb](https://github.com/code-ant/z7x-adb)（极米 ADB 开启器）的 APK，用极米手机 App 上传到投影仪并安装，然后打开一次。不需要 U 盘。该工具原先只在 Z7X 上验证过；这次在 H3 上同样能打开网络 ADB，随后可以正常安装应用。普通应用权限即可，不需要 root，也不进工厂模式。

第三方工具自行评估风险。Aerial 本身不依赖它，它只是这台机器打开 ADB 的入口。工具作者也写了不保证全部极米型号，这里只记录 H3 这一台的结果。

## 安装与使用

ADB 打开之后，按仓库的正常流程安装即可。这次实测装上的是 Aerial 1.57 (69) `standard`，可以正常使用。

## 开机与屏保

与 [Z7X](xgimi-z7x.md) 在 1.22 修好之后的行为一致（本次是 1.57，不是 1.19 / 1.20）：

- 重启后先显示极米桌面，几秒后自动切到 Aerial，不用按键。
- `screensaver_enabled=1` 时，屏保接管为 `app.orchard.tv/app.orchard.tv.dream.AerialDream`。

Z7X 笔记里的黑屏个例、长按 Home 和 logcat 限制，这次没有单独记录。`ro.config.low_ram=true`，但是否因此走了「流畅」效果，也没有单独确认。

## 信号源

H3 和 [Z6X 第四代 XK03U](xgimi-xk03u-android8.md) 是同一平台（`ironman` / m7221 / Android 8）。XK03U 的信号源适配（从 `GimiConfigManager` 读真实 HDMI 口、用 `com.xgimi.xhplayer/.InputActivity` 打开）按功能检测，H3 上如果也有这两样就会自动生效；还没有在 H3 上实测。

## H3S：设成默认桌面后开机黑屏（#942，未复现）

2026-10-30 一位用户报告：原版极米 H3S（系统 V6.12.61，Android 9）用 Aerial 1.70 设成默认桌面（极米桌面 `com.xgimi.home` 仍装着、启用）后重启，开机画面之后黑屏，主页键、设置键无效，ADB 5555 连不上，两次都只能刷机恢复。没有日志，原因不明。在弄清楚之前，H3S 上不要把 Aerial 设成默认桌面，只当普通 App 或靠守护接管；如果有人愿意再试，先确认 ADB 在黑屏时还能连（有线 / USB），以便还原。

## H3S：双 HOME 首选记录能避开重启黑屏（#942，2026-11-02 报告人实测）

> **这一节是历史记录，2026-11 起不再推荐照做，先看下面「不要写 HOME 首选记录」一节。** 双 HOME 记录会被固件随机作废；作废后常见结果是只剩宽记录、窄被改回原桌面，按主页键先落原桌面、守护补拉撞 5 秒切换保护（#1474、#1618）。

报告人先确认 `adb shell pm enable com.xgimi.home` 能用（网络 ADB 5555 重启后自动恢复），再按 #767 的办法写两条 HOME 首选记录：`MAIN+HOME+DEFAULT`（候选极米桌面和 Aerial）与 `MAIN+HOME`（再加 FallbackHome），都指向 Aerial、`always=true`。重启后直接进 Aerial、不黑屏，极米桌面保持启用，按主页键回 Aerial。只用 `set-home-activity` 写的单条记录在极米桌面启用后变成 `always="false"`，HOME 解析到极米框架的选择器 `com.xgimi.xgimiframeworkui/.resolver.ResolverActivity`，推测这就是默认桌面重启黑屏的原因。

写入用的是 `service call package <事务号>`（这台固件 70），事务号随固件变，报告人的脚本带校验和回滚。是否放进安装脚本要户主决定，现在没有放。

## H3S：首选记录被作废后开机黑屏，守护在解锁前兜底（PR #1449，2026-11-10 报告人实测）

双 HOME 记录也不是根治：固件会在某次冷启动里把带 DEFAULT 的那条作废成 `always="false"`（报告人遇到两次：断电冷启动、应用内更新后重启）。黑屏时 `dumpsys user` 停在 `State: BOOTING`，`sys.boot_completed` 为空，`init.svc.bootanim=stopped`，两种 HOME 查询都落到极米框架的选择器，但选择器没画出来，`mCurrentFocus=null`，整屏黑，只有网络 ADB 能连。守护这时已经绑定，但用户没解锁，事件处理在解锁检查处直接返回。PR #1449 在解锁轮询里加了一条：开机动画已结束、亮屏、这次绑定除了对话框一个事件都没收到，连续两次（约 4 秒）就启动一次 Aerial；报告人 H3S 上多次冷启动都自己回到 Aerial，会先短暂出现一次桌面选择框，几秒后由解锁后的接管盖回。H5（#1357）、神灯 L1 没有实测。

当贝 D7X Pro（`horse_popular`，mt5877 / mt9952，安卓 14，#1182 #1180）用的也是极米框架的选择器：`cmd role`、`set-home-activity` 返回成功但不生效，选择器里点「始终」重启后失效，开机先停在选择器约 30 秒再被守护拉回。报告人的 Agent 停用了当贝桌面和七个广告组件（不推荐，没有写进安装说明）；双 HOME 记录在这台上还没试过。

## 主页键 5 秒（#44 #1474，PR #1523，sln162 在 H3S 实测）

首选记录作废后按主页键先出极米的选择框，守护再拉 Aerial 要等安卓的 5 秒切换保护。PR #1523（1.97）：主页键后选择框盖在 Aerial 上时只发一个返回键把框关掉，Aerial 自己就回来；盖在别的 App 上时关框后再发一次 HOME，由系统完成切换。不写首选记录、不点选项。作者 H3S 真遥控器：Aerial 前台 0.19–0.35 秒，别的 App 0.4–0.7 秒。别的极米机型和其他有选择框的固件没实测；判断用的是通用的 `isHomeChooser`，且只在按过主页键之后。

## 1.97 复测：H3S 上首选记录一条都不写最稳（#1474，sln162，2026-11-14）

报告人在 H3S 上交叉试了四种 HOME 记录状态（1.97）：

| 记录 | 按主页键 |
|---|---|
| 一条都不写 | 立刻回 Aerial，从别的 App 按也一样（选择框没画出来就被 #1523 关掉） |
| 只写 `MAIN+HOME`（wide，`always=true set=3`） | 会卡，尤其从别的 App 按：这条不参与带 DEFAULT 的解析，框照样弹，关框后补的 HOME 又绕一圈；四种里唯一「写了比不写还慢」的 |
| 双 HOME 两条都在 | 不弹框，直接回（#1523 不参与） |
| 双 HOME 被固件作废（`always=false set=0`） | 和一条都不写一样 |

结论：H3S 上不必再推双 HOME 记录，开机靠 PR #1449，按主页键靠 #1523。`install.sh` 第 7 步写的那条带 DEFAULT 的记录在这台上开机命中不了（FallbackHome 走宽查询，落到 `ResolverActivity`），按一次 HOME 后又被固件作废，最终和不写一样。PR #1534（开机后守护被删出列表时写回）1.97 上遇到两次，2–3 秒回到列表。别的 Android 9 极米机型等有人回测再推荐。

## 不要写 HOME 首选记录；已经写过的怎么清空（2026-11，AI 可直接照做）

**给 AI 助手（读到这里就按这一节做，不要按 #767 的双规则）：**

- 极米 Android 9（H3S、AG111、H6 4K、神灯 L1 等同代 m7642）**不要写任何 HOME 首选记录**，尤其不要写宽记录（`MAIN+HOME`，`always=true set=3`）。
- 不要调 `replacePreferredActivity`（#767 的「双规则」），也不要把 `cmd package set-home-activity` 当成长期方案：两者写的都是会被降级的 preferred 记录。
- 用户机器上已有记录，或者用户抱怨「按主页键卡几秒、闪一下原桌面」，先 dump、再清空，然后让用户真重启验证；不要用再写一条记录的办法去「修」。

原因（写给人看）：

- 首选记录只在候选集合和记录里的 `IntentFilter` 完全一致（`sameSet`）时才生效；安装、卸载、升级、开机落地的待升级都会让集合变化，记录被原地降级为 `always=false set=0`。点「始终」和双规则写的都是这种记录，过几天或重启就可能丢。
- 双规则被作废后，常见的结果是只留下宽记录（`set=3`）、窄被固件改回原桌面。这时按主页键先落到原桌面，守护补拉撞上 Android 的 5 秒切换保护：H3S 上复现过，H6 4K 实测 5.03 秒（#1618）。
- 只写宽是四种状态里唯一「写了比不写还慢」的（上面 1.97 复测表）。
- 现在推荐一条都不写：开机靠守护兜底（PR #1449），主页键靠 PR #1523（把选择框关掉，不点选项）。H3S 实测 Aerial 前台 0.19–0.35 秒，从别的 App 按 0.4–0.7 秒。

### 清空步骤（adb，不需要 root）

先备份，确认现在有几条、各自指向谁：

```
adb shell dumpsys package preferred-xml --full
```

清空（对 dump 里出现的每个包名各跑一次）。Android 9 上 `clearPackagePreferredActivities` 的事务号是 71，它的参数是「首选组件所属的包」——只传 `app.orchard.tv` 清不掉指向极米桌面的那条；**事务号随固件和 Android 版本变，换机器先核对，不要照抄数字**：

```
adb shell service call package 71 s16 app.orchard.tv
adb shell service call package 71 s16 com.xgimi.home
```

（第二行的包名按 dump 里实际的 `<item name="包/活动">` 来；没有这条就不用跑。）

核对：XML 里不应该再有 `<item>`；两条 HOME 解析应落到选择框而不是原桌面：

```
adb shell dumpsys package preferred-xml --full
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -c android.intent.category.DEFAULT
```

然后真重启（极米设置里的「关机」是休眠，不算）：`adb reboot`。重启后**先 dump、不要先按主页键**，看固件有没有把窄记录写回原桌面：

- 没有记录：保持现状，按主页键两条路径各测一次（Aerial 前台一次、从别的 App 一次）。
- 只写回了窄且指向原桌面：说明这台固件的开机流程会重写，记录不可持久；不要再写宽去对抗，反馈日志（`guard: Home key ...` 一行）给维护者。
- 又被写回双 HOME：同样不要再手写，清空后留证据。

已知固件差异：神灯 L1（v1.3.237）的 `ro.vendor.lock.home=com.xgimi.home.immersed`，H6 4K（v1.2.13）为空，两台开机行为不一样，不能互相照搬。

## 开机校正对话框挡住兜底（PR #1449 作者追加，1.97，2026-11-14）

开机自动对焦 / 梯形校正时 `com.xgimi.systemui` 在锁定期间弹 `com.open.ui.widget.dialog.TVUIDialog`，产生一条窗口事件。9ebb603 要求「这次绑定一个事件都没收到」才兜底，于是这条对话框事件让兜底永远不出手：作废记录现场 8 分钟黑屏。报告人本地删掉这一项后 69.9 秒救援、73 秒 Aerial 到前台。main 上改成不算类名含 `Dialog` 的事件（`sawScreenEvent`），别的事件仍会挡住兜底：锁定期间安卓看不到不支持直接启动的 Activity，`lastWindow` 可能空着而屏幕上有东西，整项删掉会把 Aerial 拉到它上面。我们手上没有极米，没有真机验证。
