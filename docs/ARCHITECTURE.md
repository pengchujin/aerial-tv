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
├── settings/
│   └── AerialSettings.kt    Aerial 自己的偏好（SharedPreferences "settings"）：焦点样式 FocusStyle（默认高对比度）、固定到主屏幕的信号源、开机信号源、屏保开始时间、家长密码（SHA-256，解锁 10 分钟或按 HOME 重新上锁）
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
- **分辨率**：系统给 App 的界面多大，就按多大画（`ui/theme/Theme.kt` 的 `canvasDensity`：1080p 界面是 2，4K 界面是 4）。预先画好的位图也按它算：磁贴图是「聚焦放大后的磁贴」的实际像素（1080p 时 352×198，4K 时 704×396），壁纸按屏幕像素解码，界面超过 1080p 时每日精选下载必应的 UHD 图。大部分 4K 电视的界面其实是 1080p（Android TV 的 `config_maxUiWidth` = 1920）；只有厂商放开这个限制的电视才会拿到 4K 界面，这条路径在 Android 15 的 4K 模拟器（`Aerial4KUI`，非 TV 镜像，没有这个限制）上验证过。界面是 1080p 时分两种：
  - 显示模式也是 1080p（家里三台：TCL MT5879、小米 MFTR0、MFFU1 都只报 1920×1080 的模式）：系统按 1080p 合成，由电视硬件放大，App 画得再细也没用。
  - 显示模式是 4K、界面被压到 1080p（诊断报告里的 TCL 85T5M Plus #483、Sony BRAVIA AE2 #433、红米投影仪 5 Pro #504、腾讯极光盒子 7S #269、创维 75A6E #365；Google TV 的标准做法）：4K 模式只说明屏幕和视频是 4K，大多数电视芯片（Sony X90K 的 MT5895、上一代国产主流的 MT9653）界面层仍是 1080p，由硬件放大，画成 4K 只会多 4 倍的绘制量。1.51 按「显示模式至少是界面 2 倍」自动打开，Sony X90K（#645）控制中心和 App 页下滑掉帧、4K 屏保缩在左上角。现在只有芯片声明了 4K 界面层（`ro.feature.graphics_4kui=true`，MT9655 / Pentonic 800 的固件，TCL 75Q10L #607 上看到）、并且面板至少是界面 2 倍（显示模式或 TCL 的 `ro.odm.tcl.panel_resolution`）时，`ui/components/UhdCanvas.kt` 才自动把主页画成 4K（Android 10+），其他电视默认关闭：Compose 照常在 1080p 窗口里排版，按键和焦点不变，只是绘制录进一个放大 2 倍的 RenderNode，由单独的 HardwareRenderer 画进 `setFixedSize(3840, 2160)` 的 SurfaceView（media overlay，窗口本身不画东西）。图片按 2 倍解码（`Wallpapers.screenW/H`、`TileArtFactory`），每日精选因此下载 UHD 图。视频的 SurfaceView 不能放进去（会按 4K 缓冲的像素放大两倍），`FilmSlot` 把它留在窗口里、垫在界面层下面，并在界面层上清出它那一块；不在 4K 画布时 `FilmSlot` 只是包了一层的 SurfaceView。航拍暂停时取的那一帧、控制中心背后的主页截图仍是 1080p。
  - 卡顿退回（`UhdCanvas.Pace`）：启动 10 秒后，看主页动起来的帧（相隔 100ms 以内）：比上一帧晚 1.5 个刷新周期以上、并且是渲染器拖住的（syncAndDraw 占了 1/4 周期以上；主线程别处忙不算），记为晚到。每 120 帧里晚到三分之一，就记下「太慢」，下次启动按 1080p 画。ConfigReceiver `--ez uhd_canvas true|false` 强制开或关（强制开时不看芯片属性，只看面板是否至少是界面 2 倍；Q10L #607 那类显示模式是 1080p、靠 TCL 自己 4K UI 机制的电视，HWC 是否 1:1 送显未实测），`--es uhd_canvas auto` 回到自动并清掉「太慢」。诊断日志的 `display` 行写着 `uhd 2.0 (auto: …)` 或关掉的原因。
  - 验证：4K Google TV 模拟器（`GoogleTV4K`：android-33 google-tv，3840×2160 / 640dpi，`config_maxUiWidth` 把界面压到 1920×1080）上开启（现在没有 `graphics_4kui`，要强制打开；模拟器没有独立的 1080p 界面层，所以它证明不了真电视画 4K 有用），文字、磁贴边框、壁纸明显更清楚，按键、焦点动画、屏保、控制中心正常；`-gpu host` 下 120 帧里晚到 2–8 帧，不退回；`-gpu swiftshader_indirect`（软件渲染）下 85/120 晚到，下次启动退回 1080p。还没在真电视上验证。
- **刷新率**：Android 12 起，如果面板能无黑屏地切到更高的刷新率（`Display.Mode.getAlternativeRefreshRates`），首页会选最高的那个（最高 120Hz），见 `MainActivity.preferSmoothestRefreshRate`。会黑屏的切换一律不做：桌面每次回来都黑一下是不可接受的。
- **网格**（`HomeScreen.GridMetrics`）：5 列，16:9 磁贴，边距 56dp、间距 28dp，第一行在屏幕高 55.5% 处。个性化里选「主屏幕：单排」（`AerialSettings.oneRowHome`，1.23 起默认开；选过「双排」的保持不变）时，第一行下移到底部（屏幕高减一个行距再减 12dp，第二行只露一条边；个性化「第二排：不露出」（`AerialSettings.peekNextRow` 关）时，第二排及以下按滚动进度淡入，停在第一排时完全看不见；第一排再往下放：有底板时底板离屏幕下沿 30dp，和离左右边一样（`PlateMarginBottom`），没底板时磁贴下面留 70dp 给聚焦 App 的名称），好让壁纸里的合照露出来，像 tvOS 全屏 Top Shelf 时那样；时钟大小选「小」（`AerialSettings.largeClock` 关）时，时间和天气是一行小字：时钟选「左上」在左上角（`HomeChrome.CornerClock`），选「右上」在右上角状态按钮的左边。选「大」时是锁屏那样的一块（`HomeChrome.ShelfClock`）：日期、大时钟、天气（当前温度、城市、天气、今天最高最低）三行，左右都和磁贴的边对齐（`AerialSettings.clockRight`）；不论单排还是双排，都固定在状态按钮下面（`GridMetrics.clockY` = 80dp），不再挂在第一排上方（以前单排时浮在屏幕中间，双排时贴着底板）。时间用 Inter 粗体（W700，104sp，字距 -0.04em），日期和天气是 19sp、16sp 的半粗体，农历和天气详情用 72% 白。每行都用 `capBox` 只按大写字母高度排版（从字顶到基线），所以行间距就是看到的距离：日期离数字 16dp、天气离数字 18dp，`clockY` 是日期字顶的位置（80dp）。进网格后的位置和模糊背景不变。磁贴用绝对 `offset` 定位，没有用 Row，所以移动 App 时组件不会重建、焦点不会丢，位置变化由 `animateIntOffsetAsState` 做重排动画。
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
- 一定要装 release 版，并执行 `cmd package compile -m speed -f app.orchard.tv`。debug 版的 Compose 在电视上明显卡顿。**要在 Aerial 启动过一次之后再编译**：小米 MFTR0（Android 14）把 `speed` 当成 `speed-profile`，装完立刻编译时还没有 profile，结果还是 `verify`（解释执行）。`install.sh` 已经改成先启动、等 5 秒再编译。
- **Baseline Profile**（`app/src/main/baseline-prof.txt`）：Aerial 自己的代码和 Compose 全部标成热点。不经过 `install.sh` 的安装（控制中心升级、多屏互动推送、U 盘）不会立刻编译，系统要等后台编译任务（空闲且充电时）才按它编译；小米上实测不会在安装时用 APK 里的 profile（`assets/dexopt/` 和 `assets/art-profile/` 都试过）。编译前后：首页磁贴出来 0.8 秒 → 0.4 秒。诊断报告的 `Compiled` 一行：Android 9+（11 除外）显示 profile 状态，`odex` 大小在所有版本上都能看出编译了没有（只 verify 时几百 KB，`speed` 时约 11 MB）。
- **冷启动不在主线程做的事**：App 列表和信号源同时查（`HomeViewModel.reload`）；磁贴面（连续圆角 + 描边的位图）由 `TileFaces.prepare` 在后台按 5 个一批做，做好前磁贴先画原图（图层的圆角裁剪已经很接近），以前第一次绘制时整页一起在主线程做，小米上约 120 ms；第一排的聚焦尺寸随后也在后台做；字体（Inter 和系统的后备字体链）由 `FontWarmup` 在 `onCreate` 时在后台加载，以前第一次量文字时在主线程做，58 ms → 4 ms。DiagLog 的 `apps in` 一行把耗时拆成 wait / queries / tiles / main。
- **冷启动只先读前几排的磁贴**：磁贴缓存是每个 App 一个文件（PNG），52 个 App 共 23 MB 像素，TCL MT5879 上解码要 4 个线程合计 1.4–1.9 秒 CPU，墙钟 500–680 ms（改存压缩的原始像素只省约 10%，文件大 1.7 倍，没采用）。现在冷启动只把按保存顺序的前 13 个磁贴（文件夹算一个，读它里面的 App）整张读出来（`LayoutStore.leading`），其余只读文件开头的名字和颜色，先画成纯色占位（`TileArt.placeholder`，在屏幕外），首页出来后紧接着在后台全读（DiagLog「N more tiles in … ms」）。占位个数要从这次加载自己的列表里数：冷启动后按主页键，守护会再起一个 MainActivity，两个首页同时加载，起初把个数记在 `AppCatalog` 里，另一个首页的第二遍把它清零，这个首页就不补读，占位一直留在网格里（小米 MFTR0 上 3 次复现 2 次，改后 4 次都正常）。新装、升级后第一次启动（缓存键不匹配）照旧全读。TCL：tiles 约 530 → 165 ms，首页出来 688–790 → 360–600 ms（多数约 370 ms）。
- **控制中心预热**：每个进程一次，首页空闲 3 秒后把面板以 1% 不透明度画 3 帧、不拿焦点（`HomeScreen` 的 `ccWarm`），第一次打开就不再是第一次绘制。小米第一次打开帧耗时中位数 58 → 33 ms（最慢 95 → 38）；TCL 82 → 14–28 ms（最慢 309 → 36–67）。
- **效果（流畅模式）**：个性化 → 主屏幕 →「效果：自动 / 完整 / 流畅」（`AerialSettings.effects`）。流畅：面板用纯色（壁纸磨砂图的平均色，按玻璃的压暗算，不透明；不用每帧的离屏层），聚焦磁贴不画高光、视差和阴影，不切到更高刷新率；Android 7 以前控制中心背后的时钟和磁贴随遮罩淡出（那里没有主页截图，#466）。「自动」在低端电视上是流畅（`DeviceClass`：固件 low-RAM 标志、内存小于 1.8 GB、Android 6 及以下、两核及以下），别的是完整。小米和 TCL 都不算低端。
- **内存**：`onTrimMemory`（RUNNING_LOW 及以上、后台级别，不含每次离开桌面的 UI_HIDDEN）丢掉壁纸缩略图和聚焦尺寸的磁贴面。
- **诊断报告的 `Frames` 一行**（Android 7+，`FrameStats`，FrameMetrics）：启动 5 秒后的最近 600 帧的中位数、p90、p99、最慢，用户报卡顿时带着自己电视的数字。

## 磁贴图（TileArt）

优先级：手机页换的图（`CustomIcons`）最先，其次是下面第 1 类；第 1 类之后、第 2 类之前是**图标库**（`IconLibrary`）。

**图标库**：官网上现成的 16:9 磁贴图，按包名。`https://aerial.qust.me/icons/index.json` 是 `{"v":1,"icons":{"<包名>":"p/<包名>-<sha1 前 8 位>.<png|jpg>"}}`，图片换了文件名就变，所以 `icons/p/*` 永久缓存、`index.json` 不缓存（`site/_headers`）。电视每次加载完 App 后在后台看一眼（`HomeViewModel.refreshIconLibrary`）：索引最多 12 小时读一次，失败 10 分钟内不重试；只下载装了的 App 的图，先写 `.part`、能解码才改名，存在 `files/library/`；新图到了 `IconLibrary.version` 加 1，Home 重新加载。卸载的 App 和被新图替换的旧图会删掉（只动 `files/library/`，户主自己换的图在 `files/icons/`，不受影响）。磁贴缓存的键里带着图的修改时间。个性化 → 主屏幕 →「App 图标」：精选图（默认）/ App 自带（`AerialSettings.iconLibrary`，`settings` 的 `icon_library`）。手机页点开一个 App，图标库有它的图时多一组单选：精选图 / App 自带 /（换过图时）我换的图片，各带一张小图（`library/<包名>` 是下载的图，`tile/<包名>?art=own` 是 `AppCatalog.ownArt` 按 App 自己的横幅或图标画的）；`POST api/icon/pick`（`app`、`pick`=`library`|`own`）删掉手机传的图，记下这个 App 的选择（`icon_library` 偏好的 `use_library` / `use_own`），选的和个性化一样时不记，跟着个性化走。图片不管开关都会下载（只下装了的 App），手机页才能显示和单独选。现在的图是当贝桌面 4.1.7 的「美化图标」（`zmapi.dangbei.net/v2/appinfo?2`，128 个 App，2026-10-26 拉的），放在本机 `local/dangbei-icons/`，**不进版本库**；`tools/deploy-site.sh` 用 `tools/icon-library.py` 把它生成到 `site/icons/`（也不进版本库），没有这个目录时拒绝部署，免得把官网上的图删掉。

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
- 航拍壁纸（壁纸行第一张「航拍」，`Wallpapers.LIVE`，`ui/home/LiveWallpaper.kt`，#216）：停在主屏幕第一排 10 秒没按键，影片在磁贴后面播放（SurfaceView 在窗口下面，`HomeBackground` 的 `cover` 让图片淡出，播放时 Compose 不画全屏内容）。播放时底板不消失，换成「影片底板」（`HomeChrome.Plate` 的 film 部分）：影片在窗口下面没法实时模糊，所以每 2.5 秒取一张 320×180 小图（PixelCopy 能读的电视用 PixelCopy，比如 TCL MT5879，连续 3 次读不到才判定不能读；读不到的用 `FrameReader` 从 1080p 文件解关键帧，比如小米 MFTR0），做成磨砂（`Wallpapers.filmFrost`，第一张出来前用影片起始那一帧的磨砂），以 55% 不透明度叠在影片上，上面再加一层 18% 的黑色烟色和玻璃边缘。底板里能看到影片在动，小图慢几秒看起来就是玻璃的柔化。新小图在旧图上淡入（700ms，两张放在同一个离屏层里再按 55% 叠，免得交接时变厚），烘焙放在后台线程（在绘制时烘焙，小米上每次换图都会卡一帧，一帧 50–70ms）。停下时静态底板（不透明）在它上面淡入。取图会拖慢影片的电视不取（`LiveWallpaper.sampleGlass`）：每次取图前后读播放器的掉帧数，连续两次取图各掉 5 帧以上，本进程就不再取，只要一次会误判（小米 MFTR0 从屏保回来时掉过一次 26 帧，前后几分钟都是 0）。TCL MT5879 上 4K 影片每取一次（PixelCopy 和从文件解关键帧都一样）就停 0.5–0.6 秒、掉 16–17 帧，SurfaceFlinger 里视频层每秒只出 24 帧，不取图时 30 帧；1080p 不受影响。1.52 也有这个问题：影片设成一直播放时从不停下，一直走解文件的路。PixelCopy 在调用它的线程上同步复制，实际的活在渲染线程，主线程画下一帧要等它：TCL 上复制一帧 4K 要 0.6–1.7 秒，期间按的方向键一起涌进来，按一下下键焦点连跳好几行。现在从后台线程调用，并且只要有一次复制超过 150ms，本进程就不再用 PixelCopy，停下时改从文件解这一帧（影片已暂停，解码不影响播放）；TCL 上长按下键的重复键从晚 0.6–1 秒变成晚 20–70ms。未发布的一版曾按影片掉帧自动降档（4K → 1080p → 主屏幕上静态），已撤掉：降到静态后桌面上影片不再动、没有任何提示，户主以为坏了；小米 MFTR0 上那次降档是测试时录屏、测帧耗时引起的掉帧触发的。不取图时（以及「流畅」效果下），底板用影片起始那一帧的磨砂再缩到 16×9 糊成大块颜色（`Wallpapers.useStillFilmFrost`），影片往后走也不会透出错位的轮廓。「我的视频」只用 PixelCopy 取小图，不从文件解（4K HEVC 可能抢第二个解码器），读不到的电视也用糊成大块颜色的起始帧。实测（release）：小米 MFTR0 播放时影片 0 掉帧；每次换图 Compose 画十几帧，每帧约 53ms（这台电视在视频上画任何一帧都要 60–80ms，旧版偶尔出的一帧也一样），22 秒内约 140 帧；TCL MT5879 帧耗时中位 7ms、90 分位 9–10ms；停止取图后 4K 影片 30fps，最长间隔 42–50ms；任何按键、进网格、打开浮层或离开 Aerial 就暂停，这一帧成为壁纸图片（`Wallpapers.useLiveFrame`：照常压暗、做毛玻璃，存成 `files/live-frame.jpg`，下次启动也用它），玻璃底板和网格背景都跟着它；再停 10 秒从这一帧接着播。取到帧时把片子和时间点记在 `wallpaper` 偏好里（`Wallpapers.liveSpot`），播放器释放或重启后也从这一片、这一刻开始（`setMediaItems(…, 0, at)`），静态图和影片的第一帧是同一画面，看起来是一个镜头；没取到帧时不更新，图片和位置仍然对得上。暂停后 2 分钟释放播放器；屏保类型是航拍、屏保出现时影片正在显示（`cover` > 0.5），或焦点在第一排、没有浮层（影片停在静态帧上，比如按返回键），不另开播放器，而是接着放这段影片（静态时从这一帧开始动；10 秒内没动起来就交给屏保自己的影片，`FILM_SAVER_WAIT_MS`）（`HomeScreen` 的 `filmSaver`，`LiveWallpaper.hold`）：磁贴从左到右依次淡出、略往下沉，状态按钮、日期、天气淡出，时间从主屏幕的位置移动并缩放到屏保时钟的位置（`HomeChrome.toSaverClock`，2 秒 `Motion.EaseInOut`），`Screensaver` 只加地点字幕（`FilmLabels`，左右键切片）；按键唤醒时 380ms 收回，影片照常停在这一帧做壁纸。屏保没选过时跟随壁纸（`Wallpapers.screensaver` 由 `chosenScreensaver` 和壁纸算出来，`screensaverFollows`）：航拍壁纸和「跟随 App」配航拍，图片壁纸配照片，并从这张壁纸开始（`screensaverPicture`）；选过一次就固定，没有单独的「跟随」选项。新安装存 `follow`；1.38 之前就在用、没存过选择的存 `aerial`，保持原来的默认（`firstScreensaver`，看 `firstInstallTime == lastUpdateTime`），以后升级不变；1.38 测试版里的「壁纸」类型读成 `follow`。个性化和手机页在跟随时显示说明。照片屏保在第一排、没有浮层、壁纸是图片时一镜到底（`HomeScreen.stillSaver`、`SaverCarry.Still`）：不压暗的原图画在 `HomeBackground` 的图片和底板之间，从和主屏幕一样的构图开始 1.8 秒淡入（压暗退掉）并慢慢推近（`drawIn`：60 秒放大 8% 并向右漂 24dp，再 60 秒回来），底板跟着 `toSaver` 淡出，磁贴和时钟同航拍；18 秒后 `PhotoSaver` 从下一张接着轮播，盖住后主屏幕的图停止绘制（`covered`），屏保自己的时间在主屏幕时钟移到的位置淡入。左右键照常切换。唤醒时 380ms 缩回、压暗回来。单独出现的照片屏保（系统屏保、网格里）也从当前壁纸开始。其他情况（在网格里、类型是照片或时钟）还是原来的黑底淡入。影片停下时底板要和磁贴一起回来：PixelCopy 读不到视频的电视（小米 MFTR0）上，`FrameReader` 在影片开始播放时就用 MediaMetadataRetriever 打开这段片子的 1080p 文件（打开连 Apple、读文件末尾的索引，0.9–1.5 秒，以前每次停下才做，所以要 2.4–6.6 秒），播放中定时取 320×180 的关键帧小图（`GlassSample`，`OPTION_PREVIOUS_SYNC`，约 0.4–0.6 秒，小米实测播放 0 掉帧）。停下时用最近的小图马上做磨砂（`Wallpapers.useLiveFrost`），底板直接画在暂停的影片上（`HomeBackground.filmStopped`，`LiveFilm.stopped`），同时画上静态图会有的压暗（`Images.SHADE`）。`HomeBackground` 分三层：压暗（在图片下面）、图片（换壁纸时交叉淡入）、底板（`Plate`，只有一层）。底板只在影片动起来时让位，所以影片交给静态图时不透明度一直是 1（以前底板放在图片的交叉淡入里，暂停时的底板和图片的底板两层各半透明地交接，中间会透出视频，放大量也跟着变，看起来在闪）；换磨砂时旧的保持不透明，新的在上面淡入；底板看不见时直接换（影片刚停下时，否则会闪出上一次停下的画面颜色），`glassReady` 等两帧再让底板出现。小米实测（临时日志）：唤醒后约 0.3 秒出现，之后到静态图换上，底板不透明度一直是 1.000。然后在后台解精确帧（`OPTION_CLOSEST`，0.4–2.8 秒）做成壁纸图片（磨砂、网格背景、下次启动都用它），但屏幕上不换：暂停的影片继续当壁纸，10 秒没按键就从这一帧接着动；只有打开浮层（控制中心等，影片不在窗口里，得不到模糊）、离开 Aerial、进普通屏保，或停了 `KEEP_STOPPED_MS` 之后，才换成图片（`standIn`）。以前解出来就换，图片和视频不在同一个显示层、画质处理不同，时间点也可能差一两帧，从屏保回来时桌面会轻微一闪、错位。小图第一张在开始播放 0.8 秒后取，之后每 2.5 秒一张。精确帧要从前一个关键帧顺序读解，暂停的播放器还在缓冲时曾超时（`FrameDecoder: Timed-out waiting`），这时退一步用最近的关键帧（`OPTION_CLOSEST_SYNC`），最多差一两秒的镜头移动；都失败才退回上一张图片并换回原来的磨砂。第一次停下时先试 PixelCopy，本进程记住结果（`pixelCopy`）：读不到（小米 MFTR0）以后就不再试；读得到（TCL MT5879）就不再打开文件、也不取小图。屏保出现（不接着放时）或打开别的 App 时立刻释放、不取帧：暂停的播放器仍占着解码器，小米 MFTR0 的屏保因此拿不到解码资源（`vdec resource lost`），1.32 上屏保会降成 1080p，1.33 修好。取帧先用 PixelCopy；小米 MFTR0（MStar）的视频在硬件图层上，PixelCopy 拿到全黑，就用 MediaMetadataRetriever 从同一段的 1080p 文件解出同一时间点（`OPTION_CLOSEST`），实测 1.4 到 3.6 秒，这段时间画面停在暂停的视频上，网格背景先用旧的毛玻璃。最多 4K SDR：电视切进杜比视界模式后，叠在上面的界面也会按杜比的画面设置显示。小米 MFTR0 实测：播放时 Compose 几乎不出帧，左右移动焦点中位 19ms（和静态壁纸一样）。

- 航拍壁纸「一直播放」（`AerialSettings.wallpaperAlwaysMoves`，#360 #456）：第一排上不等 10 秒、按键也不停，影片上加和静态图一样的压暗（`HomeBackground.shadeMoving`），不画玻璃底板；进网格、打开浮层仍会停下取帧。个性化里只有选中航拍或我的视频时才出现「播放：静止时 / 一直」这一行，手机页对应「一直播放」开关。
- 我的视频当壁纸（`Wallpapers.VIDEOS`，`ui/home/VideoWallpaper.kt`，#410 #583）：用手机页上传的屏保视频（`ScreensaverVideos`），静音、裁满全屏、依次循环；播放规则同航拍壁纸。停下时先试 PixelCopy（`copyFrame`），读不到就用 MediaMetadataRetriever 从电视上的文件解同一帧，存成 `files/video-frame.jpg` 做壁纸图片，位置记在 `video_film`/`video_at`（`Wallpapers.videoSpot`）。有视频时壁纸行才出现这张卡片，上传或删除后 `Wallpapers.videosChanged` 刷新；全部删掉时清掉帧文件，选中的话改回每日精选。没有停下前的取帧时，图片是第一段视频的开头一帧。
- 信号源画面当壁纸（`source:<TvInputInfo id>`，`ui/home/SourceWallpaper.kt`，#267，参考 @chikwok 的 tv-preview 分支）：只列 Android 自己列出的 HDMI/DP 输入（id 里有 `/`；TCL、创维等 Aerial 自己拼出来的信号源不行），`HomeViewModel.arrange` 调 `Wallpapers.setSources`。透传 TvView，挂上 0.8 秒后再 tune；`WEAK_SIGNAL`（机顶盒还在开机）按 3/5/10/15/20/30 秒重试，之后放弃；Aerial 不在前台或屏保出现时释放 TvView（屏保的影片和它抢同一条视频通道）。画面不能复制或模糊，图片和磨砂用深色内置图 `hills_night`；有画面时 `cover` = 1，第一排加压暗；进网格时和其他壁纸一样，随滚动盖上磨砂压暗的背景（直通画面是硬件图层，没法真的模糊）。壁纸行里排在最后（`WallpaperRows`），手机页单独一组「信号源」。没有声音（第三方 App 拿不到透传音频）。小米 MFTR0 上验证了无信号时的深色背景和重试；有画面的情况本仓库还没在真机上看到（Apple TV 在待机）。
- 天气开关（`AerialSettings.showWeather`，#472）：主屏两种时钟和时钟屏保都不画天气，天气照常获取。
- 屏保时间（`AerialSettings.screensaverClock`，#240）：航拍、照片屏保上的时间；航拍壁纸接着放成屏保时，主屏时钟移到屏保位置的过程中淡出（`toSaverClock`）。大时钟只有时间移过去：日期和天气按下返回就 400ms 淡掉（`ShelfClock` 的 `away`，`Motion.EaseOut`），这时 2 秒 EaseInOut 的时间才走了约 5%；唤醒时时间单独按 `CLOCK_BACK_MS`（1 秒 `Motion.EaseInOut`，`HomeScreen.clockToSaver`）回到原位，磁贴等仍是 650ms EaseOut；日期和天气等时间走完七成（700ms）再 400ms 淡入。这两行的淡入淡出用 `ModulateAlpha`：默认的离屏缓冲和 `capBox` 一样大，中文上下沿、天气图标和阴影超出盒子，淡的过程中被裁掉，淡完才「展开」，户主在电视上看到过（`screenrecord` 只抓到一两帧中间态，录屏里看不出）。时间以前跟着 650ms EaseOut 回来，唤醒时每帧 45–57ms，2–3 帧就回到原位，看着像跳回去。1.52 时它们跟着 `toSaver` 进度原地淡出，时间从日期中间升过去，加上小米 MFTR0 后台播 4K 影片时主页动画只有 8–10 fps（`gfxinfo` 每帧中位约 100ms，GPU 60–70ms；1.52 正式版、小时钟、不播时钟动画都一样），户主看着像日期和天气被一截截切掉。屏保文字防烧屏（#416）：时间和地名每 2 分钟换一个偏移（横向 ±24dp、纵向 ±14dp），6 秒缓动过去（`saverDrift`），主屏时钟接着当屏保时钟时也跟着偏移；时钟屏保本来每分钟换位置，不受影响。

## 手机配置（PhoneServer）

NanoHTTPD 监听 8790 端口，路径前缀是 8 位随机令牌（每次打开面板都重新生成，二维码里带着）。只在壁纸面板打开时运行，关闭后再保留 5 分钟。接口：`GET /` 页面、`GET api/state`、`GET thumb/<id>`、`POST api/wallpaper|screensaver|delete`（表单）、`POST api/upload`（multipart，可多张）；`GET tile/<包名>`（磁贴现在的样子）、`POST api/icon`（multipart：`app` 加一张图片）、`POST api/icon/reset`、`POST api/weather/city`（`name`）、`POST api/weather/auto`、`POST api/folder/name`（`id`、`name`，Home 活着时经 `AppCatalog.renameFolder` 改名）、`POST api/app/name`（`app`、`name`：App 存进 `AppNames`，信号源存进 `SourceLooks`）、`POST api/source/pin`（`id`、`pinned=1|0`，经 `AppCatalog.pinSource`）、`POST api/layout`（`op` = move / movein / hide / unhide / folder / unfolder / ungroup，加 `key`、`target`、`folder`，经 `AppCatalog.layout`；家长密码开着时不给 unhide，`api/state` 的 `layout.hidden` 也是空的）。页面底部四个标签（壁纸、屏保、主屏幕、更多），操作单张照片或单个磁贴用底部弹层。页面在 `assets/web/index.html`。

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
