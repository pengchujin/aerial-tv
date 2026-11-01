# Aerial 机型适配（给 AI Agent）

Aerial（开源电视桌面，https://github.com/pengchujin/aerial-tv ）已经装在用户的电视上，但有地方不对：按主页键回不到 Aerial、待机或开机后停在原桌面、信号源打开的口不对、亮度条没反应等。你的任务是在用户的电视上**测出哪里不对、能改的当场改好、改不了的把证据整理好**，最后交给用户提交给作者。作者手上只有两台电视，其他品牌都靠你整理的结果。

还没装好 Aerial 时，先按 https://aerial.qust.me/agent.md 安装，并完成那里的「验收」。

## 规则

1. **亲自在用户电脑上运行命令**，每一步都看真实输出，不要假装成功。不能执行命令的聊天版 Agent，请用户换成电脑版（WorkBuddy、豆包电脑版、千问电脑版、Trae、Claude Code、Codex 等）。
2. **不要卸载、停用任何系统应用**（包括原来的桌面），不要删除电视上的文件，不要恢复出厂设置，不要改下面没有列出的系统设置。
3. **不要安装 Aerial 以外的任何 App**（包括应用商店、ADB 开启器、各种工具）。需要重装或升级 Aerial 时，只用下面的 `install.sh` 或 aerial.qust.me 上的官方 APK。
4. 待机测试、重启（`STANDBY=1`、`REBOOT=1`、`adb reboot`）前先征得用户同意。
5. 每试一个开关，都记下试之前和试之后的结果；没有帮助的开关要改回去。
6. 需要用户看电视或按遥控器时，明确告诉用户看什么、按哪个键。
7. 提交到 GitHub 前，先把要提交的全文给用户看，用户同意后再提交。

## 第 1 步：连接电视

```
adb connect <IP>:5555
adb devices
```

后面是 `device` 才算连上。连不上、显示 `unauthorized` 时，按 https://aerial.qust.me/agent.md 的第 1、2 步处理。

## 第 2 步：收集信息并自检

macOS / Linux（Windows 在 Git Bash 或 WSL 里运行）：

```bash
curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<IP> bash
curl -fsSL https://aerial.qust.me/tools/selftest.sh | TV=<IP> bash
```

- `report.sh` 只读，生成 `aerial-report-<机型>.md`：机型、固件、桌面和守护的状态、信号源、日志。
- `selftest.sh` 会按主页键、打开电视设置，再看 Aerial 能不能回来，生成 `aerial-selftest-<机型>.md`。每一项是 `PASS`、`FAIL` 或 `SKIP`。征得用户同意后，可以加上这些选项再跑一次：
  - `STANDBY=1`：待机 70 秒再唤醒，看唤醒后是不是 Aerial。小米待机时会关掉 ADB，脚本会请用户用遥控器开机。
  - `REBOOT=1`：重启电视，记录开机后一分钟内每秒在前台的是什么。
  - `INPUTS=1`：按磁贴的方式逐个打开 Aerial 列出的信号源，每个都截图。HDMI 画面在很多电视上截出来是黑的，要以用户看到的为准。

没有 bash 的 Windows，可以逐条运行下面第 3 步里的命令代替自检。

## 第 3 步：按失败项处理

所有开关都通过 Aerial 的 ADB 接口设置（只有 ADB shell 能发，电视上的其他 App 发不了）：

```
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver <参数>
```

下文把它简写为 `CONFIG <参数>`。

### 信号源不对（打开的口不对、列出不存在的口、名字不对）

这类问题可以当场改好，不用等作者发版。Aerial 把每台电视的修正存成一小段 JSON（device profile），以信号源的硬件编号（`.../HW5` 就是 5）为键：

| 字段 | 作用 |
|---|---|
| `"hidden": [8]` | 不显示 HW8（这台电视没有这个口） |
| `"labels": {"6": "HDMI 1"}` | HW6 显示为 HDMI 1 |
| `"xiaomiSources": {"6": 23}` | 小米电视：HW6 用播放器的信号源编号 23 打开（HDMI 一般在 19–32 之间） |
| `"ignoresPictureBacklight": true` | 电视有 `picture_backlight` 设置但不跟着变（亮度条没反应的小米），改用电视自己的亮度条 |
| `"ignoresScreenBrightness": true` | 电视没有 `picture_backlight`，系统的 `screen_brightness` 写得进去但画面不变（海信 VIDAA 等），亮度一行改为打开电视自己的图像设置 |

步骤：

1. 列出信号源：`CONFIG --ez list_sources true`。输出的 `data=` 后面是 JSON：`id`、`label`、`hw`、`firmwareLabel`（固件自己的名字）、`xiaomiInput`（小米播放器编号），以及 `shown`（主屏幕上是否显示）。
2. 逐个打开：`CONFIG --es open_source '<id>'`，请用户看电视切到了机身上的哪个口（或者没有画面），记下来。然后 `adb shell input keyevent KEYCODE_HOME` 回到 Aerial。
3. 写入修正。JSON 里有引号和空格，要把整条命令放进一对双引号，JSON 用单引号括起来、里面的双引号写成 `\"`（macOS / Linux 的终端，Windows 用 Git Bash），否则电视那边的 shell 会吃掉引号：
   ```
   adb shell "am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es device_profile '{\"hidden\":[8],\"labels\":{\"6\":\"HDMI 1\"},\"xiaomiSources\":{\"6\":23,\"5\":24}}'"
   ```
   输出里的 `device_profile=...` 是保存下来的内容，要和你写的一致。这段 JSON 会**整段替换**这台电视的修正，所以要在当前值（诊断报告「Device profile」一节）的基础上改。写错了不会保存，输出会说 `not valid JSON`。
4. 重复第 2 步确认每个口都对。小米换编号时，一次只改一个口，从 23 开始往上试。
5. 都对了以后，`adb shell dumpsys activity app.orchard.tv/.MainActivity report` 末尾的 **Device profile** 一节就是要交给作者的修正（来源显示为 `ThisTv`）。
6. 想撤销：`CONFIG --es device_profile default`，回到 Aerial 内置的修正。

### 按主页键回不到 Aerial（`home_on_aerial` / `home_from_app` 失败）

1. 自检里 `guard` 也失败：守护没有运行。运行 `curl -fsSL https://aerial.qust.me/install.sh | TV=<IP> bash -s repair`，或者 `adb shell am force-stop app.orchard.tv`，等 3 秒，再 `adb shell am start -W -n app.orchard.tv/.MainActivity`，然后重新自检。
2. `home_from_app` 通过但要 5 秒左右：正常。`input keyevent` 模拟的主页键由 Android 自己处理，之后 5 秒内不许后台 App 打开界面；遥控器的主页键由守护直接接住，没有这个等待。
3. `guard` 通过、`home_from_app` 失败：请用户用实体遥控器按一次主页键，8 秒后运行 `adb shell "dumpsys window | grep mCurrentFocus"`。`input keyevent` 不经过守护的按键过滤，少数电视上结果和遥控器不同，以遥控器为准。
4. 遥控器也回不来：记下 `report.sh` 输出的「Home」和「Bound services」两节，以及按键后 `adb logcat -d -t 300 | grep -E "AerialGuard|ActivityTaskManager|ActivityManager"` 的输出，交给作者。

### 待机后醒不来，或者醒来停在原桌面（`standby` 失败）

- 守护开着时电视待机后醒不来、必须拔电：试 `CONFIG --ez guard_off_in_standby true`（守护在关屏时退出、亮屏时回来，#371）。没有帮助就改回 `false`。
- 小米待机后 ADB 连不上：`CONFIG --ez keep_adb true`。
- 醒来停在原桌面：等 60 秒再看一次（小米低内存机型由同步服务拉回，要 30–60 秒）。还是原桌面，把自检里 `standby` 一行和 `report.sh` 一起交给作者。

### 开机停在原桌面（`reboot` 失败）

自检记下了开机后每秒在前台的包名。原桌面先出现、Aerial 几秒后接管属于正常；一分钟后还是原桌面，就是固件只认自家桌面（OPPO K9、Vidda 等，见 https://github.com/pengchujin/aerial-tv/tree/main/docs/devices ）。这种情况不要停用原桌面，把时间线交给作者。

### 亮度条没反应

先确认亮度条确实动不了面板：`adb shell settings get global picture_backlight`，拖动亮度条后再读一次。数字变了、画面没变：在 device profile 里加 `"ignoresPictureBacklight": true`。读出来是 `null`（没有这个设置）时，看 `adb shell settings get system screen_brightness`：拖动后数字变了、画面没变，加 `"ignoresScreenBrightness": true`。

## 第 4 步：交给作者

把这些整理成一份 Markdown（`aerial-adapt-<机型>.md`），先给用户看：

1. 电视品牌和型号、哪里不对（用户的原话）。
2. 处理之前的 `aerial-selftest-<机型>.md`，和处理之后再跑一次的结果。
3. 试过的开关，每个的结果（有用、没用已改回）。
4. 最终的 device profile JSON（有改动时）。
5. `aerial-report-<机型>.md` 的全文。

用户同意后提交：

- 电脑上装了 `gh` 并已登录（`gh auth status`）：
  ```
  gh issue create -R pengchujin/aerial-tv --title "[机型] <型号>" --body-file aerial-adapt-<机型>.md
  ```
- 没有 `gh`：打开下面的地址（`<型号>`、`<版本>`、`<JSON>` 换成实际值，并做 URL 编码），GitHub 会预先填好表单；再请用户把 `aerial-adapt-<机型>.md` 的全文粘贴到「诊断信息」一栏：
  ```
  https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml&title=[机型] <型号>&model=<型号>&version=Aerial <版本>&profile=<JSON>
  ```

用户没有 GitHub 账号时，把文件交给用户，请用户发到作者的其他联系方式，或者请有账号的朋友代发。
