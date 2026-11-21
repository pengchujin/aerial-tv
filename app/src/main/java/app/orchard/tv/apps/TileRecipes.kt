package app.orchard.tv.apps

/**
 * How Aerial redraws one app's tile when what the app ships reads badly on Home: a banner crowded
 * with text, or a phone icon whose plate the tile cannot carry. Everything comes from the app's own
 * icon; a recipe only says how to lay it out, so no artwork of another brand ships with Aerial.
 */
class TileRecipe(
    /** Built from the icon even when the app has a TV banner. */
    val useIcon: Boolean = false,
    /** The tile's colors, top to bottom, instead of the icon's own plate. */
    val top: Int? = null,
    val bottom: Int? = null,
    /** The logo drawn in white. Only for logos of one color on a plate of another. */
    val white: Boolean = false,
    /** The logo keeps its own colors on its own plate, outside Aerial's one look: a white logo would lose what tells it apart. */
    val keepColors: Boolean = false,
    /** Drawn as Aerial's own glyph over [top]..[bottom], for apps whose art is only their name. */
    val glyph: app.orchard.tv.ui.theme.Glyph? = null,
)

object TileRecipes {
    private val byPackage = mapOf(
        // Checked against the apps on Xiaomi MiTV-MFTR0.
        "com.mitv.tvhome" to TileRecipe(top = 0xFF4C8DFF.toInt(), bottom = 0xFF1F55E0.toInt(), white = true), // 桌面 (PatchWall)
        "com.duokan.videodaily" to TileRecipe(top = 0xFFFF4B4B.toInt(), bottom = 0xFFD0101C.toInt()), // 视频头条: a picture, not a plate
        // Checked against the apps on TCL MT5879 (China), drawn on Xiaomi MiTV-MFTR0.
        "cn.wps.moffice_i18n_TV" to TileRecipe(top = 0xFFFF5A3C.toInt(), bottom = 0xFFD81E1E.toInt(), white = true), // WPS TV版
        "com.dangbeimarket" to TileRecipe(useIcon = true, keepColors = true), // 当贝市场: the banner repeats its name in two sizes; the logo is embossed
        "com.wtj.cn.alipantv" to TileRecipe(useIcon = true), // 阿里云盘TV: the banner crowds its name into a corner
        "com.tcl.tshop" to TileRecipe(keepColors = true), // T惠购: the red ribbon is what makes it a gift
        "com.quark.yun.tv" to TileRecipe(top = 0xFF6A7BFF.toInt(), bottom = 0xFF3F3BE0.toInt(), white = true), // 夸克网盘
        // 我的电视: live channels; its icon and banner are only its name in grey. Teal as on tuner inputs.
        "com.lizongying.mytv" to TileRecipe(top = 0xFF1FA88E.toInt(), bottom = 0xFF0E5C55.toInt(), glyph = app.orchard.tv.ui.theme.Glyph.Antenna),
        // Checked against APKs from 当贝市场, drawn on Xiaomi MiTV-MFTR0.
        "com.baidu.netdisk.tv" to TileRecipe(useIcon = true), // 百度网盘: the banner adds its name and "TV版" beside the icon
        "com.dangbei.dbmusic" to TileRecipe(useIcon = true), // BesTV当贝音乐: name and slogan on a white banner
        "com.dangbei.tvlauncher" to TileRecipe(useIcon = true), // 当贝桌面: the banner is its name and a version badge
        "com.tencent.karaoketv" to TileRecipe(useIcon = true), // 全民K歌: name beside the icon on white
        "cn.jmake.karaoke.box.open" to TileRecipe(useIcon = true), // 雷石K歌: a cropped microphone over "专业版"
        "com.xunlei.downloadprovider.hd" to TileRecipe(useIcon = true), // 迅雷云盘: name beside the icon on white
        "com.ucmobile.yun.tv" to TileRecipe(keepColors = true), // UC网盘: a shaded logo that turns to mist in white
        "com.zmg.ott" to TileRecipe(top = 0xFF3FA9FF.toInt(), bottom = 0xFF1747E0.toInt(), white = true), // Z视介: its white Z, without the facets behind it
        "com.sohuott.tv.vod" to TileRecipe(keepColors = true), // 云视听悦厅TV: a shaded logo that turns to mist in white
        // Hisense VIDAA (MT9603), checked on the TV. These icons carry a detail in the plate's own
        // color (聚好看's eye, 日历's header), which Aerial's one look would turn inside out.
        "com.jamdeo.tv.vod" to TileRecipe(useIcon = true, keepColors = true), // 聚好看
        "com.android.wechathci" to TileRecipe(useIcon = true, keepColors = true), // 微信电视
        "com.jamdeo.calendar" to TileRecipe(useIcon = true, keepColors = true), // 日历 (Cinesense)
        "com.android.calendar" to TileRecipe(useIcon = true, keepColors = true), // 日历 (AOSP)
    )

    fun of(pkg: String): TileRecipe? = byPackage[pkg]
}
