# Aerial 架构

单模块 Android 应用（`app/`），Kotlin + Jetpack Compose（没有用 androidx.tv 和 Material，全部自绘）。包名 `app.orchard.tv`（产品名是 Aerial，包名沿用旧名 Orchard：改包名会让电视把它当成新 App，丢掉户主数据和全部授权），minSdk 21（Android 5.0），targetSdk 34，compileSdk 36。Compose 固定在 1.9.x（BOM 2025.11.01）：1.10 起要求 Android 6.0。比 Android 5.0 新的接口都要按 `Build.VERSION.SDK_INT` 判断，`./gradlew lintStandardRelease` 的 NewApi 检查能找出漏掉的地方。

## 目录一览

```
app/src/main/java/app/orchard/tv/
├── MainActivity.kt          HOME Activity：窗口设置、960×540 画布密度、广播/网络监听、空闲计时与桌面内屏保、启动 App
├── HomeViewModel.kt         首页状态：App 列表、浮层（Overlay）、系统状态（网络/音量/亮度/信号源/桌面状态）、移动模式
├── apps/
│   ├── Apps.kt              AppCatalog：枚举 LEANBACK_LAUNCHER + LAUNCHER，按包去重，缓存磁贴图（按 lastUpdateTime）
│   ├── TileDiskCache.kt     磁贴图的磁盘缓存（cache/tiles/<包名>.tile），冷启动不用重画
│   ├── TileArt.kt           磁贴图生成：横幅 / 自适应图标双层 / 铺满底板 / 同色系底色；主色；每 App 的 64×36 环境光背景
│   ├── TileRecipes.kt       精选图标库：按包名给 App 指定重绘方式（品牌色、白色 logo、用图标代替横幅、自绘图标）
│   ├── TileSheet.kt         把所有磁贴拼成一张图，用来对比改动前后（ADB 触发，见 HANDOFF「调试」）
│   ├── LayoutStore.kt       用户排序、文件夹与隐藏（SharedPreferences "layout"），首次运行的默认排序
│   └── CustomIcons.kt       用户在手机页给 App 换的磁贴图（files/icons/<包名>.png，16:9）
├── ui/
│   ├── theme/Theme.kt       Palette（tvOS 深色系统色）、Motion（缓动曲线）、Inter 字体（opsz 14/32）、Type
│   ├── theme/Glyphs.kt      图标：Material Symbols Rounded 实心款的路径（GlyphPaths.kt，Apache 2.0），控制中心的开关图标自绘
│   ├── components/Pressable.kt  遥控器可按控件：按下缩放、只在「本控件上开始的按压」抬起时触发
│   ├── components/LiquidGlass.kt  面板的 Liquid Glass 材质（取模糊版壁纸里面板身后的一块）和玻璃边缘
│   ├── home/HomeScreen.kt   首页布局：网格几何、滚动、焦点管理、各浮层挂载、返回键
│   ├── home/AppTile.kt      单个磁贴：聚焦弹簧、阴影、柔光、视差、边缘回弹、长按、移动抖动
│   ├── home/ShelfRow.kt     第一排上方的 App 内容：摘要 + 一排海报卡片（单个焦点目标，自己管理选中项）
│   ├── home/HomeChrome.kt   背景（AmbientBackground）、大时钟、右上角状态胶囊、时间格式
│   ├── home/Overlays.kt     长按菜单、隐藏的 App 面板、MenuRow、MiniTile
│   ├── home/FolderView.kt   打开的文件夹
│   ├── home/PinPad.kt       家长密码键盘（设置 / 解锁，遥控器数字键或屏幕键盘）
│   ├── home/Screensaver.kt  屏保 UI：航拍（Media3 ExoPlayer，见 wallpaper/AerialStream.kt）/ 照片（Ken Burns）/ 时钟
│   ├── control/ControlCenter.kt  控制中心：按钮、音量/亮度滑块、「个性化」入口、有新版本时的提示和升级说明页、底部版本号；信号源页（只负责切换）和信号源设置页（显示在主屏幕、开机时打开）
│   └── wallpaper/WallpaperSheet.kt  个性化面板（壁纸、屏保和开始时间、焦点样式、家长密码、天气）+ 手机扫码卡片
├── wallpaper/
│   ├── Wallpapers.kt        壁纸单例：来源（每日/内置/照片/照片每天换一张/跟随App）、选择、首页背景烘焙（结果存在 files/backdrop/，冷启动直接读回）、缩略图、上传
│   ├── Gradients.kt         14 张 Mac 风格内置壁纸（山丘、沙丘、海、波浪、光晕），在电视上按实际分辨率画
│   ├── Images.kt            解码裁剪、首页压暗烘焙、抖动、box blur
│   ├── Aerials.kt           读取 assets/aerials.json（Apple 航拍目录）；AerialFormats：这台电视能播哪档画质
│   └── PhoneServer.kt       NanoHTTPD 手机配置页 + 二维码生成
├── shelf/
│   ├── Shelf.kt             TvProvider 读取：各 App 的「继续观看」和预览频道节目，一次读完按包名分组缓存；ContentObserver 失效
│   ├── ShelfRows.kt         纯函数：排序、去重、过滤、宽高比（有 JVM 单元测试）
│   └── ShelfImages.kt       卡片图和背景图：http(s)/content/android.resource，LRU、并发上限、失败主机 60 秒内跳过
├── settings/
│   └── AerialSettings.kt    Aerial 自己的偏好（SharedPreferences "settings"）：焦点样式 FocusStyle（默认高对比度）、App 内容（默认显示）、固定到主屏幕的信号源、开机信号源、屏保开始时间、家长密码（SHA-256，解锁 10 分钟或按 HOME 重新上锁）
├── update/
│   └── Updates.kt           每 3 小时读一次官网 download/version.txt，有新版时控制中心提示；点「下载并安装」才下载 APK 并交给系统安装器（见「升级」）
├── weather/
│   └── Weather.kt           时钟旁的天气：Open-Meteo 实时天气，城市来自手机页或 IP 定位
├── system/
│   ├── SystemState.kt       SystemProbe：网络、音量、桌面状态、设置入口、信号源（TvInputManager + 小米路由 + 机型修正）
│   ├── Brightness.kt        亮度：小米 picture_backlight / 标准 screen_brightness / TCL 走面板
│   └── Diagnostics.kt       给 GitHub issue 用的诊断报告（手机页「反馈问题」、`dumpsys activity …MainActivity report`、tools/report.sh）
├── guard/
│   ├── HomeGuardService.kt  无障碍「桌面守护」：原桌面出现就切回、从磁贴进原桌面时临时拦 HOME、亮屏检查、ADB 保持
│   ├── GuardSetup.kt        用 WRITE_SECURE_SETTINGS 重新开启守护 / 保持 ADB / 「已启用未绑定」时强制重绑
│   ├── RevivalListener.kt   通知监听服务：被强杀后由系统重绑，借此复活并重开守护；10 分钟内复活 3 次以上时，看不到前台就不再抢 HOME（乐视 #63）
│   ├── RevivalSync.kt       同步适配器（挂在 settings 上，带一个自己的账号）：系统不许通知监听的低内存 Android 9（小米 MFTP0，#72）上，强停后约 30–60 秒由同步框架拉起 Aerial 并重开守护；守护在运行时什么都不做
│   ├── BootReceiver.kt      真正重启后的兜底
│   └── ConfigReceiver.kt    ADB 专用配置入口（keep_adb、own_screensaver、clear_pin、export_tiles、check_update、update_test），要求发送方持有 WRITE_SECURE_SETTINGS
└── dream/AerialDream.kt    系统级屏保（DreamService + Compose）

app/src/main/assets/aerials.json   Apple 航拍目录（114 条：地址 + 中英文地名 + 时间点说明）
app/src/main/assets/web/index.html 手机配置页（单文件，iOS 风格，深浅色）
app/src/standard/AndroidManifest.xml  只给 standard 版本加 HOME intent-filter
tools/install.sh / restore.sh       部署与还原
tools/report.sh                     收集机型适配信息（只读），输出贴进 issue 的 Markdown
site/update/                        电视升级页二维码打开的页面：这台电视的安装命令和 Agent 提示词
.github/ISSUE_TEMPLATE/device.yml   机型适配 issue 表单
```

## 两个构建版本（flavor）

| flavor | HOME 声明 | 用途 |
|---|---|---|
| `standard` | 有（`src/standard/AndroidManifest.xml` 合并进去） | 能装桌面类 APK 的电视（小米、原生 Android TV 等） |
| `guard` | 无 | 拒绝安装第三方桌面的固件（TCL 的 `LauncherStrategy`），只靠桌面守护接管 |

两者代码完全相同，只差 manifest。

## 首页

- **画布**：`MainActivity` 用 `LocalDensity` 把内容缩放到 960×540dp 的设计画布（`density = 屏幕宽 / 960`）。不同厂商的 1080p 屏幕 dpi 不同（小米 320，TCL 240），这样排版完全一致。Compose 里所有 dp/sp 都按这个画布理解。
- **分辨率**：系统给 App 的界面多大，就按多大画（`ui/theme/Theme.kt` 的 `canvasDensity`：1080p 界面是 2，4K 界面是 4）。预先画好的位图也按它算：磁贴图是「聚焦放大后的磁贴」的实际像素（1080p 时 352×198，4K 时 704×396），壁纸按屏幕像素解码，界面超过 1080p 时每日精选下载必应的 UHD 图。大部分 4K 电视的界面其实是 1080p，由硬件放大（Android TV 的 `config_maxUiWidth` = 1920，Google TV 模拟器和家里三台电视都是这样）；只有厂商放开这个限制的电视才会拿到 4K 界面。4K 路径在 Android 15 的 4K 模拟器（`Aerial4KUI`，非 TV 镜像，没有这个限制）上验证过。
- **刷新率**：Android 12 起，如果面板能无黑屏地切到更高的刷新率（`Display.Mode.getAlternativeRefreshRates`），首页会选最高的那个（最高 120Hz），见 `MainActivity.preferSmoothestRefreshRate`。会黑屏的切换一律不做：桌面每次回来都黑一下是不可接受的。
- **网格**（`HomeScreen.GridMetrics`）：5 列，16:9 磁贴，边距 56dp、间距 28dp，第一行在屏幕高 55.5% 处。个性化里选「主屏幕：单排」（`AerialSettings.oneRowHome`，1.23 起默认开；选过「双排」的保持不变）时，第一行下移到底部（屏幕高减一个行距再减 12dp，第二行只露一条边；个性化「第二排：不露出」（`AerialSettings.peekNextRow` 关）时，第二排及以下按滚动进度淡入，停在第一排时完全看不见；第一排再往下放：有底板时底板离屏幕下沿 30dp，和离左右边一样（`PlateMarginBottom`），没底板时磁贴下面留 70dp 给聚焦 App 的名称），好让壁纸里的合照露出来，像 tvOS 全屏 Top Shelf 时那样；时钟大小选「小」（`AerialSettings.largeClock` 关）时，时间和天气是一行小字：时钟选「左上」在左上角（`HomeChrome.CornerClock`），选「右上」在右上角状态按钮的左边。选「大」时是锁屏那样的一块（`HomeChrome.ShelfClock`）：日期、大时钟、天气（当前温度、城市、天气、今天最高最低）三行，左右都和磁贴的边对齐（`AerialSettings.clockRight`）；不论单排还是双排，都固定在状态按钮下面（`GridMetrics.clockY` = 74dp），不再挂在第一排上方（以前单排时浮在屏幕中间，双排时贴着底板）。时钟数字的行框比字高出很多，用 `trimmed` 收掉，让日期离数字 14dp、天气离数字 18dp（小米上量的）。进网格后的位置和模糊背景不变。磁贴用绝对 `offset` 定位，没有用 Row，所以移动 App 时组件不会重建、焦点不会丢，位置变化由 `animateIntOffsetAsState` 做重排动画。
- **滚动**：不用 `verticalScroll`（它会自动 bringIntoView），而是一个 `Animatable` 加内容 `offset`，目标由聚焦行决定（`scrollFor`），用弹簧动画以便连按时平滑改变目标。
- **焦点**：每个磁贴一个 `FocusRequester`（按包名存在 map 里）。方向键由磁贴自己处理：`moveFocus` 失败时触发边缘回弹。每行最后一个磁贴（`rowEnd`）右键是 `FocusRequester.Cancel`，否则 Compose 会找到右上角的状态按钮（#211）。浮层打开时磁贴 `focusable(enabled=false)`，焦点不会逃出浮层；浮层关闭后恢复到原磁贴。HOME 键重置通过 `homeResetPending` 加「窗口重新获得焦点」两个条件触发（在别的 App 在前台时请求焦点会丢）。
- **返回键**：网格里 → 回第一行；第一行 → 启动屏保（tvOS 行为）。
- **焦点样式**：`AppTile` 聚焦时放大约 1.15 倍（宽高各自放大整数个像素，见 `focusedPx`；圆角图有静止和焦点两种尺寸，各自一个像素对一个像素，#307），加阴影、顶部高光和加粗的名字。放大用临界阻尼弹簧（`dampingRatio = 1`，和 Android TV 的 Leanback / Compose for TV 一样不回弹）：之前 0.66 的回弹在白框上会显得发抖。`FocusStyle.HighContrast`（默认）时，另在磁贴外 5dp 处画 3dp 白色描边（外加一圈淡黑边，保证在亮壁纸上也看得见），描边带玻璃那样左上和右下更亮的渐变，内侧一圈细光。描边有自己的透明度弹簧：出现时跟着放大从磁贴边缘长出来，消失得更快，所以左右移动时不会同时看到两个框。描边画在一个不裁剪的独立图层上，和磁贴共用放大、倾斜、推移、抖动这些变换。起因：国产电视都是方向键遥控器，没有 Siri 遥控器那种跟着手指的倾斜，只靠放大的话用户找不到焦点。
- **App 名称**：个性化里「App 名称：只显示选中的 / 全部显示」（`AerialSettings.showAllNames`）。只显示选中的时，第一排有玻璃底板就不显示名称（tvOS 26 的做法；以前试过只显示选中的那个，放在玻璃上很怪）；全部显示时，第一排的名称放在底板里面（不放在底板外：壁纸可能很亮，底板里是统一的磨砂底色，字更清楚），底板下沿多出 28dp（`PlateNameRoom`），底板上的名称聚焦时不跟着高对比度白框往下移（`AppTile.nameOnPlate`），否则会碰到底板下沿；其余名称稍暗。为了不让第二排顶到加高的底板，焦点在第一排时第二排及以下多往下 28dp（`GridMetrics.firstGap`），进网格后靠 `animateIntOffsetAsState` 收回，排距一致。
- **背景**（`HomeChrome.HomeBackground`）：`Wallpapers.backdrop`（选中壁纸，已烘焙压暗），否则（「跟随 App」模式）用聚焦 App 的 `TileArt.backdrop`。和 tvOS 26 一样分两种状态：焦点在第一排时显示清晰的壁纸，第一排下面垫一块 Liquid Glass 底板（比磁贴左右宽 26dp、上下宽 20dp，圆角 32dp，宽度跟第一排的 App 数走）；往下进入网格后，整屏换成模糊、压暗的壁纸。玻璃不做实时模糊：模糊版壁纸（`Wallpapers.frosted`，320×180）由 `Images.frost` 用压暗之前的原图预先做好，通过 BitmapShader 按屏幕坐标铺开，所以底板里看到的正是它身后那块壁纸。切换跟着滚动的弹簧走，没有单独的动画：底板跟着第一排移动，在滚动的前 55% 里淡出，同时最多放大 14dp，像从壁纸上浮起后化开；回到第一排时反过来，淡入并收回原位。两张全屏图只在滚动的中段（进度 30%–70%）交叉淡化，其余时间每帧只画一张。试过让底板一路长大、越过屏幕边缘变成整屏背景，往下时好看，但从网格回到第一排时整屏缩回一条，太夸张，所以没有保留。底板的边缘（顶部内侧的柔光、左上亮右下再亮一点的描边，`LiquidGlass.drawGlassEdge`）跟着底板一起淡出。小米 MFTR0 实测（整屏扩散的版本，同一组按键各测 2–3 次）：GPU 90 分位从 12–13ms 变成 15–16ms。以前没保留玻璃底板，是因为要给聚焦磁贴下面的名字留位置；现在第一排不显示名称，这个问题没有了。
- **右上角**（`HomeChrome.StatusPill`）：按钮里只有网络和控制中心图标，进入网格、大时钟滚走后才加上时间，所以屏幕上不会同时出现两个时间。单排加「时钟：右上」时，时间和天气是按钮左边的一行字（`CornerClock`），不放进按钮。

### 性能约束（重要）

电视 GPU 很弱，这里的瓶颈是全屏填充率。第一版在小米上帧耗时中位数 65ms，改完后约 18ms。
- 首页每帧只允许一次全屏绘制：壁纸和压暗层在 CPU 上预先合成；窗口背景设为 null。
- 空闲时不能有任何持续动画（测量结果是 0 帧）。
- 模糊（`BlurEffect`）只在控制中心 / 隐藏 App 面板打开时用。
- 面板（控制中心、个性化、长按菜单、隐藏的 App、家长密码、移动提示）都用 `Modifier.liquidGlass`：同样取模糊版壁纸里面板身后的那一块，压到一半亮度保证白字可读，再加玻璃边缘。每个面板只填一次自己的面积，和原来的纯色一样；「跟随 App」模式没有模糊版壁纸，退回原来的深色 `Palette.Glass`。
- 加任何全屏效果前，先测：`adb shell dumpsys gfxinfo app.orchard.tv framestats`。
- 一定要装 release 版，并执行 `cmd package compile -m speed -f app.orchard.tv`。debug 版的 Compose 在电视上明显卡顿。

## 磁贴图（TileArt）

优先级：

1. **固件自带的 App**（设置、相册、天气、投屏、应用商店、日历、媒体中心、安全中心、浏览器）用 Aerial 自己画的磁贴：渐变底色加白色实心图标，图标下面垫一层很淡的阴影（`SystemTile` + `Glyphs.kt`），像 tvOS 的系统 App；天气是黄色太阳加白云。图标用 Material Symbols Rounded：SF Symbols 的授权只允许用在苹果平台上，Material Symbols 是 Apache 2.0，风格最接近。这些 App 在小米和 TCL 上都没有声明标准的 App 分类，所以按包名列在 `AppCatalog.SYSTEM_TILES`，每个都注明在哪台机器上核对过；声明了标准分类（`CATEGORY_APP_GALLERY` 等）而且没有横幅的系统 App 也走这条。
2. **精选图标库**（`TileRecipes`）：按包名写的「重绘配方」，只决定怎么用 App 自己的图标，不打包别家的图：
   - `top`/`bottom`：磁贴用品牌色渐变，图标的底板去掉，只留 logo（`cutOut`）；
   - `white`：logo 画成白色，只适合单色 logo 配另一种颜色的底板（WPS、夸克、小米「桌面」）；和 `top`/`bottom` 一起用在整张图片式的图标上时，只按亮度取出图里的白色 logo（Z视介：蓝色晶格上的白色 Z）；
   - `useIcon`：有横幅也不用，横幅上文字太多的 App（当贝市场、阿里云盘TV）；
   - `glyph`：完全用 Aerial 自己画的线条图标，给图标和横幅都只有名字的 App（我的电视）；
   - `keepColors`：不走下面的统一风格，保留图标原来的颜色（当贝市场的浮雕字、T惠购的红丝带）。
   每条都注明在哪台电视的 App 上核对过。改完用 `TileSheet` 出图对比。
3. **TV 横幅**。不够宽的「横幅」（有的 App 把方形图标再声明一次，比如遥遥电视助手）不算横幅。
4. **自适应图标**：背景层铺满，前景层单独保存，倾斜时做视差。前景超出安全区，或背景层自己有图形（Google 时钟的蓝色圆盘），就当整张图标处理。
5. **满底色的方形图标**（大多数国内 App）：底色铺满磁贴，图标边缘渐隐进底色。底色取上下边的中位数，并要求 6 成以上的边缘像素接近它，所以角上的小标（WPS 的「TV」）、碰到边的 logo 不影响判断。上下颜色差太多的（小米视频头条：上白下红）不算底板。
6. **老式图标**（认不出底板的）：放在同色系的斜向渐变上（取图标主色，灰色图标用石墨色），和系统 App 的磁贴一个风格。实心方块（含圆角方块）的图标多半是一整张图（视频头条、HiFi音乐），放大 1.25 倍，让里面的 logo 和别的一样大；方块上下边各自基本是一种颜色、两者又差得不太远（`edgeColors`，渐变可以，黑配橙不行）时，把这两种颜色铺满磁贴，图标边缘的 18% 渐隐进去，看起来是整块磁贴而不是贴在上面的图标（HiFi音乐、华数TV、开唛K歌、Z视介）；否则裁成圆角放在同色系渐变上。1.16 之前垫的是图标自己放大模糊的毛玻璃，看起来像手机图标贴在一块污渍上，已去掉。

**统一风格**（`TileArtFactory.unified`，默认开）：3、5、6 三类从图标生成的磁贴，和系统 App 一样画成「App 颜色的斜向渐变 + 白色 logo」（`unify`），这样磁贴之间不会一块白底一块彩底、logo 有大有小。底板本身是彩色的，就用底板的颜色，但把饱和度和亮度拉到同一个范围；底板是白、黑、灰的，就用 logo 自己的颜色。多色 logo（Geekbench 眼镜上的柱子、阿里云盘气泡里的星星、时尚画报）变白会糊成一团，`multiColor` 按色相分组，有两组以上、每组超过 8% 的就保留原样；和底板同色系的像素是 logo 边缘的抗锯齿，不算一种颜色（小米红底上的白「mi」）。TV 横幅是 App 为电视设计的图，不改。

Logo 的大小统一：先在 256×256 的采样里找出 Logo 的范围，再按面积缩放（同面积正方形的边长 = 磁贴高的 0.5），高不超过 0.6、宽不超过磁贴宽的 0.62，放在正中。这样宽的文字标志和圆形图标看起来一样重，也都留在安全区里，聚焦放大和视差不会碰到边。图标直接按最终尺寸画（不缩放位图），画完再量一次，因为有的 `layer-list` 用的是固定 dp 尺寸，不随 bounds 缩放。图标和横幅都取 App 自带的最高密度（`getDrawableForDensity(XXXHIGH)`），否则 TCL 240dpi 上拿到的是 72px 的图标。

前景层会裁到只剩可见像素，记下它在磁贴里的位置（`TileArt.foreAt`）：磁贴每帧都要画，电视 GPU 的填充率很紧。同时算出主色和 64×36 的环境光背景。所有位图都在 `Dispatchers.Default` 上生成，按包名、`lastUpdateTime` 和磁贴像素宽度缓存。

## 个性化：壁纸、屏保、焦点样式、天气

控制中心的「个性化」打开 `WallpaperSheet`，按「看得到的效果」从上到下分组：壁纸（一行缩略图：航拍、每日一图、我的照片（两张以上时加「每天换」）、6 张内置图 `Wallpapers.FEATURED_BUILTINS`、跟随 App；以前的每日图和其余内置图只在被选中时出现；行下面一条说明跟着聚焦的卡片变）、主屏幕（三张卡片：布局/第二排/底板/App 名称/隐藏的 App；时钟位置和大小；焦点样式和按键声音）、屏幕保护程序（类型和立即开始、开始时间）、天气（只读）、家长密码。除壁纸外都是 tvOS 设置那样的分组卡片，一行一个设置，左边名称、右边选项；每组下面一条说明，跟着组里聚焦的那一行变（`Group` / `SettingRow`），说明区固定两行高，焦点移动时面板不跳。天气的城市在手机页设置。右边是手机扫码卡片。焦点移到最后一行「家长密码」时，面板滚到底，让它下面的说明也露出来。

「隐藏的 App」从控制中心挪到了这里：点开是原来的 `HiddenAppsSheet`，返回回到个性化；设了家长密码时先输密码。

## 按键声音

`system/Sounds.kt` 播放电视系统自带的按键音（`AudioManager.playSoundEffect`）：焦点移动和控制中心滑块（音量、亮度）每走一格按方向播 `FX_FOCUS_NAVIGATION_*`，确认和长按打开菜单播 `FX_KEY_CLICK`，返回播 `FX_BACK`（Android 13 以下用 `FX_KEY_CLICK`），在网格边上或滑块到头时播 `FX_KEYPRESS_INVALID`。每台电视听起来和它自己的菜单一样；系统设置里关了按键音（`sound_effects_enabled`）时也不响。小米 MFTR0 用的是 Android TV 那套（`/system/media/audio/ui/android_TV_*`）。1.26 及以前用的是自己合成的音效，用户反馈不好听，已删掉。`MainActivity.dispatchKeyEvent` 把每个按键先交给 `Sounds.onKey`：记下方向键按下的时间和方向，返回键直接出声。`AppTile` 和 `Pressable` 拿到焦点时调 `Sounds.focusMoved()`，只有 250ms 内按过方向键才响，一次按键只响一次，所以打开面板、HOME 复位这类程序给的焦点不出声；唤醒屏保的那一下也不出声。个性化里「按键声音」可以关（`AerialSettings.uiSounds`，默认开）。

- `Wallpapers` 是 Compose 状态单例，只在主线程写。来源：
  - `daily:<date>`：必应 HPImageArchive，`cn.bing.com`，最近 8 张，存在 `files/daily/`；
  - `daily:latest`：一直跟随最新一张；
  - `grad:<key>`：内置壁纸。照 Mac 壁纸的风格自己画的原创图（苹果的官方壁纸有版权，不能放进 MIT 项目）：山丘四张、沙丘两张、海两张、波浪三张、光晕三张，用 Canvas 的路径和渐变在电视上按屏幕实际分辨率生成，光晕先画小图再模糊放大，最后加抖动防色带。旧的 8 张光场改名后由 `Gradients.renamed` 映射到颜色最接近的新图，选过旧图的用户不会掉回默认；
  - `photo:<id>`：手机上传的照片，存在 `files/photos/`，id 是毫秒时间戳的 36 进制；
  - `auto`：跟随 App。
- 首页背景按屏幕实际像素解码，压暗层已经烘焙进去（`Images.shadeForHome`）；网格用的模糊版见「首页」一节。
- 屏保有两条路径：
  1. **桌面内**：`MainActivity` 空闲计时 5 分钟，或在第一行按返回、点「立即开始」→ `vm.screensaverVisible`，同时加 `FLAG_KEEP_SCREEN_ON`，用来压住厂商屏保（小米靠这条）。
  2. **系统级**：`AerialDream`，由 `install.sh` 在使用标准屏保（`screensaver_enabled=1`）的电视上写入 `screensaver_components`（TCL 靠这条）。
- 航拍：`assets/aerials.json` 只含地址和地名，影片实时从 `sylvan.apple.com` 播放，用 Media3 ExoPlayer（#46，`AerialStream`）：全部影片放进一个播放列表，下一段提前加载；连接 5 秒、读取 8 秒超时，只重试一次。`sylvan.apple.com` 的证书链到 Apple Root CA，Android 自带的根证书里没有，`network_security_config.xml` 只对这个域名信任打包的 `raw/apple_root_ca.pem`；Android 5、6 不读这个配置，一开始就走 HTTP。8 秒没画面就换下一段，失败 3 次换照片屏保；这次屏保一帧都没播出来时，2 次就换。播放时把下一次屏保第一段影片的开头和索引预取到磁盘（缓存上限 48MB），下次几乎秒开。小米 MFTR0 实测冷启动 1.3 秒出第一帧。
- 航拍画质（个性化 → 屏幕保护程序，`AerialSettings.aerialQuality`，#198 #217）：自动 / 1080p（H.264，约 10 Mbps）/ 4K（HEVC SDR，约 12 Mbps）/ 4K 杜比视界（约 24 Mbps）。`AerialFormats` 只问解码器和屏幕：电视的界面大多是 1080p（MFTR0 只报一个 1920×1080 模式），问不出面板是不是 4K。有能解 3840×2160@30 的 HEVC 解码器就能选 4K；Apple 的 HDR 文件是杜比视界 profile 5（dvcC 里 `dvhe.05.07`，没有 HDR10 基础层，别的解码器解出来是绿紫色），所以要有支持 `DolbyVisionProfileDvheStn` 到 `LevelUhd30` 的解码器、并且屏幕的 HdrCapabilities 里有杜比视界，才算支持。「自动」选能播的最高一档；这台电视只能 1080p 时不显示这一项。解码器报错（4001–4005）时这一段降一档重播，本进程以后都从低一档开始。缓存键带画质（`id@dolby_vision`），4K 的缓冲目标加到 32/48MB。小米 MFTR0 实测：自动选杜比视界，用 `OMX.MS.DOLBY_VISION.DVHE.STN.Decoder`，1.06 秒出第一帧、颜色正常；4K 用 `OMX.MS.HEVC.Decoder`，0.82 秒。
- 航拍壁纸（壁纸行第一张「航拍」，`Wallpapers.LIVE`，`ui/home/LiveWallpaper.kt`，#216）：停在主屏幕第一排 10 秒没按键，影片在磁贴后面播放（SurfaceView 在窗口下面，`HomeBackground` 的 `cover` 让图片和玻璃底板淡出，播放时 Compose 不画全屏内容）；任何按键、进网格、打开浮层或离开 Aerial 就暂停，这一帧成为壁纸图片（`Wallpapers.useLiveFrame`：照常压暗、做毛玻璃，存成 `files/live-frame.jpg`，下次启动也用它），玻璃底板和网格背景都跟着它；再停 10 秒从这一帧接着播。取到帧时把片子和时间点记在 `wallpaper` 偏好里（`Wallpapers.liveSpot`），播放器释放或重启后也从这一片、这一刻开始（`setMediaItems(…, 0, at)`），静态图和影片的第一帧是同一画面，看起来是一个镜头；没取到帧时不更新，图片和位置仍然对得上。暂停后 2 分钟释放播放器；屏保类型是航拍、屏保出现时影片正在显示（`cover` > 0.5），或焦点在第一排、没有浮层（影片停在静态帧上，比如按返回键），不另开播放器，而是接着放这段影片（静态时从这一帧开始动；10 秒内没动起来就交给屏保自己的影片，`FILM_SAVER_WAIT_MS`）（`HomeScreen` 的 `filmSaver`，`LiveWallpaper.hold`）：磁贴从左到右依次淡出、略往下沉，状态按钮、日期、天气淡出，时间从主屏幕的位置移动并缩放到屏保时钟的位置（`HomeChrome.toSaverClock`，2 秒 `Motion.EaseInOut`），`Screensaver` 只加地点字幕（`FilmLabels`，左右键切片）；按键唤醒时 380ms 收回，影片照常停在这一帧做壁纸。屏保没选过时跟随壁纸（`Wallpapers.screensaver` 由 `chosenScreensaver` 和壁纸算出来，`screensaverFollows`）：航拍壁纸和「跟随 App」配航拍，图片壁纸配照片，并从这张壁纸开始（`screensaverPicture`）；选过一次就固定，没有单独的「跟随」选项。新安装存 `follow`；1.38 之前就在用、没存过选择的存 `aerial`，保持原来的默认（`firstScreensaver`，看 `firstInstallTime == lastUpdateTime`），以后升级不变；1.38 测试版里的「壁纸」类型读成 `follow`。个性化和手机页在跟随时显示说明。照片屏保在第一排、没有浮层、壁纸是图片时一镜到底（`HomeScreen.stillSaver`、`SaverCarry.Still`）：不压暗的原图画在 `HomeBackground` 的图片和底板之间，从和主屏幕一样的构图开始 1.8 秒淡入（压暗退掉）并慢慢推近（`drawIn`：60 秒放大 8% 并向右漂 24dp，再 60 秒回来），底板跟着 `toSaver` 淡出，磁贴和时钟同航拍；18 秒后 `PhotoSaver` 从下一张接着轮播，盖住后主屏幕的图停止绘制（`covered`），屏保自己的时间在主屏幕时钟移到的位置淡入。左右键照常切换。唤醒时 380ms 缩回、压暗回来。单独出现的照片屏保（系统屏保、网格里）也从当前壁纸开始。其他情况（在网格里、类型是照片或时钟）还是原来的黑底淡入。影片停下时底板要和磁贴一起回来：PixelCopy 读不到视频的电视（小米 MFTR0）上，`FrameReader` 在影片开始播放时就用 MediaMetadataRetriever 打开这段片子的 1080p 文件（打开连 Apple、读文件末尾的索引，0.9–1.5 秒，以前每次停下才做，所以要 2.4–6.6 秒），播放中定时取 320×180 的关键帧小图（`GlassSample`，`OPTION_PREVIOUS_SYNC`，约 0.4–0.6 秒，小米实测播放 0 掉帧）。停下时用最近的小图马上做磨砂（`Wallpapers.useLiveFrost`），底板直接画在暂停的影片上（`HomeBackground.filmStopped`，`LiveFilm.stopped`），同时画上静态图会有的压暗（`Images.SHADE`）。`HomeBackground` 分三层：压暗（在图片下面）、图片（换壁纸时交叉淡入）、底板（`Plate`，只有一层）。底板只在影片动起来时让位，所以影片交给静态图时不透明度一直是 1（以前底板放在图片的交叉淡入里，暂停时的底板和图片的底板两层各半透明地交接，中间会透出视频，放大量也跟着变，看起来在闪）；换磨砂时旧的保持不透明，新的在上面淡入；底板看不见时直接换（影片刚停下时，否则会闪出上一次停下的画面颜色），`glassReady` 等两帧再让底板出现。小米实测（临时日志）：唤醒后约 0.3 秒出现，之后到静态图换上，底板不透明度一直是 1.000。然后在后台解精确帧（`OPTION_CLOSEST`，0.4–2.8 秒）做成壁纸图片（磨砂、网格背景、下次启动都用它），但屏幕上不换：暂停的影片继续当壁纸，10 秒没按键就从这一帧接着动；只有打开浮层（控制中心等，影片不在窗口里，得不到模糊）、离开 Aerial、进普通屏保，或停了 `KEEP_STOPPED_MS` 之后，才换成图片（`standIn`）。以前解出来就换，图片和视频不在同一个显示层、画质处理不同，时间点也可能差一两帧，从屏保回来时桌面会轻微一闪、错位。小图第一张在开始播放 0.8 秒后取，之后每 2.5 秒一张。精确帧要从前一个关键帧顺序读解，暂停的播放器还在缓冲时曾超时（`FrameDecoder: Timed-out waiting`），这时退一步用最近的关键帧（`OPTION_CLOSEST_SYNC`），最多差一两秒的镜头移动；都失败才退回上一张图片并换回原来的磨砂。第一次停下时先试 PixelCopy，本进程记住结果（`pixelCopy`）：读不到（小米 MFTR0）以后就不再试；读得到（TCL MT5879）就不再打开文件、也不取小图。屏保出现（不接着放时）或打开别的 App 时立刻释放、不取帧：暂停的播放器仍占着解码器，小米 MFTR0 的屏保因此拿不到解码资源（`vdec resource lost`），1.32 上屏保会降成 1080p，1.33 修好。取帧先用 PixelCopy；小米 MFTR0（MStar）的视频在硬件图层上，PixelCopy 拿到全黑，就用 MediaMetadataRetriever 从同一段的 1080p 文件解出同一时间点（`OPTION_CLOSEST`），实测 1.4 到 3.6 秒，这段时间画面停在暂停的视频上，网格背景先用旧的毛玻璃。最多 4K SDR：电视切进杜比视界模式后，叠在上面的界面也会按杜比的画面设置显示。小米 MFTR0 实测：播放时 Compose 几乎不出帧，左右移动焦点中位 19ms（和静态壁纸一样）。

## 手机配置（PhoneServer）

NanoHTTPD 监听 8790 端口，路径前缀是 8 位随机令牌（每次打开面板都重新生成，二维码里带着）。只在壁纸面板打开时运行，关闭后再保留 5 分钟。接口：`GET /` 页面、`GET api/state`、`GET thumb/<id>`、`POST api/wallpaper|screensaver|delete`（表单）、`POST api/upload`（multipart，可多张）；`GET tile/<包名>`（磁贴现在的样子）、`POST api/icon`（multipart：`app` 加一张图片）、`POST api/icon/reset`、`POST api/weather/city`（`name`）、`POST api/weather/auto`。页面在 `assets/web/index.html`。

- **自定义图标**：图片裁成 16:9、1280×720 存成 PNG，有这张图的 App 直接当横幅画（`AppCatalog.entry`，排在系统 App 自绘磁贴前面）。磁贴缓存的键里带着图片的修改时间；`CustomIcons.version` 变化时 `HomeViewModel` 重新加载 App。电视上的长按菜单里有「恢复默认图标」。包名必须是已安装的 App，同时也保证它是一个合法的文件名。
- **文件夹**（像 tvOS）：长按菜单「移到文件夹」→ 选已有的文件夹或「新建文件夹」。新文件夹占这个 App 原来的位置，名字按 App 自动起（系统 App 叫「系统应用」，其他叫「文件夹」，重名加数字），可在文件夹的长按菜单里从预设名字（`folder_names`）中改；电视上没有好用的输入法，所以不做自由输入。文件夹在 `LayoutStore` 的排序里占一个 `folder:<id>` 键，里面的 App 不再出现在排序里；存成 JSON（`folders`），只记包名。文件夹磁贴是 `folderEntry`，画面是 `TileArtFactory.forFolder`：半透明底板上三乘三排最多九个 App 的缩略图，只在里面的 App 变了时重画。打开文件夹是全屏的 `FolderView`：主屏模糊变暗，上面是名字和同样大小的磁贴；在里面长按，菜单第一项是「移出文件夹」（放到文件夹后面）而不是「移动」，关掉菜单回到文件夹。最后一个 App 移出后文件夹自动消失；「解散文件夹」把里面的 App 按顺序放回文件夹的位置。浮层（菜单、文件夹）关闭时，Compose 会先把焦点交给浮层下面的磁贴，所以打开时记下要回到哪个磁贴（`focusAfterOverlay`、`FolderView` 的 `menuKey`）。小米 MFTR0 上验证过：新建、放入、打开、文件夹里的菜单、重命名、移出、解散，测完排列和原来一样。
- **天气**（`Weather`）：Open-Meteo 的实时天气接口和地名查询接口都不需要 Key，国内能访问。城市优先用手机页里设置的；没设置时用 ipinfo.io 按 IP 定位，每天最多一次。整个家庭网络走代理时 IP 定位会定到别的国家（家里的网络就会定到新加坡），所以手机页会显示定位结果，让用户改。回到主页时刷新，最多每小时一次；最近一次结果缓存 3 小时，开机后先显示缓存。老系统不信任 Let's Encrypt 的根证书，HTTPS 失败时改用 HTTP（`network_security_config.xml` 只对这几个域名放行明文）。

## 升级（update/Updates.kt）

控制中心「有新版本」→ 升级页：

- **下载并安装**：先从官网 `download/aerial-<flavor>.apk` 下载，失败再从 GitHub `releases/latest/download`（不用第三方 GitHub 代理）。下载后检查包名、签名（和这台电视上的 Aerial 比）和 versionCode，不对就不打开安装器，并在页上说明原因（`Updates.Failure`）。改过包名的版本（OPPO）不显示按钮。
- **交给系统安装器**：用 `ACTION_VIEW` + FileProvider（`res/xml/update_files.xml`，Android 7 以下用 `file://`）打开系统安装器，不用 PackageInstaller session：TCL 的安装校验只放行由 `com.android.packageinstaller` 发起的安装，`ACTION_VIEW` 时发起者就是系统安装器（和当贝一样）。要 `REQUEST_INSTALL_PACKAGES`：小米有「安装未知应用」设置页，没开时跳过去；TCL 没有这个页面，靠 `install.sh` 用 `appops set … REQUEST_INSTALL_PACKAGES allow` 放行。
- **装完回到 Aerial**：安装会杀掉 Aerial 的任务，露出底下的东西（小米上是 PatchWall 的详情页，不是原桌面的 HOME Activity，守护原来的规则不管）。所以打开安装器前记下时间（`GuardSetup.noteInstallerOpened`，用 commit 立刻写盘），新进程的守护在 15 分钟内看到原桌面的任意页面就拉回 Aerial；在安装器里取消、回到 Aerial 时清掉这个记录。
- **二维码**：装不上的电视（以及 1.31 及以前的版本）还是要再运行一次安装命令。升级页不再显示长命令，而是一个二维码，打开 `https://aerial.qust.me/update/#v=…&f=…&tv=<局域网IP>`（参数放在 `#` 后面，浏览器不会发给服务器），页面上先是 Agent 提示词，再是电视上的按钮说明和可复制的安装命令。
- 测试：`ConfigReceiver --ez check_update true` 立刻检查新版本；`--ez update_test true` 让「下载并安装」改用 `Android/data/app.orchard.tv/files/update-test.apk`（到 Aerial 重启为止），发版前就能测整条流程。做法：装一个只把 `versionName` 改低的包，推一个带新代码的包当 update-test.apk。

## 诊断报告（system/Diagnostics.kt）

给维护者没有的电视用：机型、固件指纹、屏幕和内存、HOME 解析和所有候选、守护看着的原桌面、守护和通知监听的状态、各项授权、屏保和按键音设置、亮度、`TvInputManager` 的输入和 Aerial 显示的信号源、Aerial 认识的厂商包（只列这些，不列全部 App）、DiagLog 最近 150 行、本进程 Aerial 各 tag 的 logcat（不含 AerialWeather，里面有城市）。日志里的 IP、MAC、手机页令牌打码。不收集账号、序列号、Wi-Fi 名称、天气城市。

三个出口：手机页 `GET api/diagnostics`（页面最下面「反馈问题」，可复制，局域网 HTTP 没有 `navigator.clipboard`，用 `execCommand`）；`adb shell dumpsys activity app.orchard.tv/.MainActivity report`；`tools/report.sh` 在它前面再加只有 shell 能读的：getprop、包信息和 appops、HOME 候选和 role、相关 settings、无障碍绑定、tv_input 硬件、系统服务里关于 Aerial 的 logcat（只按 tag 取：小米应用商店会在日志里打印已装 App 列表）。

## 信号源磁贴

控制中心 → 信号源 → 信号源设置里「显示在主屏幕」的信号源和 App 一起排在网格里（`sourceEntry`）。长按菜单「接入的设备」可以选 Apple TV、PlayStation、Nintendo Switch、Xbox、游戏机、电脑、机顶盒、回音壁（`apps/SourceLooks.kt`，#172 #210）：磁贴换成设备的颜色和标志，大字是设备名，小字是端口（HDMI 1）。Apple TV、PlayStation 的标志是 Simple Icons（CC0）的路径；任天堂和微软让 Simple Icons 撤掉了自己的标志，Switch 和 Xbox 画的是掌机和手柄的轮廓。自己起的名字在手机配置页输入（`POST api/source/name`，最多 24 个字，太长先缩小字号再截断）；手机页也能给信号源磁贴传图片，文件名用键的 SHA-1（键里有 `/`）。名字和设备存在 `settings` 的 `source_looks`（JSON，按 TvInputInfo id）。控制中心的信号源页右边也显示起的名字。

## USB

信号源列表最后一项「USB」打开电视自带的 U 盘播放器（`SystemProbe.usbSource`）：小米是高清播放器 `com.xiaomi.mitv.mediaexplorer/.NewScraperMainEntryActivity`（MFTR0 实测，打开就是「设备」页，插着的 U 盘列在这里），TCL 是媒体中心 `com.tcl.ui_mediaCenter/.main.MainActivity`（MT5879 用 adb 打开过，默认停在「影视墙」，左边第三项是「U盘」）。两个都没装的电视不显示这一项。和其他信号源一样可以显示在主屏幕、设成开机时打开。不跟踪 U 盘插拔，所以不显示「已连接」。

## 开机信号源

控制中心 → 信号源 → 信号源设置 →「开机时打开」（`AerialSettings.bootSource`，issue #25）。也可以开机打开一个 App（`AerialSettings.bootApp`，按包名，比如直播 App，一般会接着播上次的频道）：在主屏幕长按 App 或信号源磁贴，选「开机时打开」，再选一次是「开机时不再打开」；两者只能有一个，信号源设置页里选了 App 时显示在列表里，选「不打开」清除。设了开机打开的对象时，控制中心主页「个性化」下面多一行「开机时打开 ……›」，点进信号源设置。每次选择、更换、取消，屏幕顶部都出现一条玻璃提示，4 秒后消失（`HomeViewModel.chooseBootTarget` / `notify`，`Overlays.NoticeBanner`），更换时写明换掉的是哪个。国内电视的「开机」大多是从待机唤醒，所以三条路径都会触发 `MainActivity.openBootSource`：真重启后第一次启动（按 `Settings.Global.BOOT_COUNT` 每次开机只开一次）；守护在亮屏、复活或开机后把 Aerial 拉回前台时带上 `EXTRA_WOKE`（Aerial 被杀掉的小米）；Aerial 本来就在前台时自己收到的亮屏广播（TCL）。15 秒内重复触发只开一次。如果唤醒时 CEC 已经切到了某个信号源，守护不会拉回 Aerial，这一次也就不再打开设定的信号源。

## 遥控器按键

- 方向键、确认键由磁贴自己处理；长按确认键打开长按菜单。
- 菜单键：焦点在 App 磁贴上时打开这个 App 的长按菜单（老遥控器长按确认键识别不出来，issue #28），其他时候开关控制中心。和长按一样，只在本磁贴上按下、抬起时才触发，抬起不会误选菜单的第一项。
- `KEYCODE_TV_INPUT` 打开控制中心的信号源页。

## 触屏和鼠标（issue #60）

给带触摸屏的商用大屏和平板用，遥控器的逻辑不变。

- `Pressable` 和 `AppTile` 用 `detectTapGestures`：点一下先把焦点移过去再触发；磁贴长按打开长按菜单。触摸事件只发给按下时所在的控件，所以长按后抬起手指不会选中菜单第一项，和遥控器「只在本控件上开始的按压」一致。拖动一旦变成滚动，点按就取消。
- 网格用 `draggable` 拖动 `HomeScreen` 里同一个 `scroll`（不用 `verticalScroll`/`scrollable`，它们会响应焦点的 bringIntoView），松手按速度惯性滑动。拖过之后 `dragged` 为真，滚动不再跟随焦点行；按任意遥控器键（返回键除外）恢复。返回键在拖过的网格上先回第一排。
- 浮层：面板加 `consumeTaps()`，外面的遮罩加 `dismissOnTap()`，点外面等于按返回。没显示的浮层不能挂 pointerInput，否则会挡住首页的所有触摸。
- 移动模式下点某个磁贴把 App 移到那里，点这个 App 本身或空白处完成。提示文字按 `Configuration.touchscreen` 显示带触屏的版本。
- 音量、亮度滑块：左右拖动按步数调（音量按最大值分步，亮度 20 步）。小米 MFTR0 接 HDMI ARC 功放时，音量每一步都要等功放回应，快速拖动会丢步。
- `MainActivity.dispatchTouchEvent` 重置空闲计时；屏保显示时，这次触摸从按下到抬起只用来关屏保。

## 桌面接管（guard/）

层次和原因见 `docs/devices/`。数据流：

```
安装：install.sh 授权（WRITE_SECURE_SETTINGS、通知监听、SAW、WRITE_SETTINGS）
  → MainActivity.onStart → GuardSetup.ensureEnabled()（把守护写进 enabled_accessibility_services）
  → 5 秒后 rebindIfStalled()（已启用但没运行 → 先移除、1.5 秒后加回，触发重绑）
开机：守护、RevivalListener、BootReceiver 都声明了 directBootAware（#45），用户解锁之前就绑定
  - 锁定期间只记窗口，不启动界面、不拦 HOME；凭据加密存储里的偏好（keep_adb、屏保）解锁后再读
  - ACTION_USER_UNLOCKED → 1.5 秒后按下面的规则接管。TCL 75S365C 上从解锁到 Aerial 7.6 秒 → 约 2 秒
运行：HomeGuardService
  - onKeyEvent(HOME) → 不是默认桌面时吞掉按键，打开 Aerial（Android 11 及以下一直开着按键过滤，见 filterKeys）
  - TYPE_WINDOW_STATE_CHANGED → 原桌面的 HOME Activity 到前台 → 打开 Aerial
  - 亮屏、开机、复活、解锁 → 等 1.5 秒，前台还是原桌面（或者不知道）才打开 Aerial（takeOverOnceSettled）。
    只有 Activity 窗口算前台，浮层和对话框不算（TCL 语音助手）。前台是信号源时不动：CEC 切过去的，
    或者电视自己「开机播放上次的信号源」（小米 PatchWall 的 autoPlay），都以电视为准
  - adb_enabled 的 ContentObserver → keep_adb 时写回 1
被杀（小米待机 killAllThirdApp）：系统移除无障碍授权
  → 通知监听绑定断开 → 系统约 0.5 秒后重绑 RevivalListener（进程复活）
  → GuardSetup.ensureEnabled() → 守护重新启用 → onServiceConnected 判断为「刚被复活」→ 打开 Aerial
```

`allowStockLauncher`：用户从网格里主动打开原桌面时，1 小时内不拦截。

电视的播放器（`com.tcl.tv`、`com.xiaomi.mitv.tvplayer`）即使声明了 HOME，也不算原桌面，除非它就是系统解析出来的 HOME：TCL 85G60E 把 `com.tcl.tv` 列为 HOME，每次切信号源都被守护当成原桌面拉回（#223，用户日志；家里的 TCL MT5879 没有这个 HOME，没有真机验证）。

**只装了 APK 的电视**（U 盘、应用商店，#207 #213）：1.32 起 `MainActivity` 也声明 `CATEGORY_LAUNCHER`，国内电视的「我的应用」大多只列这种，以前装完找不到图标。打开后没有 `WRITE_SECURE_SETTINGS`（只有 install.sh 会授权）、桌面状态又是「尚未设为默认桌面」时，4 秒后自动打开一次控制中心的「设为主屏幕」页（`CcPage.Setup`，`settings` 的 `setup_offered` 记住），以后从控制中心底部进去。页上按电视有没有这些设置页显示：无障碍（手动打开桌面守护，小米 MFTR0 有，TCL MT5879 没有）、默认桌面（只有 standard 版；小米会忽略）、通知使用权（待机被杀后复活），以及二维码：打开 `site/update/#setup=1`，给出这台电视的安装命令和 Agent 提示词。Google TV 模拟器（Android 13）上验证过自动弹出和跳到无障碍设置。

## 系统能力（system/）

- **信号源**：`TvInputManager` 列出直通输入和调谐器。装了 `com.xiaomi.mitv.tvplayer` 的走小米的 Intent（HDMI 的 `input = HW 编号 + 18`，AV=2，ATV/DTMB 各自的 Activity），否则走 `TvContract` 的通用 URI。CEC 子输入的名字用于显示设备名，`Playback_1` 这类通用名会被过滤。`DeviceProfile` 存放按机型的修正（MFTR0 隐藏不存在的 HW8，并重命名调谐器）。
- **音量**：`AudioManager` 的 STREAM_MUSIC；TCL 必须加 `FLAG_SHOW_UI`（否则不会写入功放）。
- **亮度**：见 `Brightness.kt` 的注释（小米 / 标准 / TCL 面板三种）。
- **设置入口**：优先厂商自己的设置（小米 `com.xiaomi.mitv.settings`）。

## 资源与第三方

- 字体：Inter 可变字体（OFL，许可证在 `FONT-LICENSE-OFL.txt`）。
- 依赖：Compose BOM 2026.02.01、activity-compose、lifecycle-viewmodel-compose、core-ktx、NanoHTTPD 2.3.1、ZXing core 3.5.3。
- 航拍目录由 Apple 的 `resources-16.tar` 生成（生成方法见 HANDOFF.md）。
- 信号源磁贴的 Apple TV、PlayStation 标志：Simple Icons 16.35.0（CC0），只用来标明接的是什么设备。

## App 内容（Top Shelf）

个性化「App 内容」有三档：「显示」（默认）、「不显示」、「始终」（`AppContentMode`；旧的开/关设置自动迁移）。「始终」是 #296 的做法：焦点一移到有内容的 App 就自动展开，卡片暗着，按「上」才拿到焦点，不出现箭头。「显示」下：第一排某个 App 发布了内容时，焦点在它的磁贴上，磁贴上方出现一个小箭头（白色，带一点阴影，弹出时往上顶一下）；按「上」，屏幕上半部分换成这个 App 自己发布的内容：上面是选中节目的标题、一行「第 N 季 · 第 M 集 · 还剩 X 分钟」（有就写）和简介，下面一排海报卡片；背景换成选中卡片的图（模糊加压暗）。左右移动，确定键打开节目，「下」或返回键回到磁贴。App 有多个频道时，卡片上方出现一排标签（「继续观看」和各频道的名字）：在卡片行按「上」进入标签，左右换标签，「下」回到卡片。没有内容的 App 什么都不变，焦点进入网格或在个性化里关掉「App 内容」时也一样；选了航拍壁纸时，打开 App 内容期间航拍不播放。

- **数据**（`shelf/Shelf.kt`）：系统 TvProvider 的 `watch_next_program`、`preview_program`、`channel` 三张表（Android 8.0+）。Aerial 不是系统应用，拿不到 `ACCESS_ALL_EPG_DATA`（signature|privileged）；`READ_TV_LISTINGS`（dangerous，`tools/install.sh` 用 adb 授予）就能读到所有 App 的行（索尼 BRAVIA_4K_VH2 / Android 12 实测；1.27 记的「读别的 App 的频道要 ACCESS_ALL_EPG_DATA」只对带 selection 的查询成立）。没有这个权限的调用方，TvProvider 不接受任何 selection 和 sortOrder（「Selection not allowed」，Android TV 12 模拟器），所以三张表各读一次整表，在内存里按包名分组，结果缓存；第一排的 App 进首页时就一起读好。`ContentObserver` 监听 `content://android.media.tv`（启动时即注册，不等权限），有变化 1 秒防抖后才重读；只查卡片用到的列。
- **排序**（`ShelfRows.arrange`，有单元测试）：先「继续观看」（最近的在前），再按频道顺序取预览节目（同一频道按 weight 从大到小）；没有链接或标题的去掉，同一链接只留一次，最多 20 条。频道的 `browsable` 由系统桌面写；一个都不是 browsable 时（系统桌面从没问过），就当全部可见，免得一条都没有。
- **图片**（`shelf/ShelfImages.kt`）：卡片图按聚焦放大后的像素解码；背景图缩小模糊（`Images.frost`）再压暗（`Images.shadeForHome`），所以 Emby 那种 300px 的海报做背景也不糊，绘制时仍是一张全屏图。Emby 等媒体服务器在局域网里用 http 和裸 IP 提供图片，域名配置写不出来，所以 `network_security_config` 的 base-config 允许明文（Aerial 自己只在原来列明的几处回退到 http）。同时最多 3 个请求，失败重试一次，连不上的主机 60 秒内跳过（排队中的请求也跳过；服务器回 404 之类只算这一张没有图，不重试，也不影响同一主机的其他图，Emby 会给发布了链接却没有图的条目回 404），单张最大 8 MB；图没到之前卡片显示标题。
- **打开**：`Intent.parseUri(intent_uri)`，限定 `setPackage(发布者)`，卡片不能拉起别的 App；打开失败就打开 App 本身；家长密码锁着的设置类 App 和它的磁贴一样先要密码。
- **标签**（`ShelfRows.tabs`，有单元测试）：每个 `ShelfItem` 带频道 id 和名字（`channel.display_name`），按频道分组；「继续观看」没有频道，标签名是 `shelf_continue`。上限 20 条是每个标签各自的，长频道不挤掉后面的。
- **系统桌面没有时谁来叫 App 发布**（`shelf/ShelfAsk.kt`，有单元测试）：Android TV 的系统桌面在开机和装 App 后给声明了接收器的 App 发 `android.media.tv.action.INITIALIZE_PROGRAMS`，App 收到才建自己的预览频道。第三方桌面没人发，所以这些 App（小米 MFTR0 上的 kino.watch、VK Video、Lampa、TorrServe）的表一直是空的。`Shelf.askToPublish` 用 `queryBroadcastReceivers` 找出所有接收者，给每个发一次显式广播（每个 App 每个版本一次，key 是包名 + 类名 + `lastUpdateTime`，存在 SharedPreferences "shelf"），启动时和装新 App 时做；没有 `READ_TV_LISTINGS` 或关了「App 内容」就不做。小米 MFTR0 上发了以后：kino.watch 3 个频道、7 条继续观看、67 条预览；Lampa 9 个频道、20 条预览；VK Video 1 个频道、5 条继续观看、10 条预览。这些频道的 `browsable` 都是 0（那是系统桌面写的），所以「没有一个可见时全部算可见」那条很重要。
- **系列信息**：`season_display_number`、`episode_display_number` 和（位置、时长算出的）剩余时间显示在标题下面一行；描述为空串（kino.watch 的「我是在看」频道就是）当作没有。
- **大名字**（#348，`NameMode.HeroTitle`）：个性化「App 名称」的第三档，选中 App 的名字用大字放在图标行左上方，图标下面不再写名字。App 内容展开时，大名字上移到卡片行（有标签时在标签）上方；上面放不下时（双排）就淡出。
- **高对比度白框**：卡片也用（`ui/components/FocusRing.kt`，2dp、贴着卡片）。
- **动效**：一个连续的进度值（弹簧，阻尼 1、刚度 140）驱动全部变化：选了大时钟时，大时钟缩向角落并淡出，同一角的小时钟淡入（小时钟本来就在角上，不动）；摘要和卡片行淡入并上移 14dp；背景由 `HomeBackground` 的 Crossfade 交叉淡化。全部只用 `graphicsLayer`，不重排；弹簧可以中途掉头。焦点在 App 之间移动时先等约 0.2 秒再切换，扫过一排 App 不会每个都闪一下；换背景同样等 0.2 秒。离开卡片行时保留选中项，行、摘要和背景始终一致。
- **焦点**：卡片行是一个焦点目标，自己处理方向键和选中项（和标签是否有焦点），不参与 Compose 的二维焦点搜索。文件夹和信号源磁贴没有 App 内容。第一排磁贴用 `focusProperties { up = shelfRequester }` 把「上」指向卡片行，行本身一直在组合里（没打开时透明），所以「上」有地方落脚；在卡片行时，来源 App 的磁贴保持半浮起（`AppTile.shelfOpen`）。**控制中心**：从卡片行最上面一层（标签，没有标签时是卡片）按「上」到右上角的状态胶囊；第一排最右一格按「右」也到胶囊（`AppTile.rowEndRight`）。到胶囊前记下从哪里来（卡片行或磁贴），「下」、返回键、关掉控制中心都回到那里；从卡片行去时，卡片行保持打开（变暗）。
