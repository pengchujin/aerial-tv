# 腾讯极光盒子

来自用户反馈，维护者手上没有。

| 机型 | 系统 / 平台 | 结果 | 来源 |
|---|---|---|---|
| 极光盒子 6 / Q5043（创维数字代工，cupid-p2，全志） | Android 10 | 开机直达和主页键都正常（守护接管，主页键后约 4 秒回 Aerial）。任何 `settings` 子命令都会让 adbd 卡住 120 秒，`pm`、`appops`、`dumpsys` 正常；Aerial 拿到 `WRITE_SECURE_SETTINGS` 后自己打开了守护，所以不需要手写设置。HOME 候选只认平台签名的桌面（极光、小湃），Aerial 进不了候选，`role add-role-holder` 不报错但无效 | #367 |
| 极光盒子 6SE、7S（BA101，Amlogic s7d） | Android 14 | 原桌面 `com.ktcp.launcher`，真正显示的是 `LauncherHomeSingleTaskActivity`（1.35 起守护认得它）。7S 上 1.36 仍然开机停在原桌面，原因还没查到，需要开机后的 `AerialGuard` 日志 | #269、#270 |

## 极光盒子 4 Pro（A4111，小湃科技，Amlogic SC2，Android 11，#928 #930）

报告人 Agent 实测（1.69 / 1.70）：原桌面 `com.ktcp.launcher`（平台签名，uid 1000），来到前台时**不发任何窗口事件**：开机约 6 秒静默盖住 Aerial、睡眠时固件自己拉起它、按主页键后 1.5 秒内守护零事件，只能靠用量统计。按键过滤开着时主页键仍由系统（uid 0）处理。无人干预时重启和待机唤醒都一直停在原桌面。

1.71 起：原桌面有 `com.ktcp.launcher` 时守护每秒查用量统计（同海信聚好看）；唤醒后的接管也查「Aerial 被原桌面静默盖住」。没有真机验证。机身插北通手柄接收器时守护在别的 App 里不过滤按键（#774 的设计），与本问题无关。
