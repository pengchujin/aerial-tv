# TCL MT9655（国行，雷鸟 55S595C Ultra）

> 和 [tcl-mt5879-cn.md](tcl-mt5879-cn.md) 是**两个不同平台**，行为差异很大。来源：#50（作者 @sherlock2221b），对应 issue #52。

- 零售型号：雷鸟 **55S595C Ultra**；设备串号 `FF-CN-0015-S595CPRO-TX`；固件 `V8-0015T11-LF1V487`。
- 平台 MediaTek **MT9655**，3840×2160，4 GB / 64 GB。
- 原桌面：TCL 自有桌面。**已确认 `com.android.settings` 与 `com.android.tv.settings` 两个包都不存在**（用 `263` 指令以多个 class 试过，画面无变化；`android.settings.SETTINGS` / `android.settings.ACCESSIBILITY_SETTINGS` 以 action-only 方式发也没有 Activity 响应）。
- 安装的 flavor：`guard`（`standard` 被 LauncherStrategy 拒绝，与 MT5879 一致）。
- **实测结论：APK 能装进去，但拿不到 ADB 授权 ⇒ 无法完成 HOME 接管。**

## 与 MT5879 的关键差异

| 项 | MT5879（文档已有） | **MT9655（本机）** |
|---|---|---|
| ADB 类型 | 传统 adbd，明文 5555，弹「允许 USB 调试吗？」 | **ADB over TLS（Android 11+ 无线调试）**，无弹窗 |
| 端口 | 固定 5555 | **每次开关都变**，实测 34007 → 33837 → 40357 → **42803** |
| 授权方式 | 电视上点允许 | **无配对码入口，无法授权** |
| 5555/5554/5037 | 开 | **全程 closed**（连发两次 `CNXN` 均被强制 `STLS`） |
| 无障碍设置页 | 可打开 | **设置里没有该入口** |

## ADB over TLS 的实测证据

1. 对调试端口发标准 ADB `CNXN`（24 字节头，含 `crc32` 与 `magic`），设备回的不是 `CNXN`/`AUTH`，而是 **`STLS`**（arg0=`0x01000000`，magic `0xacb3abac` 校验通过）。
2. **故意不升级 TLS 再发一次 `CNXN`，设备仍然回 `STLS`** ⇒ 强制 TLS，**没有任何明文回落**。
3. `STLS` 协商后 TLS 握手可成功（TLSv1.3 / `TLS_AES_256_GCM_SHA384`），但服务端随后拒绝客户端证书：
   - 带证书（`~/.android/adbkey` 派生、CN=adb 自签）→ `CERTIFICATE_UNKNOWN`
   - 不带证书 → `CERTIFICATE_REQUIRED`
   持续重试 40 次全部失败，说明「远程自证」被协议禁止，授权只能在电视侧完成。
4. `adb connect` → `failed to connect`；`adb pair` → `protocol fault`。
5. **扫描 30000–50000 发现过两个端口**：`42803`（调试端口，发 `CNXN` 回 `STLS`）与 `37115`
   （TCP 可连但发 `CNXN` 后 4 秒零回应，符合配对服务特征）。对 `37115` 发 `STLS` 同样零回应，
   用 6 位投屏码 `adb pair` 也无响应 ⇒ **投屏码不是 ADB 配对码**。

⚠ 排查提醒：ADB 消息头是 **24 字节**（`cmd, arg0, arg1, data_length, data_crc32, magic`，小端），
只发 16 字节会让设备不回应，容易误判成「端口开着但不是 adbd」。

## 安装：多屏互动仍然可行，但下载地址必须是公网 URL

`aerial-guard.apk` v1.14 一次通过（`PROGRESS stage 3 → stage 5`）；`standard` 版走 `stage 3 → stage 4`（被拒）。

⚠ **与 MT5879 的做法不同**：MT5879 文档用 `adb reverse tcp:8765 tcp:8765` + `http://127.0.0.1:8765/Aerial.apk`。
本机没有 ADB，**做不了 reverse**。实测电视**不接受局域网 `http://<Mac IP>:<非标端口>/...` 的 URL**——
HTTP 服务日志里完全没有来自电视的请求（本机 macOS 防火墙为 disabled，已排除）。
改用**公网可访问 URL** 即可成功，例如 `https://gh-proxy.com/https://github.com/pengchujin/aerial-tv/releases/download/v1.14/aerial-guard.apk`。

⇒ 建议 `install.sh` 在**没有 ADB 授权**的场景下，把下载地址参数改成可配置的外网 URL，而不是默认走 `adb reverse`。

## 设置面板：9 个分类，没有无障碍

`com.tcl.settings/.ui.MainActivity` 打开左侧悬浮面板，共 9 类：图像 / 声音 / 网络 / 蓝牙 / 信号源 / AI智能 / 通用 / 个性化 / 关于。

- **通用** = 语言、位置、输入法、开关机设置、快捷指令、呼吸灯、气泡说明 → **无无障碍**
- **个性化主题** = 桌面壁纸（选取壁纸）、氛围屏保（**「选取屏保」为禁用态**，与「屏保时间=关闭」联动）、屏保时间
- **关于本机** = 电视名称、本机信息、网络信息、软件更新

`223>>GET` 解出只有 **12 个可启动应用**：云视听小电视、天气预报、应用商店、多屏互动、影视仓、用户中心、
机顶盒设置、`com.tcl.settings`（设置）、`app.orchard.tv`（Aerial）、VidHub、媒体中心、网易云音乐。

⚠ **每次新建 TCP 连接都会让 TCL 侧边设置面板自动关闭**（相当于「手机已连接」提示抢焦点）。
所以「打开设置 → 按键导航 → 截图确认」必须**在同一个连接里一口气跑完**，否则按键会全部落到桌面上。

## 无 ADB 时的补救：T-Cast 6553 的高级指令

多屏互动除了 `223>>INSTALL`，还支持更多指令（详见 [../tcast-protocol.md](../tcast-protocol.md)）：

| 指令 | 作用 | 本机实测 |
|---|---|---|
| `263>><1 activity / 2 broadcast>><intentJSON>` | 发任意 Intent（等于远程 `am start`），可指定 `component{pkg,cls}` | ✅ 拉起 `app.orchard.tv/.MainActivity` 成功 |
| `225>>` | 电视截图，回包带图片 URL | ✅ 可拿到 89 KB PNG（**视频层截不到，字幕/时钟可见**） |
| `149>><keyCode>` | 模拟遥控按键（up=11/down=12/left=13/right=14/ok=15/back=16/menu=18/home=19） | ✅ 可用于盲导航 |
| `223>>GET` | 应用列表 | ✅ 解出 12 个应用 |

这让**无 ADB 时也能远程诊断**（截图看画面、发 Intent 拉起页面）。但**仍无法替代 ADB**：
无障碍开关、appops 自启动放行、`screensaver_components` 都需要 shell 权限。

## 仍未解决

无障碍入口被固件删除 ⇒ 无法手动开启「Aerial 桌面守护」；ADB 又是 TLS-only 且无授权通道 ⇒ 死锁。
现状：Aerial 只能当普通 App 用（点图标进，按 HOME 回 TCL 桌面）。

已试过且**无效**的手段（避免重复排查）：
- `adb pair` / `adb connect`（含投屏码）
- 自建 ADB-over-TLS 客户端自签证书
- `android.settings.SETTINGS` / `ACCESSIBILITY_SETTINGS`（action-only 与指定 class 均无效）
- `com.android.settings` / `com.android.tv.settings`（包不存在）
- TCL 设置 9 个分类逐个翻查（无障碍不在其中，屏保只列自家内容）
- 工厂菜单路径（信源 → 模拟接受 → 菜单 → 设置 → 显示 → 专业设定 → 对比度 → 数字键 `1950`）**未验证成功**

## 待作者确认的三个问题

1. MT9655 平台是否有别的 ADB 入口？工厂菜单那条路径开出的是不是传统 adbd（明文 5555 + 弹窗授权）？
2. 完全没有 ADB 时，`guard` flavor 是否还有其他接管 HOME 的可能（不依赖无障碍）？
3. 需要我补哪些 logcat / `dumpsys` 才能定位授权通道？

## 4K 界面（TCL 75Q10L，#607，作者 @waverjiang 的排查，Android 14）

- `wm size` 是 1920×1080 / 320dpi，`dumpsys display` 的 real 也是 1920×1080，`ro.odm.tcl.panel_resolution` = 3840x2160，`ro.feature.graphics_4kui=true`。
- TCL 自家桌面 `com.tcl.cyberui` 拿到 3840×2160 的缓冲加 0.5 的图层缩放，HWC 1:1 送显；第三方（Aerial）是 1080p 缓冲被放大，文字发虚。`framework.jar` 里有 `isUhdActivity` / `updateUhdActivityList`、`TCL_4KUI_WINDOW_SCALE` 等，像是系统服务按名单给窗口下发 4K 缓冲。`wm size 3840x2160` 和 `display_size_forced` 都被框架拦掉。
- 显示模式是 1080p，所以 1.51 的 4K 画布（`UhdCanvas`）不会自动打开。之后改成按 `ro.feature.graphics_4kui` 判断（#645），面板尺寸用 `panel_resolution` 算出 2 倍，这台会自动打开；`--ez uhd_canvas false` 可关。HWC 会不会像对 TCL 桌面那样把这一层 1:1 送到面板，待作者实测；名单接口能否给第三方用，待看 framework.jar / services.jar。
