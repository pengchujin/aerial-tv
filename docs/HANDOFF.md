# 交接说明

写给接手 Aerial 的人或 AI。先读这份，再读 [ARCHITECTURE.md](ARCHITECTURE.md) 和 [devices/](devices/)。

## 现状（2026-10-23）

- **1.42（未发布）**：户主在小米 MFTR0 上报的几个问题。① 在 Aerial 里按主页键会闪一下小米桌面：Android 12+ 改为 Aerial 在前台时过滤按键（原桌面和其他 App 里仍不过滤，#293 不受影响），户主用真遥控器确认不闪了；同一次主页键的两次窗口事件只拉一次 Aerial。② 硬重启后先显示小米桌面十几秒：设了「开机时打开」时，守护进程一启动就接管，开机 App 直接用它自己的入口打开，屏幕亮起到打开 App 从 13–15 秒变为 7.2 秒。③ 屏保「跳回小米自带的」：户主这台设的是「从不」，小米自己的 8 分钟屏保照样出来；现在选「从不」且永不关屏时 Aerial 在前台保持常亮，户主确认不再出现。④ 开机打开 App「又被拉回」：日志里是有人在 App 里按了主页键，没改。详见 `docs/devices/xiaomi-mitv-mftr0.md`。
- 同一版（PR #364 的基础，#296）：App 内容（Top Shelf）。焦点在第一排某个 App 上时，第一排上方显示这个 App 发布到系统 TvProvider 的「继续观看」和预览频道节目（海报卡片，背景换成选中卡片的图），按上进入、确定打开节目；个性化「App 内容：显示 / 不显示」，默认显示。需要 `READ_TV_LISTINGS`（`tools/install.sh` 和 `agent.md` 用 adb 授予）。细节见 ARCHITECTURE.md「App 内容」和 `docs/specs/top-shelf.md`。最早在 1.22 上做，在 Android TV 12 模拟器（测试发布者、YouTube）和索尼 BRAVIA_4K_VH2（Emby，226 条继续观看、49 条推荐）上验证过；`adb logcat -s Shelf` 每次读取打一行各 App 的发布数量。国产电视（小米、TCL）上的 App 有没有发布内容还没查过。
- 同一版（PR #364，含 #296、#348，未合并前没在我们的电视上看过）：App 内容改成「小箭头 + 按上才打开」，加按频道分的标签、「第 N 季 · 第 M 集 · 还剩 X 分钟」一行，从卡片行最上面一层按「上」或第一排最右一格按「右」去控制中心（Down、Back、关掉控制中心回到来处），以及 Aerial 主动给声明了 `INITIALIZE_PROGRAMS` 的 App 发广播让它们建频道（见 ARCHITECTURE.md「App 内容」）。小米 MFTR0（standard）上用 kino.watch、VK Video、Lampa 的真实频道看过：箭头、打开、标签、卡片、到控制中心和回来；VK Video 有几个图片服务器（93.186.227.x）这台电视连不上，对应卡片显示标题。没在真机上验证：自己建频道之前没有 `INITIALIZE_PROGRAMS` 的行为只在日志里看过（发之前表是空的），SmartTube 没有这个接收器、没发布内容。
- **1.41（versionCode 44），2026-10-23 发布**（[v1.41](https://github.com/pengchujin/aerial-tv/releases/tag/v1.41)）：逐条看了户主回复过的 issue 后的一轮修复。
  - 控制中心背后改成磨砂图淡入（#354）。实时模糊（Android 12+，`BlurEffect`）只要底下有东西在动就整屏重算：小米 MFTR0 上控制中心打开时 GPU 每帧 20–77 ms；改成磨砂图后静止时 0 帧，移动焦点时 GPU 9–15 ms，磨砂盖满后不画主屏幕。个性化里新增「面板背后：磨砂 / 实时模糊」（只在 Android 12+ 显示，默认磨砂）。还剩每次打开第一帧约 160–260 ms（组合 + 布局），没查。
  - 玻璃底板始终是一整行宽，App 少时靠左（#284）；航拍壁纸的绿帧检测放宽（亮处红色不为 0）。
  - 家长密码可以选「4 个方向键」（#368），输入时方向键只记录不移动，Android 8 模拟器上验证了设置、错误、正确三种情况。
  - 小米：有线网络时「网络」先开以太网页（#369）；PatchWall 的儿童模式作为「儿童」磁贴（#332，`android.intent.action.ENTER_KIDS_MODE`，MFTR0 上打开、主页键返回都验证过）；无法打开「安装未知应用」设置页时也照样下载并交给安装程序（#284）。
  - TCL：控制中心滑块不再带 `FLAG_SHOW_UI`（#288，MT5879 上验证功放跟着变、不弹 TCL 音量条）；`TTvPictureManager` 的图形背光做亮度滑块（#363，只在读数 1–100 时用，MT5879 读出 0 所以不变，75V2 未验证）；屏幕关闭时守护不过滤按键（#371，没有在真机上做待机测试）。
  - 信号源有 CEC 设备时打开设备的子输入，让电视通过 CEC 唤醒设备（#259，索尼，未验证）。
  - 屏保时钟跟随个性化的左右位置，退出屏保时主屏幕淡回 650 ms（#357）。
  - Android 8.0 上 `OnBackAnimationCallback` 的 `NoClassDefFoundError` 日志：只是 info 级，进程不会因此退出（模拟器复现），加了 keep 规则后日志消失（#303）。极米上切 App 后进程消失多半是极米自己的后台清理。
  - `ConfigReceiver` 新增 `peek_next_row`、`home_plate`、`show_all_names`。
  - 新增机型笔记 `docs/devices/skyworth-coocaa.md`、`tencent-aurora-box.md`，`hisense-vidaa.md` 补了 #285、#323；`site/agent.md` 第 8 步补了 `settings` 卡死的固件。
- 同一版（1.39）：发版前的检查。#258 原来在拿到 Bing 列表之前就删掉每日精选缓存，而且没有 `market` 文件也算市场变了：升级后第一次如果没联网，选好的每日一图会显示不出来，中文用户也会白白重下 8 张。现在没有文件就当作 `zh-CN`，等新市场的列表拿到再删。lint 找出两个 1.38 以前就有的 Android 5 问题：`GuardSetup.noteRevival` 用了 6.0 才有的 `getSystemService(Class)`（复活时会崩），以及建账号还要 `AUTHENTICATE_ACCOUNTS`（maxSdk 22）。在 Android 5.0 模拟器（`aerial21`）上验证过：账号建出来了；守护被关掉后同步框架把它开回来，没有崩溃。小米 MFTR0（standard）和 TCL MT5879（guard，多屏互动推送）都装了，守护绑定正常，主页键能回到 Aerial（TCL 上用多屏互动注入的 HOME 要等 5 秒，因为不经过按键过滤，真遥控器不受影响）。完整 lint 剩 10 个错误，都是 1.38 以前就有的。
- 同一版（1.39）：多语言。合并了社区的俄语、乌克兰语、哈萨克语翻译（#246–#248）、手机页多语言和右上角语言切换（#252：手机语言不在五种之内时默认英文，以前一律中文）、非中文电视的每日精选用英文说明（#258：`mkt=en-US`，切换时清掉按日期命名的缓存图）。补了三份译文缺的 16 条（1.38 新加的文字）和手机页漏翻的「跟随壁纸」提示；手机页 `.row` 盖掉 `hidden` 导致诊断框一直显示的问题也修了。控制中心的日期改用 `java.text.SimpleDateFormat`（ICU）：经过 desugar 的 java.time 给出的是月份独立形式，俄语成了「23 октябрь」，现在是「23 октября」。小米 MFTR0 上用 `cmd locale set-app-locales app.orchard.tv --locales ru-RU` 看过控制中心、个性化（包括新补的「Никогда」）和日期，测完已恢复成跟随系统，中文日期不变。手机页只用 node 校验了翻译表（五种语言各 81 条），没在手机上打开。译文没有母语者审过。
- **1.40（versionCode 43），2026-10-23 发布**（[v1.40](https://github.com/pengchujin/aerial-tv/releases/tag/v1.40)）：原桌面磁贴（#110、#98）。只声明了 HOME、没有启动器入口的原桌面（TCL 的 `com.tcl.cyberui`，名称「T-UI」，图标是默认机器人）现在也作为磁贴列出来，名称「原桌面」，用 Aerial 自己画的主页图标（`SystemTile.Home`）。守护在运行时用它识别出的原桌面，否则用 priority ≥ 0 的其他 HOME。已有入口的（小米 PatchWall）不变。打开时走 `HomeGuardService.openStockLauncher`。TCL MT5879（guard）上验证过：磁贴出现在网格末尾，点开停在 T-UI，9 秒内不被拉回；按主页键（多屏互动注入）回到 Aerial。小米 MFTR0 上没有多出磁贴。
- **1.39（versionCode 42），2026-10-23 发布**（[v1.39](https://github.com/pengchujin/aerial-tv/releases/tag/v1.39)）：合并的 PR。#260（焦点缩放放到 `focusable` 之后，个性化左右移焦点不再整页抖）、#251（控制中心开着时每 3 秒刷新守护状态和亮度）、#344（控制中心二级页尺寸瞬切、只淡入淡出）、#306（极米 GMUI 的设置和网络走导出的 `com.xgimi.newsettings.SettingService`，作者的分支在组织 fork 里推不了，由我 rebase 到 1.38 的网络候选页列表后并入，并加了第一批 JVM 测试：JUnit + Robolectric，`./gradlew :app:testStandardDebugUnitTest`，11 个通过），文档 #237、#280、#226。小米 MFTR0 上看过合并后的版本：信号源二级页、网络按钮（小米 Wi-Fi 页，返回回到 Aerial）、守护正常；极米没有真机。#346 已关（被 #347 包含）。还开着、已回复修改意见的：#347（小米 TvService 背光：写死的事务号要核对、增量起点、保留背光条兜底、rebase）、#334（Direct Boot 要做数据迁移、PIN 不放 DE）、#233（本机 adbd 授权有风险，建议拆分）、#218（只留图标部分 rebase）、#345（rebase 到网络候选页）；等维护者定方向的：#348、#296、#83、#319，以及新来的 #352（高对比度焦点框贴边变细）、#353（自动轮换壁纸）。
- **1.38（versionCode 41），2026-10-23 发布**（[v1.38](https://github.com/pengchujin/aerial-tv/releases/tag/v1.38)）：第一排玻璃底板上也能显示名称（「App 名称：全部显示」时放在底板里，底板加高）；单排时「第二排：露出一点 / 不露出」（不露出时第一排再往下放，底板离下沿和离左右一样 30dp）；开机时打开某个 App（长按菜单；控制中心显示当前选的是哪个，选择、更换、取消时顶部有提示）；信号源里加「USB」，打开电视自带的 U 盘播放器。小米 MFTR0 上看过底板名称、隐藏第二排、网格排距、长按菜单、USB 打开高清播放器，以及带 `EXTRA_WOKE` 拉起时打开选好的 App；遥控器关机再开机后也打开了选好的 App（2026-10-23 15:12）。TCL 也装了（多屏互动推送，用 ADB 点「同意」），信号源页的 USB 打开了媒体中心。高对比度白框下底板名称的位置没在真机上看过。
- 同一版：issue 修复。**TCL 遥控器音量键一次两格**（户主转来的用户邮件）：Android 11 及以下守护开着按键过滤，TCL MT5879 在过滤前就调了一次音量，过滤后放行又调一次（日志：一按 28→29→30，相隔 25ms）；守护收到音量键时若音量已经变了就不再放行，长按时由守护按系统重复节奏自己调。TCL MT5879（guard）上用真遥控器验证单按恢复一格；**长按没有在真遥控器上验证**（多屏互动 `149>>` 和 `input` 注入的按键不经过过滤器，`sendevent` 被 SELinux 拒绝）。其余：控制中心「网络」打开的页面 2 秒内就关掉时依次换下一个（小米 `MitvWifiActivity` 被 ANSP0 秒杀，#331、#335，换 TV 设置的 `NetworkActivity`；MFTR0 上验证小米页面正常时不触发、快速返回时会换到 NetworkActivity）；MainActivity `excludeFromRecents`（#324，MFTR0：别的 App 在前台时任务中心不再列 Aerial，Aerial 自己在前台时系统仍列出当前任务）；屏保「开始时间」加「从不」，系统屏保是 Aerial 的时一并关掉 `screensaver_enabled`、选回时间再打开（#333，MFTR0 上看到选项，没点）；MFTP0 不写 `picture_backlight`，改弹小米背光条（#284、#335）；解码出来的航拍帧是绿的也不用（#284）；守护过滤按键时双击 HOME 打开最近任务（#335）；Android 6 及以下开机绑定 20 秒没收到任何事件就把无障碍总开关关了再开，开机接管后 30/45 秒再看一次（#318）；`GET_ACCOUNTS`（maxSdk 22）让 Android 5 的同步复活能建账号、`largeHeap`（#328）；TCL m7221 调音量不带 `FLAG_SHOW_UI`（#288）；小米 4A（MiTV4A）HDMI HW7→23（#313）、MFZP0 HDMI HW4–6→23–25 并隐藏 HW7（#311）；打开控制中心时距上次查新版超过 10 分钟就再查（#301）。除注明的以外都没有真机。
- 同一版（小米 MFTR0 装了发布前的 standard release）：个性化整理。壁纸并成一行（航拍、每日一图、我的照片、6 张内置、跟随 App），主屏幕一组拆成三张卡片。航拍壁纸记住停下时的片子和时间点，播放器释放后也从这一帧接着播（小米实测：强停重启后从 27560ms 开始，静态图和视频第一帧一致）。屏保是航拍、影片正在播或停在第一排的静态帧上时，屏保直接接着放：磁贴淡出、时钟移到右上角、加地点字幕，唤醒后收回；影片停下（包括从屏保唤醒）时，磨砂底板和压暗立刻出现：播放时预先打开片子文件、每 2.5 秒备一张关键帧小图，停下就用它做磨砂，精确帧在后台解好再换上（画面一样）。小米实测：静态时按返回键接着放当前影片；三轮屏保唤醒，底板两次即时、一次 0.4 秒，精确帧 0.8–1.8 秒，播放 0 掉帧；精确帧超时过一次（换成关键帧兜底后没再出现）。底板闪烁（两层底板交接时半透明、换磨砂时闪出旧颜色）已改成单独一层，小米上用临时日志确认交接过程中不透明度一直是 1。从屏保回来时桌面轻微一闪、错位（解出来的图片换掉暂停的视频）：现在暂停的视频直接当壁纸，打开浮层、离开 Aerial 或 2 分钟后才换图；小米实测唤醒后不换图，10 秒后从暂停那一帧（25141ms）接着播，打开控制中心时换图。户主测试时自己把小米的壁纸换成了「航拍」、底板改成「无」、时钟改成小号，都保持不动。TCL MT5879（guard，多屏互动推送，ADB 点「同意」）也测过：PixelCopy 能读到影片，停下后不走解码；从停下的那一帧接着播；屏保改成航拍后接着放影片、时钟移到右上角、唤醒后底板立刻出现；三段播放和一段屏保都是 0 掉帧（有一轮掉了 5%，那时还在 TCL 上开文件取小图，改成 PixelCopy 能用就不取之后没再出现，但不能确定就是它）。测试时临时改了 TCL 的壁纸和屏保类型，已改回「沙丘 · 金色」和「时钟」。
- 同一版（未提交）：屏保没选过时跟随壁纸（航拍壁纸配航拍，图片壁纸配照片），新安装默认跟随，老用户没选过的保持航拍；照片屏保从当前壁纸一镜到底开始（原图慢慢推近），18 秒后接着轮播。小米实测：升级后仍是「航拍」；临时改成「每日一图 + 照片」，返回键进屏保一镜到底，18 秒后换到户主的照片，唤醒复原；测完改回「航拍 + 航拍」。GoogleTV 模拟器全新安装：每日一图壁纸时勾的是「照片」。TCL（户主测试时选过测试版的「壁纸」类型、壁纸换成了航拍）：读成跟随，进屏保接着播影片。没测：系统屏保（AerialDream）里从壁纸开始的照片屏保、TCL 上的图片壁纸一镜到底、到点自动进入。从屏保按「下」唤醒时，TCL 上焦点会跟着翻进 App 列表（小米没有）：怀疑是红外遥控器把一次按键发成两次；现在唤醒的那个键直到抬起前都被吞掉，唤醒后 400ms 内的其他按键也当作唤醒（`MainActivity.wakeQuietUntil`；系统屏保 AerialDream 结束时记下 `dreamWokeAt`，主屏幕同样处理）。两台上用 `input keyevent DPAD_DOWN DPAD_DOWN` 模拟连发，第二下被忽略；用 TCL 真遥控器没验证。测试时把小米的壁纸临时换成航拍，已改回户主原来的「每日一图」。没测：TCL、到点自动进屏保（只测了返回键进入）、影片 10 秒没动起来时退回屏保自己的影片、时钟小号/右上时的移动轨迹、过渡中间帧（小米录屏只录到几帧）。小米 `screencap` 截视频层时偶尔出现整屏彩色噪点，原来的航拍壁纸（没进屏保）也一样，判断是截图的问题；户主看到的画面没确认。唤醒时若 MediaMetadataRetriever 解帧失败（小米上一次在 233 秒处，7.7 秒后返回空），壁纸会退回上一张图，是原有限制。
- 同一版（两台都装了 release 版）：图块清晰度（#307）。banner 是位图时保留原图像素（最多 2 倍磁贴宽），只裁成 16:9，不再先画成 352×198；每个图块有两张圆角图，静止尺寸一张、焦点尺寸一张（第一次聚焦时才生成），放大过半时换图，两种状态都是一个像素对一个像素；焦点放大改成宽高各自按整数像素放大（294→338、165→189，`AppTile.focusedPx`），否则 294×1.15=338.1 会让整张图跨像素边界重采样，几乎抵消掉前面的改进。右上角控制中心图标照 SF Symbols 的 switch.2 重画（上面描边加实心圆点、下面实心加镂空圆点，坐标落在整数像素上）。小米 MFTR0 实测（拉普拉斯方差，越大越清楚）：聚焦的奇异果 265→636，静止的各图块 +43–62%；TCL MT5879 静止图块 +0–58%，聚焦图块 338px 宽。导航时 GPU 中位 8–9ms（不变），CPU 帧耗时没有旧版对照。磁盘缓存的 key 含 versionCode，所以改 `TileArt` 后不加版本号，电视会读回旧图。
- 版本 **1.37（versionCode 39）**，2026-10-23 发布（[v1.37](https://github.com/pengchujin/aerial-tv/releases/tag/v1.37)），两台都装了：冷启动直接用上次的结果。压暗和毛玻璃之后的桌面背景存在 `files/backdrop/`（`home.jpg`、`frost.png`、`key`，key 由壁纸来源、文件修改时间、屏幕尺寸和 versionCode 组成），启动时在主线程读回，窗口背景也先用它、第一帧后拿掉；磁贴图存在 `cache/tiles/<包名>.tile`（`apps/TileDiskCache.kt`，key 含 App 更新时间、磁贴尺寸、图标风格、语言、versionCode）；主题打开 `windowDisablePreview`，不再显示黑色启动窗口；HOME 广播在守护 1 秒内已拉过 Aerial 时不再重复拉起。实测：小米 MFTR0（24 个 App）磁贴 1330→约 450ms，第一帧就是正确的壁纸（以前黑 0.5 秒、再闪 HDMI 磁贴的蓝色环境光，约 2.3 秒才完整）；TCL MT5879（51 个 App）磁贴 1420→约 500–600ms，黑屏约 1.3 秒→没有（上一个画面停约 0.45 秒后直接是壁纸）。导航时 GPU 帧耗时不变（MFTR0 9ms），位图内存不变。装 1.37 后小米冷启动磁贴约 500ms、TCL 约 600–770ms（升级后第一次启动版本号变了，缓存全部重画，TCL 那次 6 秒）。TCL 用多屏互动推送装完后前台一度是 `com.tcl.tv/.TVActivity`（Aerial 的开机信号源是关的，日志被清掉了没查到是谁打开的），按 HOME 回到 Aerial。没测：换壁纸后重新烘焙（要改户主的壁纸）、「跟随 App」和航拍壁纸、低配电视上关掉启动窗口后「按下后原画面多停一会儿」的观感。守护绑定时 Aerial 进程优先级是 100（两台都是），平时按 HOME 是热启动，冷启动只在开机、强停后复活、升级之后出现。

- 1.36（versionCode 38），2026-10-23 发布（[v1.36](https://github.com/pengchujin/aerial-tv/releases/tag/v1.36)），两台都装了：Android 12 以上守护完全不过滤按键，原桌面里的 HOME 改由 `CLOSE_SYSTEM_DIALOGS`（reason=homekey）广播接住，前台是原桌面或未知时拉回 Aerial（从「桌面」磁贴进 PatchWall 后方向键一次跳两三格，#293）；小米电视的模拟/数字调谐器按名称里的 ATV / DTV 选播放器（Amlogic 的 ANSM0 是 HW0=ATV、HW10=DTV，以前对调，#291）；微鲸 WTV55K1J 的信号源（`com.whaley.tv.tvplayer.ui` 的 `START_TV_PLAYER`，HDMI 1–3、AV）和设置入口 `com.helios.setting`（#292，报告人实测的接口，没有真机）；每日精选过了 0 点就查新图（`DATE_CHANGED`、校时），当天的图还没出来就 30 分钟后再查（#286）。小米 MFTR0（standard）上验证过：进原桌面后日志没有 `filtering keys`，HOME 约 0.5 秒回到 Aerial，强停后前台未知时 HOME 也回来，信号源列表不变；方向键跳格本身用 `input` 注入复现不了（注入的按键单步正常，`/dev/input` 没有写权限），要等报告人确认。TCL MT5879（guard，多屏互动推送，ADB 点「同意」）上 HOME 2 秒内回到 Aerial。

- 1.35（versionCode 37），2026-10-23 发布（[v1.35](https://github.com/pengchujin/aerial-tv/releases/tag/v1.35)），两台都装了：Aerial 是默认桌面时守护不再过滤按键（小米 ASTP0 上过滤后的音量键让 MitvFreezer 在 system_server 里空指针、整机重启，#249；#115 #268 也可能受益）；复活次数只算本次开机以来的（#154 #261）；航拍壁纸停下时 PixelCopy 拷出绿色或纯色也改为解码（#256）；腾讯极光盒子 `com.ktcp.launcher` 的 `LauncherHomeSingleTaskActivity` 当原桌面（#269 #270）；海信语音桌面 `com.ju.secondscreen.HomeActivity`（优先级 -150）当原桌面、没有输入时控制中心打开海信信号源面板（#261）；「电视」按键音改用 `playSoundEffect(effect, -1f)` 并自己检查按键音开关（海信单参数版只收 10–18，#262）；`adjustStreamVolume` 抛 SecurityException 时改用 `setStreamVolume`（TCL m7642，#15）；ColorOS TV `tv_power_mode=QUICK` 时守护不拉回（OPPO S1，#22）；PPTV、极米的设置/网络/图像入口，网络按钮最后退到 TCL 设置（#264 #241 #15）。小米 MFTR0（standard）和 TCL MT5879（guard，多屏互动推送，ADB 点「同意」）回归过首页、HOME 拉回、控制中心音量（13→14→13 已复原）；TCL 刚装完时 `input keyevent HOME` 有一次要 5 秒才回到 Aerial（守护过滤还没生效），强停重启后日志有 `filtering keys`，HOME 立刻回来，原因没查清；涉及的机型都没有真机，按键过滤那条在两台上都走不到（MFTR0 是 Android 14、TCL guard 不是默认桌面），模拟器守护绑不上。
- 1.34（versionCode 36），2026-10-23 发布（[v1.34](https://github.com/pengchujin/aerial-tv/releases/tag/v1.34)）：第一排玻璃底板上不显示 App 名称（和 tvOS 26 一样，下面几排和不显示底板时照旧）；信号源磁贴的图标换成 Material Symbols Rounded 实心图标（和系统磁贴同一套，Switch 按同样风格手画），选了「接入的设备」的磁贴只显示设备图标、不写名字和端口，默认 HDMI 磁贴保留图标和「HDMI 1」。小米 MFTR0（standard）上看过默认 HDMI、Apple TV、电脑、PlayStation，测完 HDMI 1 改回默认；TCL 没装 1.34。
- 1.33（versionCode 35），2026-10-22 发布（[v1.33](https://github.com/pengchujin/aerial-tv/releases/tag/v1.33)）：选了航拍壁纸时，屏保出现前立刻释放壁纸的播放器（1.32 上它占着解码器，小米 MFTR0 的杜比视界屏保报 `vdec resource lost`，降成 1080p）。两台都装了。
- 1.32（versionCode 34），2026-10-22 发布（[v1.32](https://github.com/pengchujin/aerial-tv/releases/tag/v1.32)），小米 MFTR0（standard）和 TCL MT5879（guard，多屏互动推送，ADB 点「同意」）都装了：航拍壁纸（壁纸选「航拍」，停在主屏幕 10 秒后播放，按键停在当前帧并成为壁纸，#216，小米 MFTR0 验证过，测完改回户主的「山丘 · 黎明」；`files/live-frame.jpg` 是测试留下的暂停帧，只有选「航拍」时用到）。（2026-10-22 晚）第一排选中的 App 也显示名称（#196 #199 #219）；每行最右一格按右键不再跳到右上角（#211）；守护不把电视播放器（`com.tcl.tv` 等）当原桌面（TCL 85G60E 切信号源被拉回，#223，没有真机验证）；`CATEGORY_LAUNCHER` 入口，只装 APK 时首次打开弹「设为主屏幕」引导（#207 #213）；信号源磁贴选接入的设备（8 种图标）、手机页起名字和传图（#172 #210）；航拍画质 自动/1080p/4K/4K 杜比视界（#198 #217）；Issue 模板改成问题反馈 / 机型适配 / 功能建议 / 实测可用四种，关掉空白 issue。这批在小米 MFTR0（standard）上逐项验证过，测完 HDMI 磁贴改回默认、画质改回自动；TCL 没装。此前：控制中心升级页加「下载并安装」（下载官网 APK，交给系统安装器；装不上的电视显示二维码，打开 `site/update/` 的升级说明页），诊断报告（手机页「反馈问题」、`tools/report.sh`、`.github/ISSUE_TEMPLATE/device.yml`）。小米 MFTR0 和 TCL MT5879 上用测试包验证过，细节见 ARCHITECTURE.md「升级」「诊断报告」。发版时要跑 `tools/deploy-site.sh`，否则二维码打开的 `aerial.qust.me/update/` 和 `tools/report.sh` 都还不存在。文案里写的是「1.32 起」，版本号不是 1.32 时要改 README、官网、`agent.md`、`site/update/`。
- 1.31（versionCode 33），2026-10-22 发布（[v1.31](https://github.com/pengchujin/aerial-tv/releases/tag/v1.31)）：玻璃面板（控制中心、个性化、长按菜单等）改成在面板大小的图层里先画连续曲率形状、再用 SrcIn 叠毛玻璃，一次只用一个位图 shader；1.27–1.30 用玻璃和形状遮罩的 ComposeShader，在 Redmi A65 2025（MiTV-ASTP0，Android 9，Mali-G31）上画成不透明的深色块（用户私信反馈），Android 9 以前的面板现在也有连续圆角；个性化选「时钟大小：大」时，日期、大时钟和天气（加天气状况和今天最高最低温）放在状态栏下面左上或右上，单排双排都一样（以前挂在第一排上方）；`ConfigReceiver` 可以用 `large_clock`、`clock_right`、`one_row_home` 切首页布局方便截图。小米 MiTV-MFTR0（`standard`）验证了控制中心、个性化面板和大时钟三种布局，控制中心里导航 GPU 中位 10.8ms（1.30 是 11.1ms），户主设置测完改回（小时钟、左上、单排）；TCL MT5879（`guard`，多屏互动推送，ADB 点「同意」）装的是 1.31，首页、控制中心回归通过。ASTP0 没有真机，深色块的修复还没在它上面确认。
- 1.30（versionCode 32），2026-10-22 发布（[v1.30](https://github.com/pengchujin/aerial-tv/releases/tag/v1.30)）：手机页「我的照片」加「每天换一张」（电视上 #27 就有，手机页没有，#194）；启动时清理上传中断留下的临时文件（#193，其余缓存是航拍开场预取，上限 48MB）；调不了背光的小米电视上「亮度」发广播弹出小米设置自己的背光条（`com.xiaomi.mitv.settings.BACKLIGHT_POPUP`，按接收器是否存在检测，PR #85 的发现；MFTR0 上收回写设置权限模拟验证过）；小米电视 4（MiTV4-ANSM0）HDMI HW7 → tvplayer 23（#191，没有真机）。小米 MiTV-MFTR0（`standard`）、TCL MT5879（`guard`）装的是 1.30。小米上 `files/tiles/` 里的 743MB 是调磁贴用的样本 APK 和对比图，等户主说调完再删。
- 1.29（versionCode 31），2026-10-22 发布（[v1.29](https://github.com/pengchujin/aerial-tv/releases/tag/v1.29)）：第一排玻璃底板改成 CPU 烘焙成一张位图再画（1.27 的嵌套 ComposeShader 在小米 ASTP0 / Mali-G31 等老 GPU 上画成纯白，#183 #181 #176），Android 9 以前的面板不再用两个位图 shader 合成；天气先查内置的国内城市表（`assets/cities-cn.tsv`，GeoNames，`tools/make-cities.py`），Open-Meteo 搜不到商丘、把安庆定到江苏（#187），以前选错的自动纠正；守护拦主页键时长按打开最近任务（#185，没用实体遥控器验证）；安装脚本判断守护绑定也认类名（ASTP0 上标签显示成类名）。小米 MiTV-MFTR0（`standard`）回归、底板和 1.28 逐像素对比、导航 GPU 中位 8ms；TCL MT5879（`guard`）装的是 1.29。白底板在模拟器上复现不了，没有 ASTP0 真机。
- 1.28（versionCode 30），2026-10-22 发布（[v1.28](https://github.com/pengchujin/aerial-tv/releases/tag/v1.28)）：亮度最低一档是 1（小米写 0 会关背光黑屏，#174）；按键声音「电视 / Aerial / 关」三选一，系统按键音关着的电视默认用 Aerial 自己的声音（`res/raw/ui_*` 加回来了，#169）；个性化加「第一排底板：玻璃 / 不显示」（#151）；首页壁纸烘焙的黑色渐变减轻（`Images.shadeForHome`，#157）；BOOT_COMPLETED 时守护已在跑也再看一次前台（MTEQ0，#154）；创维切信号源经过原桌面时守护延后再看（#155）；康佳原生信号源（PR #125）；调不了亮度时那一行写「在图像设置里调 ›」（#130）。小米 MiTV-MFTR0（`standard`）逐项验证过，户主设置测完都改回（亮度 21、底板玻璃、按键音电视、屏保时钟）；TCL MT5879（`guard`，多屏互动推送，ADB 点「同意」）装的是 1.28，首页、控制中心回归通过。MTEQ0、创维、康佳没有真机。
- 1.27（versionCode 29），2026-10-22 发布（[v1.27](https://github.com/pengchujin/aerial-tv/releases/tag/v1.27)）：磁贴、Dock 底板、玻璃面板和高对比度白框改成连续曲率圆角，形状在 CPU 上按尺寸烘焙成位图，GPU 只画矩形和贴图（直接让 GPU 画路径在小米上从 11ms 涨到 57–72ms，见 `ui/components/SmoothCorners.kt`）；修好白框淡入时四角的小点；个性化加「时钟大小」（默认小）和「App 名称」（1.23 去掉的，沿用原设置）；所有 App 都在第一排时放低；选中磁贴有烘焙的阴影；按键音改用电视系统自带的；文件夹（长按菜单「移到文件夹」）。小米 MiTV-MFTR0（`standard`）逐项验证过，导航时 GPU 中位约 9ms（1.26 约 10.5ms）；TCL MT5879 国行（`guard`，多屏互动推送，ADB 点「同意」）装的是 1.27，首页、底板、控制中心回归通过。「所有 App 都在第一排」、文件夹之间移动、在文件夹里打开 App 还没在真机上试过。TvProvider 的「继续观看」查过做不了：读别的 App 的频道要 `ACCESS_ALL_EPG_DATA`（signature|privileged），两台电视上都只有 TvProvider 自己有；奇异果、极光自己的播放记录接口只对合作厂商开放（开关、appid、加密）。
- 1.26（versionCode 28），2026-10-22 发布（[v1.26](https://github.com/pengchujin/aerial-tv/releases/tag/v1.26)）：检查新版本从每天一次改成每 3 小时一次，联网时也查（一天发了 1.22–1.25 四个版，早上查过的电视当天看不到后面的版本）。在小米上用一个自称 1.24 的临时包确认过控制中心会提示「有新版本 1.25」。
- 1.25（versionCode 27），2026-10-22 发布（[v1.25](https://github.com/pengchujin/aerial-tv/releases/tag/v1.25)）：守护认得酷开用 `smartdevice.intent.category.LITE_HOME` 的原桌面（#122）和乐视 `ExternalJumpActivity` 背后真正显示的 `ui.activity.Launcher`（#121）；每日精选和天气在开机没网时取不到的，联网后立刻重试（以前要等 6 小时，#129）；`restore.sh` 在海信上能还原 1.15 以前手动改的 `persist.vt.use_home_pkg`（#40）。两台当时装的是 1.25，回归都过；酷开、乐视、海信没有真机，联网重试没有在真机上断网验证。
- 1.24（versionCode 26），2026-10-22 发布（[v1.24](https://github.com/pengchujin/aerial-tv/releases/tag/v1.24)）：内置壁纸换成 14 张 Mac 风格的原创图（山丘、沙丘、海、波浪、光晕，在电视上按实际分辨率画），选过旧光场的自动换成颜色最接近的新图。壁纸在小米上逐张看过。官网和 README 的首页、聚焦、网格、长按菜单、控制中心、个性化截图换成了 1.24 在小米上拍的（每张换了一张壁纸，拍完改回户主选的「山丘 · 黎明」），个性化截图里的二维码、局域网地址和「我的照片」已模糊。
- 1.23（versionCode 25），2026-10-22 发布（[v1.23](https://github.com/pengchujin/aerial-tv/releases/tag/v1.23)）：照 tvOS 26 改外观。第一排下面垫 Liquid Glass 底板（用预先模糊好的壁纸做材质，不做实时模糊），进网格时淡出、回第一排时淡入；控制中心、个性化、长按菜单、隐藏的 App、家长密码都改成玻璃面板；焦点放大不再回弹，高对比度白框不会同时出现两个；第一排不显示名称，网格里只显示选中的（「App 名称」设置去掉）；新增按键声音（自己合成，个性化里可关）；图标换成 Material Symbols Rounded；主屏幕默认单排（选过双排的不变）；「隐藏的 App」从控制中心挪进个性化；右上角按钮只放网络和控制中心，时间不再在两处同时出现。都在小米 MiTV-MFTR0（`standard`）上逐项验证过，GPU 90 分位约 15–16ms（之前 12–13ms）；TCL MT5879 国行（`guard`）随后也装了 1.23（多屏互动推送，ADB 点「同意」），首页、玻璃底板、控制中心回归通过，导航时 GPU 90 分位 11ms；TCL 没选过布局，所以现在是默认的单排。
- 1.22（versionCode 24），2026-10-21 发布（[v1.22](https://github.com/pengchujin/aerial-tv/releases/tag/v1.22)）：个性化 → 屏幕保护程序里加「返回键：开始屏保 / 不响应」（#94）；待机唤醒时如果 Aerial 已经在前台（守护先把它拉回来了，或进程是唤醒后才复活的），守护补发一次唤醒标记，「开机时打开」的信号源照样打开（#111，小米 MiTV-MTEQ0 上报告人验证过同样的改法）；海信 VIDAA（有 `com.hisense.vidaaassistant` 或 `com.jamdeo.tv.vod`）上守护不再过滤按键，音量键交回海信固件（#7，没有海信真机）；Android TV 原生设置侧栏（CM311-1）不再另开任务（PR #90）。小米 MiTV-MFTR0（`standard`）和 TCL MT5879 国行（`guard`，多屏互动推送，ADB 点「同意」）装的都是 1.22，两台回归都过，返回键开关在小米上逐项验证过，测完已改回默认（开始屏保）。
- 1.21（versionCode 23）：系统不许通知监听的电视（低内存 Android 9，小米 MFTP0）上，待机强停后改由同步适配器（`guard/RevivalSync.kt`）约 30–60 秒后拉回 Aerial 并重开守护和 ADB，详见 [devices/xiaomi-mitv-mftp0.md](devices/xiaomi-mitv-mftp0.md)；**MFTP0 本机还没用 1.21 实测**（#77 有用户在 1.20 上抓到小米唤醒时 `restartHomeStandby` 强停 Aerial 的日志）。
- 1.20（versionCode 22）：个性化里加「主屏幕：标准 / 只显示一排」（只显示一排时第一排放到屏幕下方，不显示大时钟，把屏幕让给壁纸）和「时钟：左上 / 右上」；个性化面板改成 tvOS 设置那样的分组卡片。小米 MiTV-MFTR0（`standard`）和 TCL MT5879 国行（`guard`，多屏互动推送，用 ADB 点的「同意」）装的都是 1.20；新功能只在小米上逐项验证过（两种布局、两个时钟位置、面板各组），两台电视上的这两项都保持默认（标准、左上）。1.19 的修复（#82、#95、#87）见 v1.19 的 Release 说明，1.18 的内容见 v1.18。酷开、乐视、OPPO K9、小米 ANTL0 / Android 9 相关的改动没有真机，只按用户日志改。海信 VIDAA 的主页键见 [devices/hisense-vidaa.md](devices/hisense-vidaa.md)。主要改动见 Release 说明和 ARCHITECTURE.md。
- 具体 IP、每台电视上改过的设置、户主自己的数据：见 **`local/TVS.md`**（不在版本库，需要单独私下拿到）。
- 开源在 https://github.com/pengchujin/aerial-tv（MIT）。官网在 `site/`，已部署到 https://aerial.qust.me（Cloudflare Worker 静态资源）。还没有自动化测试，以下结论都来自真机操作和日志。

### 真机验证过的功能

| 功能 | 小米 | TCL |
|---|---|---|
| 安装 | `adb install` | 多屏互动推送，电视上点一次「同意」（没有多屏互动时用当贝） |
| 接管 HOME（按 HOME 会闪一下原桌面，约 0.5 秒回到 Aerial） | ✅（1.11 起，adb 测） | 未测 1.11 |
| 从「桌面」磁贴进入原桌面（小米 PatchWall 的爱奇艺、芒果等内容） | ✅（1.11 起） | 未测 |
| 待机唤醒后仍是 Aerial | ✅（靠复活机制；同步适配器这条备用路径在 MFTR0 上关掉通知监听后测过，见 [MFTP0](devices/xiaomi-mitv-mftp0.md)） | ✅ |
| 信号源磁贴（HDMI 等加到主屏幕，一键切换） | ✅（1.13） | 未测 |
| 断电重启后仍是 Aerial | ✅（2026-10-20 实测） | ✅（要放行 TCL 的 `APP_AUTO_START`，2026-10-20 实测） |
| ADB 被关后自动打开 | ✅（小米会主动关） | 不需要（不会关） |
| 首页、网格滚动、焦点、长按菜单、移动 | ✅ | ✅（只验证了显示和守护） |
| 小米的应用中心 / 高清播放器 / 米家显示在桌面 | ✅（1.7） | 不适用 |
| 控制中心 | ✅ | ✅（「设置」「网络」1.7 修好） |
| 音量 | ✅ | ✅（需要 `FLAG_SHOW_UI`） |
| 亮度 | ✅ 滑块 | 打开 TCL 图像面板 |
| 每日精选 / 内置壁纸 | ✅ | ✅ |
| 手机扫码上传与选择 | ✅ | 未单独测（代码相同） |
| 航拍屏保 | ✅ | ✅（截图截不到视频，字幕正常） |
| 系统级屏保（Dream） | 不适用（小米不用 Dream） | ✅ |
| 返回键进入屏保 | ✅ | ✅ |
| 焦点样式（高对比度白边 / 标准，控制中心与手机页切换） | ✅ 1.9 | 未装 1.9 |
| 控制中心「下载并安装」（应用内升级） | ✅（未发版代码，2026-10-22：下载、「继续更新」、「完成」后回到 Aerial；取消、没开安装权限、签名不同都测过） | ✅（同日，1.30→官方 1.31：TCL 校验放行，系统安装器点「安装」，「暂不体验」后守护拉回） |
| 覆盖安装时不打断正在看的信号源 / App | ✅ 1.9（装在 HDMI 播放中） | 未装 1.9 |
| 屏保里左右键切换上一段/下一段（时钟模式除外，其他键退出） | ✅ 航拍（1.7） | 未测 |
| 帧率 | 导航时中位数约 18ms | 未测 |
| 触屏（#60）：点按、长按菜单、拖动网格、控制中心和个性化可点、点屏保唤醒 | ✅ `adb shell input` 模拟（小米没有触摸屏）；移动模式和手势返回在 Android 15 手机模拟器上测过 | 未测 |

**Android 5.0–7.1 的老电视**（1.9 起 minSdk 21，Compose 降到 1.9.5）：小米上装了这一版回归过（首页、焦点、控制中心、壁纸页和手机页、航拍屏保走 HTTPS 正常）。老系统没有真机，在 Android 5.0.2 模拟器（`aerial21`，arm64）上验证过：桌面、焦点、控制中心（音量、亮度）、壁纸页和二维码、手机配置页、守护版接管 HOME。和新系统的差别：

- 界面字体用系统的 Roboto（Inter 的字重靠可变字体，Android 8.0 才支持）；App 图标用 PNG（自适应图标同理）。
- Android 5.0 上无障碍服务接不到 HOME 键，守护版靠「原桌面一出现就拉回」，会闪一下原桌面。
- 老系统不信任 Apple 的根证书，航拍视频的 HTTPS 会失败，屏保会自动改用 HTTP（`res/xml/network_security_config.xml` 只对 `sylvan.apple.com` 放行明文）。模拟器的软件解码器只支持 H.264 Baseline，解不了 Apple 的视频，所以航拍画面本身还没在 5.x 上看到过，要等真机。
- Android 4.4 做不到：Compose 最低要求 Android 5.0。

## 环境

- macOS，JDK 17（`/opt/homebrew/opt/openjdk@17`），Android SDK 在 `~/Library/Android/sdk`，Gradle Wrapper 8.14.3，AGP 8.12，Kotlin 2.2.10。
- **签名**：`local/signing.keystore` 加 `local/signing.properties`，`app/build.gradle.kts` 会优先使用它。电视上的版本都是用这把钥匙签的（SHA-256 `e08962dc…6685`）。没有它也能编译，但会回落到本机的 debug key，装不上电视（签名不一致）。
- **ADB 连电视**需要关闭 Claude Code 的沙箱（局域网访问被拦）。电视第一次连接时要在电视上点「允许 USB 调试」。

## 日常操作

```bash
source local/tvs.env                                    # XIAOMI_TV / TCL_TV（模板：tools/tvs.env.example）
./gradlew assembleStandardRelease assembleGuardRelease  # 两个版本一起编译
BUILD=1 KEEP_ADB=1 TV=$XIAOMI_TV tools/install.sh       # 小米：全自动（BUILD=1 用本地编译；不加则下载 GitHub 最新 Release）
BUILD=1 KEEP_ADB=1 TV=$TCL_TV tools/install.sh guard    # TCL：通过多屏互动推送，在电视上选「同意」
TV=<ip> tools/restore.sh [--uninstall]                  # 撤销所有授权和设置
```

多屏互动推送不要求 versionCode 变大，但回退到当贝时，当贝会隐藏不比已装版本新的 APK。所以发版时照样给 `versionCode` 加 1，两台电视的版本号保持一致。

只改了代码、想快速更新小米时：

```bash
adb -s $XIAOMI_TV:5555 install -r app/build/outputs/apk/standard/release/app-standard-release.apk
adb -s $XIAOMI_TV:5555 shell cmd package compile -m speed -f app.orchard.tv
```

覆盖安装会保留之前的授权；守护服务由 App 启动时自己恢复。

## 发版

1. `app/build.gradle.kts` 里 `versionCode` 加 1，并更新 `versionName`。
2. `./gradlew assembleStandardRelease assembleGuardRelease`（必须有 `local/signing.*`，否则签名不一致，用户无法覆盖安装）。
3. 发布时 APK 要改名为 `aerial-standard.apk` 和 `aerial-guard.apk`，`tools/install.sh` 和官网都按这两个名字从 `releases/latest/download/` 下载：
   ```bash
   cp app/build/outputs/apk/standard/release/app-standard-release.apk /tmp/aerial-standard.apk
   cp app/build/outputs/apk/guard/release/app-guard-release.apk /tmp/aerial-guard.apk
   gh release create vX.Y /tmp/aerial-standard.apk /tmp/aerial-guard.apk --title "Aerial X.Y" --notes "..."
   ```
4. 运行 `tools/deploy-site.sh`（在 main 上、`tools/` 已提交）。国内访问 GitHub 慢或打不开，所以官网自己放一份最新 Release 的 APK 和安装脚本：脚本把 Release 的两个 APK 下载到 `site/download/`，把 `tools/get.sh` 复制成 `site/install.sh`，把 `install.sh`、`restore.sh`、`tcast-push.py` 复制到 `site/tools/`，再部署官网。这些副本不进版本库（见 `.gitignore`）。**每次发版都要跑一次**，否则官网上的 APK 还是旧版。`get.sh`、`install.sh` 先从官网下载，失败再从 GitHub 下载。
5. 官网 `site/` 有中文（`index.html`）和英文（`en/index.html`）两页，共用 `style.css` 和 `app.js`（页面里的 `window.T` 放各自的文案和 Agent 提示词）。页面开头的小脚本按浏览器语言跳转，用户点过语言切换后以用户的选择为准（`localStorage` 的 `aerial-lang`）。改文案时两页都要改，README 也一样（`README.md` 中文、`README.en.md` 英文）。改了 `style.css` 或 `app.js` 要把页面里引用的 `?v=` 加 1。官网是 Cloudflare Worker 静态资源（配置见 `wrangler.jsonc`），绑定在 https://aerial.qust.me 和 https://tv.qust.me。改完官网也用 `tools/deploy-site.sh` 部署（直接 `npx wrangler@4 deploy` 会把官网上的 APK 和脚本删掉）。`site/_headers` 让图片在浏览器里缓存 7 天，所以替换截图时要把 `index.html` 里图片地址的 `?v=` 加 1，否则用户看到的还是旧图。`site/_redirects` 把旧地址 `/download/standard`、`/download/guard` 跳转到 `site/download/` 里的 APK。`site/agent.md`（中文）和 `site/en/agent.md`（英文）是给 Agent 看的安装说明，官网和 README 的 Agent 提示词只让 Agent 去读它；里面的 Windows 逐条步骤照抄了 `tools/install.sh` 和 `tools/restore.sh`，改这两个脚本时要一起改。

## 调试技巧

- **截图**：`adb shell screencap -p /data/local/tmp/x.png && adb pull ...`（小米用 `/data/local/tmp`，TCL 用 `/sdcard`）。用完要删掉。
- **帧率**：`adb shell dumpsys gfxinfo app.orchard.tv reset`，操作几下，再执行 `... framestats`，看 `SwapBuffers→GpuCompleted` 这一段的耗时。
- **守护状态**：`dumpsys accessibility | grep -E "label=(Aerial|app.orchard.tv)"`（有输出就是已绑定；小米 ASTP0 上标签显示成类名）；日志 tag 是 `AerialGuard`。
- **壁纸**：日志 tag 是 `AerialWallpaper`。
- **logcat 看不到 App 日志的电视**（创维 7T631_T60）：`adb shell dumpsys activity app.orchard.tv/.MainActivity` 末尾打印 `DiagLog` 最近的记录（启动、显示模式、绘制、按键、焦点、屏保、加载耗时）；诊断版（`./gradlew -Pdiag ...`，版本名带 `-diag`）或 `ConfigReceiver --ez diag true` 之后还写进 `/sdcard/Android/data/app.orchard.tv/files/aerial-diag.log`，可以 `adb pull`。试验开关（下次启动生效）：`--ez mode_switch false` 不切刷新率（诊断版默认就不切），`--ez soft_layer true` 用软件绘制首页。
- **机型适配信息**：`TV=<ip> tools/report.sh`（只读）生成可以贴进 issue 的 `aerial-report-<机型>.md`，macOS 上顺便复制到剪贴板；用户也可以在手机页最下面「反馈问题」里复制。issue 表单是 `.github/ISSUE_TEMPLATE/device.yml`。内容和隐私边界见 ARCHITECTURE.md「诊断报告」。
- **测应用内升级**：`ConfigReceiver --ez check_update true` 立刻检查；`--ez update_test true` 让「下载并安装」用推到 `Android/data/app.orchard.tv/files/update-test.apk` 的包（测完删掉这个文件）。见 ARCHITECTURE.md「升级」。
- **手机配置页**：令牌不写日志，从电视截图里识别二维码即可：`swift tools/qr-from-screenshot.swift shot.png`。
- **立即预览屏保**：`adb shell am start -a app.orchard.tv.PREVIEW_SCREENSAVER -n app.orchard.tv/.MainActivity`；TCL 上测系统屏保：`am start -n com.android.systemui/.Somnambulator`。
- **模拟小米的待机清理**：`adb shell am force-stop app.orchard.tv`，然后看日志里出现 `revived by the notification listener binding`。
- **配置**：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true`。
- **磁贴对比图**：`adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es export_tiles before`，等日志 `AerialTiles` 出现 `Wrote`，再 `adb pull /sdcard/Android/data/app.orchard.tv/files/tiles/before.png`。改 `TileArt` 或 `TileRecipes` 前后各导一次。别的电视上的 App 不用装：把 APK 去掉 `lib/`、`assets/`、`*.dex` 之后推到 `files/tiles/apks/`，会按「装上了」的样子画在后面（名字后带「·」）。这个目录是自己建的，用完可以整个删掉。

## 必须遵守（踩过的坑）

1. **不要停用小米的 `com.mitv.tvhome`**：小米只认系统签名的桌面，停用后电视会卡在黑屏的 FallbackHome。
2. **改 `enabled_accessibility_services` 时要保留别人的服务**（TCL 有两个 `com.tcl.walleve` 服务），用追加的方式写。
3. **在 zsh 里不要写 `"$VAR:xxx"`**：`:a`、`:h`、`:t` 这些会被当成路径修饰符。一律写 `"${VAR}:xxx"`。这个坑把 TCL 的无障碍设置写坏过两次。zsh 也不会按空格拆分 `$VAR`，要循环就用数组。
4. **清理测试数据时，只删自己创建的东西**（按记下来的 id 删），不要删「所有照片」这种。户主在测试期间用手机上传过照片，差点被一起删掉。用户选的壁纸也不要改回去。
5. **在电视上按键操作前先截图确认焦点**，尤其是当贝的安装包管理（左侧有「一键清空」）。
6. **不要把 `local/` 提交到版本库**（里面有 IP 和签名密钥）。
7. 改动系统设置前，先记下原值（写进 `local/TVS.md`），并确保 `restore.sh` 能还原。
8. 每次改完都在真机上验证，不要只看编译通过。电视上的行为和模拟器差别很大。
9. **不要升级 Compose BOM 到 2025.12 及以后**（Compose 1.10 要求 Android 6.0），也不要升级到要求 minSdk 23 的 AndroidX 版本（比如 `lifecycle` 2.10 起）。升级前看 AAR 里 `AndroidManifest.xml` 的 `minSdkVersion`。

## 重新生成航拍目录

```bash
curl -O https://sylvan.apple.com/Aerials/resources-16.tar
tar xf resources-16.tar entries.json TVIdleScreenStrings.bundle/zh_CN.lproj TVIdleScreenStrings.bundle/en.lproj
plutil -convert json -o zh.json TVIdleScreenStrings.bundle/zh_CN.lproj/Localizable.nocache.strings
plutil -convert json -o en.json TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings
```

然后把每个 asset 转成 `{id,label,category,url(=url-1080-H264),hevc(=url-1080-SDR),uhd(=url-4K-SDR),dv(=url-4K-HDR),captions:[[秒,中文,英文],...]}` 写进 `app/src/main/assets/aerials.json`。captions 由 `pointsOfInterest` 的 key 去两个 strings 文件里查出来；category 由 `categories[0]` 对应 `localizedNameKey` 去掉 `AerialCategory` 前缀再转小写得到。

## 已知问题与限制

- TCL 每次升级都要点一次「同意」（多屏互动的提示框）。可以用 ADB 点：提示框出现时焦点在「拒绝」，先截图确认，再按右键、截图确认焦点在「同意」，再按确定。
- 小米只有停在桌面上时才由 Aerial 接管屏保；在别的 App 里空闲，小米自己的屏保仍会出现。
- TCL 调音量时会同时弹出 TCL 自己的音量条。
- HDMI 设备名只能显示「已连接」，读真实 CEC 名称需要系统权限。
- 航拍和每日精选都需要联网，航拍视频没有本地缓存。4K 杜比视界约 24 Mbps，网慢的家里会频繁跳段，要在个性化里改成 1080p。
- 手机配置页是局域网 HTTP，只靠随机令牌保护。
- 签名用的是 debug key。想换成正式密钥，必须先在两台电视上卸载重装（会丢掉壁纸、照片和图标排序），TCL 还要走一遍当贝流程。
- 海信等其他品牌没有在真机上验证过。

## 可以继续做的事（按价值排序）

1. **小米上在任意 App 空闲时也用 Aerial 屏保**：守护服务能看到 `com.xiaomi.mitv.hyper.screensaver` 的窗口出现，届时打开 Aerial 的屏保 Activity 替换它（需要把屏保做成独立 Activity）。
2. **TCL 升级完全免手动**：让桌面守护（无障碍服务）在多屏互动的推送提示框出现、而且推送的是 Aerial 时，自动点「同意」。
3. **自动化测试**：`LayoutStore.move`、`Aerial.captionAt`、`TileArtFactory` 的图标分类、`SystemProbe` 的信号源映射都适合写单元测试。
4. **航拍预缓存**：夜间缓存几段视频，离线也能播。
5. **手机页增强**：调整 App 排序、隐藏 App、屏保等待时间。
6. **新机型**：参照 `docs/devices/` 的格式补充海信等机型的笔记，`SystemState.DeviceProfile` 里加对应的修正。
7. **tvOS 的「顶部陈列内容」**：国内 App 基本不提供 TvProvider 的预览节目，可以考虑为常用 App 做精选内容（工作量大，要有数据来源）。

## 参考

- tv-vibehack / CleanYourTV（https://github.com/Gathin23/tv-vibehack）：有很多机型的去广告列表，以及 MFTR0 的信号源笔记，Aerial 的小米信号源路由就参考了它。
- Apple 航拍资源：`https://sylvan.apple.com/Aerials/resources-16.tar`。
