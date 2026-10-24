# Aerial 安装说明（给 AI Agent）

你在帮用户把 Aerial（开源电视桌面，https://github.com/pengchujin/aerial-tv ）装到 Android 电视上，并让它成为开机进入的桌面。整个过程通过电脑上的 adb 完成，电视不需要 root。

## 规则

1. **亲自在用户电脑上运行命令**，不要只把步骤列给用户。如果你不能执行命令（比如你是手机版或网页版聊天），直接告诉用户：请换成能执行命令的电脑版 Agent（WorkBuddy、豆包电脑版或豆包工作版、千问电脑版、Trae、Claude Code、Codex 等），并在第一次运行命令时允许它执行命令、访问网络。豆包手机版、网页版只会把步骤告诉用户，不会执行。
2. **不要假装成功。** 每一步都看命令的真实输出；最后必须完成「验收」，把原始输出原样贴给用户，包括 `resolve-activity` 解析到的桌面和 `enabled_accessibility_services` 的内容。没有看到这些输出，就不能说装好了。
3. **不要卸载、停用任何系统应用**（包括电视原来的桌面），不要删除电视上的任何文件，不要恢复出厂设置。只能删除这次安装自己创建的 `/sdcard/Download/Aerial.apk`。
4. 重启电视（`adb reboot`）前先征得用户同意。
5. 需要用户在电视上操作时（允许调试、同意安装），明确告诉用户按遥控器的哪个键。
6. 失败时把命令和输出原样给用户看，再对照下面的「常见情况」处理。

## 第 1 步：准备 adb

先运行 `adb version`。有输出就跳到第 2 步。

没有 adb 时，下载 Google 的 platform-tools（`dl.google.com` 在国内可以访问），解压后用里面的 adb：

- **Windows**（PowerShell）：
  ```powershell
  cd $env:USERPROFILE
  curl.exe -fL -o platform-tools.zip https://dl.google.com/android/repository/platform-tools-latest-windows.zip
  Expand-Archive -Force platform-tools.zip .
  $env:Path = "$env:USERPROFILE\platform-tools;$env:Path"
  adb version
  ```
- **macOS**：`brew install android-platform-tools`；没有 Homebrew 时可以跳过，第 3 步的一行命令会自己下载 adb。
- **Linux**：用系统的包管理器装 `adb` 或 `android-tools`；或者跳过，第 3 步的一行命令会自己下载。

## 第 2 步：连接电视

电脑和电视要在同一个网络。用户给你的电视 IP 记为 `<IP>`。

```
adb connect <IP>:5555
adb devices
```

`adb devices` 里这台电视后面是 `device` 才算连上。

- **`unauthorized`**：电视上弹出了「允许 USB 调试吗？」，请用户选「允许」并勾选「始终允许」，再运行一次 `adb devices`。
- **`failed to connect` / `Connection refused` / 超时**：电视的 ADB 调试没打开，或 IP 不对。让用户按下表打开，再试：
- **5555 连不上、电视里只有「无线调试」没有 USB 调试**（TCL Q9M / Q10L 等 Android 11+ 机型）：这类固件要先配对。让用户在电视上打开 开发者选项 → 无线调试 → **使用配对码配对设备**，要来两组 IP:端口和 6 位配对码——一组在配对码屏幕上，一组在无线调试主屏幕上（两个端口不一样，每次进这个页面都会变）。然后运行 `adb pair <配对屏IP:端口> <配对码>`，成功后 `adb connect <主屏IP:端口>`，`adb devices` 显示 `device` 后马上 `adb tcpip 5555`，再 `adb connect <IP>:5555`，以后就不用再配对。新版 `install.sh` 会引导整个过程（交互输入，或先设 `ADB_PAIR=<ip:端口> ADB_CODE=<配对码> ADB_CONNECT=<ip:端口>` 再运行）。部分 TCL 固件（Q10M、鹤 7 Pro）没有配对码入口，只能用数据线把电视连上电脑执行一次 `adb tcpip 5555`。

| 电视 | 怎么打开 ADB |
|---|---|
| 小米 / Redmi | 设置 → 关于 → **产品型号**，连续按确认键，直到提示进入开发者模式。再到 设置 → 账号与安全，打开 **ADB 调试** 和 **安装未知来源的应用** |
| TCL / 雷鸟 | 设置 → 关于 → **本机信息**，依次按遥控器 **上、下、左、右**（间隔不超过 1 秒），底部出现「更多」，在里面打开 **ADB** |
| OPPO 电视（含 S1） | 设置 → 系统 → 关于本机 → **设备型号**，依次按 **上、上、右、右**，再按确认；然后到 设置 → 系统 → **开发者选项**，打开 **USB 调试**（有的机型叫 ADB 调试）。用户反馈；ColorOS TV 在 OPPO K9 上验证过 |
| 海尔 / 模卡（MOOKA） | 按**电视机身**上的信号源键，弹出信号源菜单后，用遥控器依次按 **左、左、上、右**，进入「海尔工厂菜单」，把 **ADB 调试开关** 设为开（有的机型在第二页 Other Option → adb debug）。只改这一项，别动其他选项，尤其不要选「工厂复位」。（ZNDS 用户反馈，模卡 U65H3 等，未验证） |
| 原生 Android TV / Google TV、索尼、海信等 | 设置 → 设备偏好设置 → 关于 → 连续按 **内部版本号**（Build，不是「Android 版本」，按错了会出彩蛋，按返回键退出即可）7 次，再到开发者选项打开 **USB 调试**（也可能叫「网络调试」） |

电视的 IP 一般在 设置 → 网络 → 已连接的网络 里（OPPO S1 在这一页最下面）。

**打不开 ADB 的机型**：极米 H3、老乐视、荣耀智慧屏、老康佳（看不到版本号）等，目前没有办法，直接告诉用户不支持，不要反复尝试。

**用户没有电脑**：有用户用手机上的「甲壳虫 ADB 助手」「超级小爱」装成功过，本项目没有逐一实测。可以让用户照本说明第 2、3 步在手机上操作（手机 ADB 工具一般不能执行全部命令），装好后尽量再用电脑或你跑一遍「验收」。

## 第 3 步：安装

### macOS / Linux：一行命令

```bash
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=<IP> bash
```

脚本会下载最新版、安装、授权、接管桌面和屏保，结尾打印结果。用户不想让 Aerial 保持 ADB 常开时去掉 `KEEP_ADB=1`。

- 输出提示固件拒绝安装桌面类 App 时，在最后加 `-s guard` 再运行一次。
- **TCL** 会显示「Pushing Aerial through 多屏互动」：请用户看电视右上角的「多屏互动：正在发起应用推送」提示，**先按遥控器「右」选中「同意」，再按确认**。

跑完后去做「验收」。

### Windows：逐条运行

在 PowerShell 里运行（`adb` 是第 1 步准备好的）。下面的 `adb shell "..."` 在电视上执行，Windows 和 macOS 写法一样。

1. 下载 APK：
   ```powershell
   curl.exe -fL -o aerial-standard.apk https://aerial.qust.me/download/aerial-standard.apk
   ```
   官网下载失败时改用 `https://github.com/pengchujin/aerial-tv/releases/latest/download/aerial-standard.apk`。

2. 安装：`adb install -r aerial-standard.apk`，输出 `Success` 继续。失败时：
   - 输出里有 **`INSTALL_FAILED_VERIFICATION_FAILURE`**：这是 TCL，它拒绝一切 adb 安装。按下面「TCL 安装」处理，装好后从第 3 条继续。
   - 输出里有 **`INSTALL_FAILED_USER_RESTRICTED`**（创维等）：见「常见情况」里的创维一条，处理后再装。
   - 其他失败（比如固件不许装桌面类 App）：改用 `aerial-guard.apk`（同一个地址，把 `standard` 换成 `guard`）再装。

3. 授予只有 adb 能给的权限（某条报错可以忽略，继续下一条）：
   ```
   adb shell pm grant app.orchard.tv android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
   adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
   adb shell appops set app.orchard.tv WRITE_SETTINGS allow
   adb shell appops set app.orchard.tv REQUEST_INSTALL_PACKAGES allow
   adb shell appops set app.orchard.tv GET_USAGE_STATS allow
   adb shell appops set app.orchard.tv android:get_usage_stats allow
   adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
   adb shell appops set app.orchard.tv APP_AUTO_START allow
   adb shell appops set app.orchard.tv APP_ASSOC_START allow
   adb shell cmd package compile -m speed -f app.orchard.tv
   ```
   最后两条 `appops` 只有 TCL 认，别的电视报错是正常的。`compile` 要几十秒，让 Aerial 在电视上更流畅。

4. 屏保：运行 `adb shell settings get secure screensaver_enabled`。结果是 `1` 时再运行：
   ```
   adb shell settings put secure screensaver_components app.orchard.tv/app.orchard.tv.dream.AerialDream
   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez own_screensaver true
   ```

5. 保持 ADB 常开（用户同意时；小米待机唤醒后会自己关掉 ADB，开了这项以后升级更方便）：
   ```
   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
   ```

6. 启动 Aerial：`adb shell am start -W -n app.orchard.tv/.MainActivity`，输出里要有 `Status: ok`。

7. 设为默认桌面（装的是 guard 版时跳过）：
   ```
   adb shell cmd role add-role-holder --user 0 android.app.role.HOME app.orchard.tv
   ```
   报错时改用 `adb shell cmd package set-home-activity --user 0 app.orchard.tv/.MainActivity`。小米、TCL 等固件会无视这一步，没关系，下一步的桌面守护会接管。

   **海信 VIDAA**：先运行 `adb shell getprop persist.vt.use_home_pkg`。有输出（比如 `com.jamdeo.tv.vod`）且不是 `app.orchard.tv` 时，记下原值并改成 Aerial：
   ```
   adb shell settings put global aerial_vt_home_pkg <原值>
   adb shell setprop persist.vt.use_home_pkg app.orchard.tv
   ```
   没有输出就跳过，其他电视没有这个属性。

8. 桌面守护：Aerial 启动时会自己打开它。运行 `adb shell settings get secure enabled_accessibility_services`，结果里应该有 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`。没有时手动加上，**保留原来的内容**，用冒号连接：
   ```powershell
   adb shell "settings put secure enabled_accessibility_services '<原来的值>:app.orchard.tv/app.orchard.tv.guard.HomeGuardService'"
   adb shell settings put secure accessibility_enabled 1
   ```
   原来的值是 `null` 或空时，只写 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`。在 PowerShell 里，原来的值中如果有 `$`，要写成 `` `$ ``。

   有的固件上 `settings` 命令会卡住不返回（腾讯极光盒子 6 / Q5043，等 120 秒后断开）：那就跳过这一步，改用 `adb shell dumpsys accessibility` 看 `Enabled services` 里有没有 Aerial，Aerial 拿到授权后会自己打开守护。

### TCL 安装（adb 装不上时）

TCL 只能通过电视自带的「多屏互动」安装，要用 guard 版。macOS / Linux 的一行命令会自动处理，Windows 上需要 Python 3（`python --version`；没有就让用户先装 Python）：

```powershell
mkdir aerial-tcl; cd aerial-tcl
curl.exe -fL -o Aerial.apk https://aerial.qust.me/download/aerial-guard.apk
curl.exe -fL -o tcast-push.py https://aerial.qust.me/tools/tcast-push.py
adb reverse tcp:8765 tcp:8765
Start-Process python -ArgumentList '-m','http.server','8765','--bind','127.0.0.1' -WindowStyle Hidden
python tcast-push.py <IP> http://127.0.0.1:8765/Aerial.apk app.orchard.tv Aerial 0 180
```

运行最后一条后，请用户在电视右上角的提示里**先按「右」再按确认，选「同意」**。脚本打印装好后，结束 `python -m http.server` 进程，运行 `adb reverse --remove tcp:8765`，再回到上面 Windows 第 3 条。

电视上没有多屏互动时：`adb push Aerial.apk /sdcard/Download/Aerial.apk`，再请用户用电视上的文件管理器或当贝市场的「本地安装」打开它安装。

## 验收（必须做）

逐条运行，把**原始输出**贴给用户：

```
adb shell "dumpsys package app.orchard.tv | grep versionName"
adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
adb shell settings get secure enabled_accessibility_services
adb shell "dumpsys accessibility | grep -c -E 'label=(Aerial|app.orchard.tv)'"
adb shell input keyevent KEYCODE_HOME
adb shell "sleep 3; dumpsys window | grep mCurrentFocus"
```

- 第 1 条有 `versionName`：已安装。
- 第 2 条是系统给出的默认桌面。小米仍然是 PatchWall（`com.mitv.tvhome`）、TCL 仍然是自己的桌面时正常，守护接管；是 `app.orchard.tv` 更好。
- 第 3 条有 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`：守护已打开（原生 Android TV 已是默认桌面时可能没有，看第 6 条即可）。
- 第 4 条大于 0：守护正在运行。
- **第 6 条包含 `app.orchard.tv`：按主页键进入 Aerial，安装成功。** 这一条不满足，就不能告诉用户装好了。
- 第 2、3、6 条的原始输出必须贴给用户，不要只说「成功」。第 2 条报错（老系统没有 `cmd`）时，改用 `adb shell pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`。

第 6 条不是 `app.orchard.tv` 时，等 5 秒再运行一次第 5、6 条（有的电视守护启动得慢）。还不是，看「常见情况」。

`input keyevent` 模拟的主页键不经过守护的按键过滤，在少数电视上（索尼 Android 9）结果和实体遥控器不同：这时请用户用实体遥控器按一次主页键，再只运行第 6 条，以这次为准。

都通过后，征得用户同意再 `adb reboot`，等一两分钟电视开机后重新 `adb connect <IP>:5555`，再跑一次第 6 条，确认开机直接进入 Aerial。

## 还原

macOS / Linux：

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=<IP> bash -s restore
```

最后加 `--uninstall`（`bash -s restore --uninstall`）会顺便卸载 Aerial。

Windows 逐条运行：

```
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb false --ez own_screensaver false
adb shell cmd notification disallow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell pm revoke app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW default
adb shell appops set app.orchard.tv REQUEST_INSTALL_PACKAGES default
adb shell appops set app.orchard.tv APP_AUTO_START default
adb shell appops set app.orchard.tv APP_ASSOC_START default
adb shell am force-stop app.orchard.tv
adb shell cmd role remove-role-holder --user 0 android.app.role.HOME app.orchard.tv
```

然后：

- `adb shell settings get secure screensaver_components` 是 `app.orchard.tv/...` 时，用 `adb shell settings get secure screensaver_default_component` 的结果写回去：`adb shell settings put secure screensaver_components <默认值>`。
- `adb shell settings get secure enabled_accessibility_services`：从结果里去掉 `app.orchard.tv/app.orchard.tv.guard.HomeGuardService`（和它前后多余的冒号），其他服务原样保留写回；只剩它自己时运行 `adb shell settings delete secure enabled_accessibility_services`。
- 海信 VIDAA：`adb shell settings get global aerial_vt_home_pkg` 有值（不是 `null`）时，运行 `adb shell setprop persist.vt.use_home_pkg <这个值>` 和 `adb shell settings delete global aerial_vt_home_pkg`。没有值、但 `adb shell getprop persist.vt.use_home_pkg` 是 `app.orchard.tv`（1.15 及以前手动改过）并且装有 `com.jamdeo.tv.vod` 时，运行 `adb shell setprop persist.vt.use_home_pkg com.jamdeo.tv.vod`。
- 要卸载时 `adb uninstall app.orchard.tv`。
- 最后 `adb shell input keyevent KEYCODE_HOME`，电视回到原来的桌面。

## 常见情况

- **小米**：`resolve-activity` 仍然显示 PatchWall（`com.mitv.tvhome`）是正常的，由桌面守护接管。千万不要停用 `com.mitv.tvhome`。建议开 `KEEP_ADB`。
- **小米 Android 9 低内存机型（`adb shell getprop ro.config.low_ram` 是 `true`，比如 MFTP0）**：系统不许任何第三方通知监听，`allow_listener` 没有输出但不生效，这是正常的，不要反复重试。小米待机时会强停 Aerial，1.21 起 Aerial 改由系统的同步服务在强停后约 30–60 秒拉回来，之后守护会把 ADB 也打开。所以待机超过一分钟再唤醒，直接是 Aerial；只待机十几秒就唤醒，会先看到原桌面，最多再等一分钟左右才切回 Aerial。测待机时请待机 1 分钟以上，唤醒后等 30 秒再看结果，不要在这期间按主页键或打开别的 App。1.20 及更早的版本在这类电视上待机后回不来。
- **TCL**：`adb install` 一定失败（`INSTALL_FAILED_VERIFICATION_FAILURE`），按「TCL 安装」走多屏互动。用户没看到电视上的提示时，重新运行一次。
- **创维（比如 7T631_T60，Android 13）**：`adb install` 报 `INSTALL_FAILED_USER_RESTRICTED` 时，是固件在校验 adb 安装。先用 `adb shell settings get global verifier_verify_adb_installs` 记下原值，再运行 `adb shell settings put global verifier_verify_adb_installs 0`，然后重新安装（macOS / Linux 重新运行一行命令）。装好后把原值写回去（原值是 `null` 时用 `adb shell settings delete global verifier_verify_adb_installs`），以后升级时再关一次。这条来自用户反馈，没有在创维真机上验证过。
- **雷鸟 55S595C Ultra（MT9655）等只有「无线调试」的机型**：拿不到授权，能装但接管不了主页键。
- **海信 VIDAA**：第 7 步改了 `persist.vt.use_home_pkg` 以后，主页键直接进 Aerial；但开机仍会先进聚好看（固件在开机完成前拦截第三方 App），按一次主页键即可。
- **Vidda 其他系统、OPPO K9、雷鸟鹤 6**：能装，但开机进不了 Aerial（固件只认自家桌面，除非删掉原桌面，这里不做）。可以把 Aerial 当普通 App 用。
- **验收第 4 条是 0，但第 3 条里有 Aerial**：常见于升级之后，系统还在找旧的安装目录（小米 Android 9，#72）。运行 `adb shell am force-stop app.orchard.tv`，等 3 秒，再 `adb shell am start -W -n app.orchard.tv/.MainActivity`，然后重新验收。macOS / Linux 也可以直接运行 `curl -fsSL https://aerial.qust.me/install.sh | TV=<IP> bash -s repair`。
- **用了一段时间后按主页键又回到原桌面**：同上，先试一次上面的强停再打开。
- **验收第 4 条是 0，第 3 条里也没有 Aerial**：有的固件拦截侧载的无障碍服务。请用户在电视的安全中心或权限管理里允许 Aerial 自启动、无障碍，然后重新运行 `adb shell am start -n app.orchard.tv/.MainActivity` 再验收。
- **忘了家长密码**：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez clear_pin true`，密码就关掉了。
- **不支持**：LG webOS、三星 Tizen、华为智慧屏（鸿蒙）、Android 4.4 及更老的电视（Aerial 最低要 Android 5.0）。极米 H3、老乐视、荣耀智慧屏、老康佳（看不到版本号）等打不开 ADB 的机型也暂时没办法。

## 用户常问

- **会员和 App 还在吗？** 在。Aerial 只换桌面，电视里的 App、账号和会员都不变，从 Aerial 里打开即可。小米 / TCL 的会员、小爱同学、小米账号、有线电视都不受影响。
- **原来的桌面还能用吗？** 能。原来的桌面作为一个 App 留在 Aerial 的 App 列表里（小米的 PatchWall 也在，从「桌面」磁贴进入），打开就是原来的界面和推荐内容。小米影视、PatchWall 的内容都在。
- **开机广告能去掉吗？** 不能。开机广告是固件在 Aerial 启动之前播的。
- **以后怎么升级？** 1.32 起，电视上控制中心出现「有新版本」时点「下载并安装」，在系统安装界面确认即可（小米上验证过）。电视不让 App 安装应用时，再运行一次同样的安装命令（Windows 重复第 3 步的 1、2、3、6 条），设置都会保留。
- **电视上有问题，想反馈给作者**：macOS / Linux 运行 `curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<IP> bash`，它只读不改，生成 `aerial-report-<机型>.md`；读一遍，再贴进 https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml 。没有电脑时，用手机扫个性化里的二维码，在手机页底部「反馈问题」复制诊断信息。
- **出问题去哪反馈？** https://github.com/pengchujin/aerial-tv/issues ，附上电视型号（`adb shell getprop ro.product.model`）和安装输出。
