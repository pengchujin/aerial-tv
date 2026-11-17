# TCL ak30a5（国行，雷鸟 55F295C 等，Amlogic t5，安卓 9）

> 和 [tcl-mt5879-cn.md](tcl-mt5879-cn.md)（MT5879，安卓 11）、[tcl-m7221-cn.md](tcl-m7221-cn.md)（m7221，安卓 8）是不同平台。

都是报告人实测（#1499 #1514 #1521 #1602），我们手上没有这台。

- 默认桌面 `com.tcl.cyberui/.MainActivity`，固件拒绝 `set-home-activity`，用 guard 版守护接管。
- **按住音量只走一格**：遥控器（`aml_keypad`，没有 `EV_REP`）按住只发一次按下、一次抬起；TCL 的连调是系统分发按键时合成的（`android/WindowManager`，0.5 秒后每 50 ms 一格）。守护在安卓 9 上一直开着按键过滤（接住主页键要靠它），有过滤器时安卓不合成连发；音量加减键根本不进守护（5 天日志里 0 条，静音键有），守护也补不了。关掉守护立刻能连调。只能在 `neverFilterKeys`（能连调，从 App 按主页键先停在 TCL 桌面约 5 秒）和默认之间取舍。
- 信号源界面（`com.tcl.tv/.TVActivity`，独立任务）按返回落到 TCL 桌面，守护拉回 Aerial 时正赶上安卓 9 的 5 秒切换保护；`am start` 原桌面不经过这条路就没有 5 秒。
- 每次重启后固件关掉 ADB（5555 拒绝连接），要在 设置 → 关于 → 本机信息 里按 上下左右 → 更多 → ADB 重开；待机后电视从网络上消失。
- 981 MB 内存、`ro.config.low_ram=true` 的机器上，几天不重启后整机换页卡死（swap 用到 300 MB，系统反复杀进程）；航拍壁纸在播时 Aerial 多占约 49 MB（PSS 266 MB 对静态壁纸 217 MB），取不到帧（`wallpaper key frame decoded: false`）。报告人提议低内存且取不到帧时不放影片，转户主决定，没改。
