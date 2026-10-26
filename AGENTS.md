# Aerial：给 AI 代理的说明

tvOS 风格的 Android TV 桌面，面向国内电视（小米、TCL 等），Kotlin + Jetpack Compose。

开始任何工作前先读：
1. `docs/HANDOFF.md`：现状、操作方式，以及**必须遵守**的规则
2. `docs/ARCHITECTURE.md`：代码结构
3. `docs/devices/*.md`：各台电视的限制和解决办法
4. `local/TVS.md`（如果存在）：电视的 IP、每台电视上改过的设置、户主的数据。这些是本机信息，不要复制进版本库里的文件

## 命令

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleStandardRelease assembleGuardRelease
source local/tvs.env && BUILD=1 KEEP_ADB=1 TV=$XIAOMI_TV tools/install.sh   # BUILD=1：用本地编译的 APK；不加就下载最新 Release
```

- 连电视的命令（adb、curl 访问电视）需要关闭沙箱。
- 在电视上测试一律用 release 版并执行 `cmd package compile -m speed -f app.orchard.tv`，debug 版在电视上会卡。
- 发 TCL 版本前先给 `versionCode` 加 1。

## 代码约定

- 注释和标识符用英文，界面文字放 `res/values`（英文）、`res/values-zh`（简体中文）、`res/values-b+zh+Hant`（繁体中文）、`res/values-ru`（俄语）、`res/values-uk`（乌克兰语）、`res/values-kk`（哈萨克语），六份都要加，占位符要一致。手机设置页 `assets/web/index.html` 的文字在 `KEYS` / `TEXTS` 表里，同样六种语言，脚本里不要写死文字。
- 界面按 960×540dp 的画布设计（`MainActivity` 统一缩放），不要按真实 dpi 计算。
- 动效：使用 `ui/theme/Theme.kt` 里的 `Motion` 曲线；会被打断的动画（比如焦点）用 spring；不在空闲时跑持续动画；每帧最多一次全屏绘制。
- 遥控器可按的控件用 `ui/components/Pressable`，它只在「本控件上开始的按压」抬起时触发，这样长按打开菜单时，那次抬起不会误触菜单的第一项。
- 厂商的特殊处理写成按功能检测（看包是否存在、设置是否存在），并在注释里写明是哪台机型上验证的。只有确实无法检测时，才写进 `DeviceProfile` 按机型硬编码。

## 不要做

- 不要停用 `com.mitv.tvhome`，也不要卸载、停用任何系统应用，除非用户明确要求且清楚后果。
- 写 `enabled_accessibility_services` 时不要覆盖其他服务；在 zsh 里写 `${VAR}:...`，不要写 `$VAR:...`。
- 不要删除或改动户主的数据（上传的照片、选好的壁纸、电视里别人的 APK）；清理时只按自己创建时记下的 id 删。
- 不要提交 `local/` 目录（签名密钥、IP）。
- 没有在真机上验证过的功能，不要说「已完成」。
