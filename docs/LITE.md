# Aerial Lite（Android 4.0–4.4）

给 Android 4.x 老电视用的精简版桌面，模块是 `lite/`，包名 `app.orchard.tv.lite`，minSdk 14（Android 4.0），和完整版用同一把签名。

## 为什么是单独的 App

完整版的界面全部用 Jetpack Compose 写，Compose 从来没支持过 Android 5.0 以下，所以没法靠降低 minSdk 来支持 4.x。Lite 用原生 View 重写了界面，不依赖 AndroidX 和 Compose，release 包约 700 KB（大部分是 Inter 字体）。

**外观和完整版一致**（户主的要求，2026-10-26）：同样的磁贴、玻璃底板、焦点框、时钟、控制中心、长按菜单、个性化面板和屏保，尺寸、颜色、字体按完整版的代码逐项搬过来。改了完整版的外观，Lite 要跟着改。

- 包名独立：4.x 电视本来就装不了完整版，两者分开发版、分开升级，versionCode 也互不影响。
- **构建时从完整版复制、不在仓库里存两份的**（`lite/build.gradle.kts`）：`aerials.json`、Inter 字体、`cities-cn.tsv`（assets，复制到 `lite/lite-assets/`，不进版本库）；`GlyphPaths.kt`（图标路径）、`TileRecipes.kt`（精选图标配方）、`Cities.kt`（源码，改包名后放进 `build/generated/shared`）；完整版五种语言的 `strings.xml`（去掉 `app_name`，界面用词和完整版完全一样）。
- **移植过来、要手动同步的代码**：磁贴算法 `TileArt.kt`（完整版的 `TileArtFactory`，规则和数字不变，Compose 类型换成 Bitmap/Canvas，前景层直接合成进一张图，没有视差）、平滑圆角和焦点框/阴影/玻璃边 `Shapes.kt`（`SmoothCorners.kt`、`AppTile.kt`、`LiquidGlass.kt`）、`Gradients.kt`、`Images.kt` 的一部分、`Lunar.kt`（`java.time` 换成 `Calendar`，4.x 上用 `java.time` 要脱糖、多一个 dex）、`IconLibrary.kt`、`Weather.kt`、`SystemIntents.kt`（`SystemProbe` 里各厂商的设置/网络/信号源入口）。
- 4.x 没有的：SVG 路径解析（`SvgPath`，完整版用 Compose 的 PathParser）、`letterSpacing`（Android 5.0 起；之前逐字画）、可变字体（Android 8.0 起；之前用系统的 Roboto，和完整版在老系统上一样）。

## 性能原则（比外观优先）

4.x 电视的 CPU 慢，Dalvik 解释执行，GPU 弱，有的盒子根本没有可用的硬件加速。所以：

- **每帧最多一次全屏绘制**：壁纸按屏幕尺寸预先画好、压暗，烘焙成一张图作为窗口背景（`Wallpapers`），存成 `files/backdrop.png`，冷启动只解码一个文件。用 RGB_565（不透明，传纹理和填充的数据量都减半）。
- **不做实时模糊**：玻璃（底板、面板）和完整版一样，是预先模糊好的 320×180 壁纸小图，按面板在屏幕上的位置截一块、调色、切出平滑圆角，烘焙成一张图（`Shapes.glass`）；按布局位置烘焙，不按动画中的位置（滑入时烘焙会取到屏幕外，面板是透明的）。焦点框、阴影、磁贴圆角和细边也都是烘焙好的位图（`Shapes`），GPU 只画矩形和图片。
- **磁贴是独立的 View，用 translation 定位，焦点放大改 scale**：这些都是 View 属性，开着硬件加速时改动不用重新录制绘制内容，主线程几乎没有负担。
- **首次运行先显示替身**：完整版的磁贴算法（256×256 采样、逐像素抠 logo）在 Dalvik 上每个 App 要零点几秒到一秒，4.4 软件模拟下 29 个 App 要 24 秒。所以先用 `TileFactory.quick`（图标放在同色渐变上）把首页显示出来，真正的磁贴在后台做好后五个一批换上（`AppCatalog.load(quick)` / `onBatch`）。4.4 虚拟机：2.7 s 出首页；4.0.4 虚拟机：0.7 s 出首页，43 个磁贴 6.6 s 全部换完。之后从缓存读，约 1 s。
- **没有硬件加速时不做动画**（`HomeView.smooth`）：焦点和滚动直接跳到位，每按一次键只画一帧。在 Android-x86 4.4（软件渲染）上测过：10 次按键从 62 帧降到 10 帧。
- 软件渲染时一帧只重画脏区域，其余从上一块缓冲拷贝。新装后第一次运行时那块缓冲可能还是黑的：Android-x86 4.4 上打开设置面板，除了顶部一条，壁纸全黑（全新安装 2 次都复现）。所以没有硬件加速时，打开、关闭面板会整窗重画（`redrawAllInSoftware`），修改后全新安装 2 次都正常。
- **空闲时没有任何动画**：时钟每分钟整点更新一次，其余时间 0 帧。
- 时钟容器 `hasOverlappingRendering() = false`，淡出时不分配离屏缓冲。
- 磁贴图在两个后台线程里生成，按「包名 + lastUpdateTime + 尺寸」缓存到 `cache/tiles/`，冷启动只读 PNG。
- 不用 Kotlin 协程和 AndroidX，只用 `Executor` 和 `Handler`。

## 功能

| 功能 | Lite | 说明 |
|---|---|---|
| 首页：单排、下一排露出、玻璃底板、只在底板外显示焦点 App 的名字 | ✅ | 完整版的默认布局（`GridMetrics`） |
| 磁贴：图标库精选图、系统 App 自绘磁贴、白色 logo 统一风格、横幅（5.0+） | ✅ | 4.x 的 App 没有 TV 横幅，靠图标库和图标 |
| 焦点：放大 1.15、白色渐变框、阴影 | ✅ | |
| 小时钟 / 大时钟（日期、农历、天气） | ✅ | 个性化里切换 |
| 天气 | ✅ | 地点只能按 IP 猜（Lite 没有手机页），家里走代理时会猜错（小米上显示 Singapore） |
| 右上角状态按钮（网络、控制中心，进网格后加时间） | ✅ | |
| 控制中心：信号源、网络、设置、音量、亮度、个性化、开机打开的 App、桌面状态 | ✅ | 信号源：4.x 没有 `TvInputManager`，小米电视（有 `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity`、不是盒子）列出 HDMI 1–3（`input` 23–25，和完整版的小米兜底相同，#1094），其他只能打开海信/TCL/酷开自己的信号源面板，没有就提示；亮度：`Brightness`（小米 `picture_backlight`、MiTV 中间件、TCL `TTvPictureManager`、海信激光 `PictureManager`、系统亮度），都不行时打开电视自己的图像设置，和完整版一样；网络、设置受家长密码保护 |
| 长按 / 菜单键菜单：移动、移到文件夹 ›（新建/已有）、从主屏幕隐藏、恢复默认图标、应用信息、开机时打开、卸载；文件夹：移动、重命名 ›、解散 | ✅ | 长按按「按键重复」判断；有的红外遥控器不发重复，可以用菜单键 |
| 布局：单排/双排、第二排露出、玻璃底板、App 名称、时钟左右/大小、天气开关、焦点样式、按键声音、效果 | ✅ | `HomeView` 按完整版 `GridMetrics` 计算 |
| 文件夹（`FolderView`）、家长密码（`PinPad`，数字或方向键） | ✅ | 排序、文件夹、隐藏用完整版的 `LayoutStore`，手机页改的名字用 `AppNames` |
| 个性化：完整版 `WallpaperSheet` 的全部行，右侧手机页二维码 | ✅ | |
| 壁纸：必应每日一图（保留 8 张）、手机上传的照片（两张以上可「每天换」）、内置图片 | ✅ | 每日一图先走 HTTPS，证书不被信任时走 HTTP |
| 屏保：航拍 / 照片（Ken Burns）/ 时钟 / 我的视频，显示时间开关，返回键启动开关 | ✅ | 开始时间比固件屏保早 15 秒（小米的 `screen_saver_timeout`），屏保时保持亮屏 |
| 手机配置页（换图标、改名、排列主屏幕、上传照片和视频、设天气城市） | ✅ | 完整版的网页和接口（`PhoneServer`），NanoHTTPD + ZXing 3.3.0；端口 8790 被占用时依次用 8791… |
| HOME 键回到第一个磁贴、开机打开的 App | ✅ | |
| 儿童与长辈模式：只显示选好的 App、不能整理、退出要家长密码、每天时长 / 最晚到几点 / 休息、锁屏和「再看 N 分钟」、手机页的卡片 | ✅ | `Kids.kt`（完整版的 `Restricted` + `KidsWatch`，时间规则用 `Calendar` 重写，`KidsTimeTest`）、`KidsPanels.kt`（设置面板是一列：选项、进入按钮、App 列表；锁屏）。没有守护：4.4 以下每 2 秒用 `getRunningTasks` 看前台（`GET_TASKS`，maxSdk 20），把没选的 App 拉回来；5.0 以上只能限制桌面。提醒用 Toast。可选的 App 里没有信号源（Lite 没有信号源磁贴） |
| 航拍壁纸、我的视频当壁纸、信号源磁贴、应用内升级、系统屏保、4K/杜比画质 | ❌ | 原因见下面「不做的」 |
| 守护版（无障碍接管 HOME） | ❌ | Lite 只有 standard 版（声明 HOME） |

### 航拍屏保

Android 4.x 不信任 Apple 的根证书，系统播放器也没法传入自定义证书，而 `sylvan.apple.com` 的 HTTP 现在会 301 跳到 HTTPS（2026-10 实测），所以完整版在 5.x、6.x 上用的「改走 HTTP」在这里行不通。Lite 在本机起一个只监听 127.0.0.1 的小代理（`FilmProxy`）：播放器打开 `http://127.0.0.1:<端口>/f?u=<地址>`，代理用 HTTPS 去取，只信任打包的 Apple 根证书，并打开 4.1–4.4 默认关掉的 TLS 1.2（4.0 用 TLS 1.0，sylvan 也接受），Range 请求原样转发。只代理 `https://sylvan.apple.com/` 下的地址。

片源用 1080p H.264（`url` 字段）。20 秒没准备好、或者出错就换下一段；连续 3 次失败改成时钟屏保。

## 模拟器

新版 Android 模拟器在 Apple Silicon 上不能跑 ARM32 和 x86 的 4.x 镜像（`CPU Architecture 'arm' is not supported`），SDK Tools 25 的 classic 模拟器在 Rosetta 下会崩溃。所以用 Homebrew 的 QEMU 跑 Android-x86 的 Live ISO（TCG 软件模拟，慢，但能用）：

- **4.4.4**：`android-x86-4.4-r5.iso`（SourceForge）。`qemu-system-x86_64 -cpu qemu64 -smp 2`，内核参数 `androidboot.hardware=android_x86 nomodeset UVESA_MODE=1280x720 console=ttyS0`，网卡 `rtl8139`，`hostfwd` 把 5555 转出来就能 adb。开机后先在串口 shell 里 `echo x > /sys/power/wake_lock`，否则熄屏后系统挂起，adb 跟着断；`settings put global package_verifier_enable 0`，否则 `adb install` 卡在 Google 的「验证应用」上。没有 GLES2，App 走软件渲染。
- **4.0.4**：archive.org 上的 `android-x86-4.0-eth0-asus_laptop-20120516.iso`。要用 `qemu-system-i386 -cpu n270 -smp 1`（init 用了 SSSE3，pentium3 会反复重启；qemu64 卡在内核），`androidboot.hardware=asus_laptop vga=788 console=ttyS0`。这个镜像只带 Atheros 和 r8169 网卡驱动，QEMU 都不模拟，所以没有网络；SurfaceFlinger 依赖 Intel 显卡，屏幕上没有画面。传文件靠串口 shell 的 `base64`，检查界面靠 App 把自己的 View 树画进位图（临时代码，没有提交）。

## 验证情况（2026-10-26，模拟器，没有真机）

- 4.4.4（1280×720）：首页、焦点、滚动、长按菜单（松手不会误触第一项）、移动并保存顺序、设置面板、换壁纸、HOME 键复位、打开 App、时钟屏保。冷启动到第一帧 195–343 ms，磁贴（缓存）约 250 ms 就绪，进程 PSS 约 16 MB。航拍：代理的 HTTPS、证书、Range 都正常（206，读到文件末尾的索引），但虚拟机里的解码器解不了 H.264 High，画面没出来，失败 3 次后改为时钟，符合预期。
- 4.0.4（800×600）：安装、启动、加载 43 个 App、焦点、滚动、菜单键菜单、设置面板，没有 VerifyError 或 NoSuchMethodError。
- TCL MT5879（Android 11，1080p，2026-10-26）：Lite 也能装在新系统上，用来测真实的 GPU、解码器和流畅度（测试包去掉了 HOME 声明，用多屏互动推送安装，测完已卸载）。导航 253 帧：中位数 5 ms，p99 7 ms，0 卡顿；打开面板、换壁纸各有一帧 20 ms 左右（整张 1080p 纹理上传）。首次运行生成 53 个磁贴 1.24 s。稳定时 PSS 59 MB（同一台电视上完整版 243 MB）。航拍：TCL 的系统播放器 UniPlayer 走隧道播放，视频经 sideband 直接送到硬件视频平面，screencap 截不到；合成器里视频层是全屏 SIDEBAND，Lite 的窗口在视频区域是透明的，播放器状态 PLAYING，连续播了 1 分半，没触发失败重试。画面还没有用眼睛确认。
- 新系统上发现的问题：targetSdk 34 在 Android 9 以上默认不许明文 HTTP，播放器访问 `http://127.0.0.1` 的代理会被拦下（4.x 没有这条规则，模拟器里看不出来）。`res/xml/network_security_config.xml` 只对 127.0.0.1 放行。
- 外观对齐完整版后（2026-10-26 晚）：小米 MFTR0（Android 14，1080p）上逐项和完整版对比过首页、网格、控制中心、长按菜单、个性化、每日一图、大时钟和天气、航拍屏保（能截到画面，在播）；4.4 虚拟机上从空数据启动（替身 → 真磁贴、每日一图下载）；4.0.4 虚拟机上安装启动、43 个磁贴，无崩溃，Dalvik 校验只有两处无害的提示（8.0 才用的 `Typeface.Builder`，Kotlin 给 `LinkedHashMap` 生成的桥接方法，都不会被调用）。
- 儿童与长辈模式（2026-11-02，小米 MFTR0 上的 `-Pnohome` 测试包，Android 14）：设置面板（先设密码、选项、选 App、进入）、模式里的首页和控制中心、退出后「回到儿童模式」、退出后户主的首页原样不变、手机页 `api/state` 的 kids。锁屏用一个本地临时包（把最早时间改成 19:00，没有提交）在 18:05 触发「现在不能看电视了」：返回键无效、家长密码、「再看 15 分钟」后解锁。`getRunningTasks` 拦其他 App 只在 4.4 以下有效，没有 4.x 真机，没测。
- 还没在真机上看过：4.x 真机上的一切、长按（按键重复）在红外遥控器上的表现、开机进桌面。

## 构建和安装

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :lite:assembleRelease :lite:testReleaseUnitTest :lite:lintRelease
adb install -r lite/build/outputs/apk/release/lite-release.apk
```

`tools/install.sh` 在 `ro.build.version.sdk` 低于 21 时自动装 Lite（`install.sh lite` 明确指定）。两边互相拒绝：5.0 及以上写 `lite` 会报错退出（系统不拦 Lite，装上会多一个 HOME 候选），要在新电视上试 Lite 时加 `FORCE_LITE=1`，并且用 `-Pnohome` 的包；4.x 上明确写 `standard`/`guard`/`repair` 也报错退出（完整版本来就装不上）。流程：下载 `aerial-lite.apk`（官网，失败再 GitHub Release），`adb install -r`，`pm grant WRITE_SECURE_SETTINGS`（亮度；4.0 不支持 shell 授权，失败忽略），打开 `HomeActivity`，然后提示用户按 HOME 键，在系统弹出的选择框里选 Aerial Lite 并选「始终」（4.x 没有能从 shell 设默认桌面的命令）。`tools/restore.sh --uninstall` 也会卸载 Lite。

发版：1.59 起 Release 里多一个 `aerial-lite.apk`（`lite/build/outputs/apk/release/lite-release.apk` 改名），官网 `download/aerial-lite.apk` 由 `tools/deploy-site.sh` 一起下载。注意 `-Pnohome` 构建会覆盖同一个输出文件，发版前要重新 `./gradlew :lite:assembleRelease`。第一版（versionName 1.0，versionCode 1）在官网和 README 标为测试版。

**在装着完整版的电视上试 Lite**：用 `./gradlew :lite:assembleRelease -Pnohome` 构建，`FORCE_LITE=1 BUILD=1 TV=… tools/install.sh lite` 安装。这个包不声明 HOME（新增一个 HOME 候选可能让系统清掉现在的默认桌面），只作为普通 App 出现在 Aerial 的网格里。

## 接手说明（2026-10-27）

上一轮交接时编译不过（底层换了接口），这一轮把界面接上了，并补齐了上面表里的功能。

**这一轮修掉的界面问题**

- 面板「半透明」：`GlassBox` 在玻璃还没烘焙好的第一帧用画玻璃的同一个 `Paint` 画了 90% 不透明的底色，alpha 留在了 Paint 里，之后的玻璃都只有 90%，个性化面板透出后面的磁贴和时钟（小米 MFTR0）。底色改用自己的 Paint。
- 个性化下面几乎没有内容：滚动区是 FrameLayout，给 wrap_content 子 View 的高度上限是自己的高度，下面的分组被压扁了。改成 `Viewport`（子 View 高度不限）；壁纸那一排同理（横向）。
- 滚动内容画到面板外面：开着硬件加速时，父容器的 `clipChildren` 只把子 View 裁到子 View 自己的边界，所以视口要靠**视口的父容器** clipChildren=true 才会被裁。`Sheet.unclip` 现在保留视口和它父容器的裁剪。
- 控制中心获得焦点的控件放大后被父容器切成直角（上一轮的 `unclip`）。

**不做的**：航拍壁纸和我的视频当壁纸（4.x 上首页底下一直播视频太重）、系统屏保（Lite 不是 DreamService）、4K/杜比画质（4.x 基本没有 HEVC）、信号源磁贴（4.x 没有 `TvInputManager`）、应用内升级（还没有 Lite 的版本文件，升级就是再装一次）。

**验证（2026-10-27）**

- 4.4.4 虚拟机（软件渲染）：从空数据启动、首页、控制中心、个性化全部行和手机页二维码、隐藏的 App、建文件夹、家长密码（输错红字提示）。这个镜像里 Google 服务把 CPU 吃满，`adb shell input` 会挂住：测试时在虚拟机里 `pm disable` 掉 gms/vending 等，按键走串口 shell。
- 4.0.4 虚拟机：安装启动、43 个 App，控制中心、个性化全部行、长按菜单和子菜单、新建/打开文件夹、屏保，进程没有崩溃；Dalvik 校验仍只有那两处无害提示。画面靠一个临时测试包看（按 F12 把窗口画进 `files/snap.png`，串口 base64 取出，代码不进仓库）：首页、控制中心（离线状态）、个性化（壁纸卡片、设置行、离线时的二维码占位）都和 4.4 一致。
- 小米 MFTR0（`-Pnohome` 测试包）：首页单排/双排、全部名字、网格顶部渐隐、控制中心、个性化、长按菜单 → 移到文件夹 → 打开 → 移出、照片屏保（Ken Burns）、家长密码（设、重新上锁后要求输入、输错、关闭）、手机页接口和全部磁贴图片。
- 小米 MFTR0 上还测了：亮度滑块（`pm grant` WRITE_SECURE_SETTINGS 后走 `picture_backlight`，1 → 5 → 10 → 1；没授权时显示「在图像设置里调」，和完整版一样）、「我的视频」屏保（手机页上传 → 屏保播放，不显示文件名，和完整版一样）。
- 开机打开的 App：临时去掉「开机 3 分钟内」的条件编了一个包，在小米上从长按菜单设「天气」，重启 Lite 后自动打开天气；控制中心的「开机时打开」行和子页、清除都正常（测完代码已改回，设置已清除）。
- 还没看过：真实 4.x 电视。

**测试**：小米 MFTR0 上用 `./gradlew :lite:assembleRelease -Pnohome` 装测试包（不声明 HOME），和完整版逐屏对比；那台电视上完整版占着 8790，Lite 的手机页在 8791。4.4 和 4.0.4 虚拟机见上面「模拟器」一节。
