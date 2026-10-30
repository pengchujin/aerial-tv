package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    /** False for apps on the system image, which cannot be uninstalled. */
    val removable: Boolean,
    /** The tile as Home draws it: its picture cut to the continuous outline, at focused size. */
    val tile: Bitmap,
    /** A quick stand-in, until [AppCatalog.load] has made the real tile. */
    val placeholder: Boolean = false,
    /** Set for a folder's tile; [key] is then the folder's key. */
    val folder: Folder? = null,
) {
    /** The same app under another name (a name typed on the phone page, AppNames). */
    fun named(name: String) = AppEntry(key, name, component, leanback, removable, tile, placeholder, folder)

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    companion object {
        /** Intent.CATEGORY_LEANBACK_LAUNCHER, which the 4.x SDKs do not name. */
        const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
    }
}

/**
 * Every launchable component, one tile per entry, on the full app's rules (apps/Apps.kt): TV entries
 * first, then phone ones and the firmware's hidden ones; the TV's own launcher as 原桌面; Aerial's
 * own tiles for firmware apps; the icon library's picture before the app's banner or icon.
 * Tiles are made once per app version and kept as PNGs in cache/tiles.
 */
/** Kept for the full app's LayoutStore, which Lite shares: Lite pins no inputs, so no key has it. */
const val SOURCE_KEY_PREFIX = "input:"

/** A folder's tile, its picture made from the apps in it. */
fun folderEntry(folder: Folder, tile: Bitmap): AppEntry =
    AppEntry(folder.key, folder.name, ComponentName("folder", folder.id), leanback = false, removable = false, tile = tile, folder = folder)

object AppCatalog {
    private const val TAG = "AerialLite"
    /** Xiaomi's app store declares only this category, not CATEGORY_LAUNCHER (MiTV-MFTR0). */
    private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"

    /** Firmware apps with no launcher category at all (Xiaomi MiTV-MFTR0). */
    private val UNLISTED_ENTRIES = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"),
        ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"),
    )

    /** Firmware apps drawn as Aerial's own tiles, by package, as in the full app. */
    private val SYSTEM_TILES = mapOf(
        "com.xiaomi.mitv.settings" to SystemTile.Settings,
        "com.mitv.gallery" to SystemTile.Photos,
        "com.xiaomi.mitv.calendar" to SystemTile.Calendar,
        "com.xiaomi.tweather" to SystemTile.Weather,
        "com.xiaomi.mitv.smartshare" to SystemTile.Cast,
        "com.xiaomi.mitv.tvmanager" to SystemTile.Security,
        "com.xiaomi.mitv.appstore" to SystemTile.Store,
        "com.xiaomi.mitv.mediaexplorer" to SystemTile.Media,
        "com.tcl.settings" to SystemTile.Settings,
        "com.tcl.tvsmartalbum" to SystemTile.Photos,
        "com.tcl.common.weather" to SystemTile.Weather,
        "com.tcl.MultiScreenInteraction_TV" to SystemTile.Cast,
        "com.tcl.guard" to SystemTile.Security,
        "com.tcl.appmarket2" to SystemTile.Store,
        "com.tcl.ui_mediaCenter" to SystemTile.Media,
        "com.tcl.browser" to SystemTile.Browser,
    )

    /** Firmwares that declare the standard categories; only for system apps without a TV banner. */
    private val SYSTEM_CATEGORIES = listOf(
        "android.intent.category.APP_GALLERY" to SystemTile.Photos,
        "android.intent.category.APP_CALENDAR" to SystemTile.Calendar,
        "android.intent.category.APP_BROWSER" to SystemTile.Browser,
        "android.intent.category.APP_MARKET" to SystemTile.Store,
        CATEGORY_APPSTORE_LAUNCHER to SystemTile.Store,
    )

    /** The packages of the last load, for the icon library. */
    @Volatile var installed: Set<String> = emptySet(); private set

    /**
     * With [quick], apps whose tile is not on disk yet get a stand-in at once ([TileFactory.quick])
     * instead of the real one, so Home shows on the first start without waiting for every tile.
     * Without it, every tile is made and kept, and [onBatch] hears of them five at a time.
     */
    fun load(context: Context, quick: Boolean = false, onBatch: ((List<AppEntry>) -> Unit)? = null): List<AppEntry> {
        val pm = context.packageManager
        IconLibrary.init(context)
        CustomIcons.init(context)
        val entries = LauncherEntries()
        fun add(category: String, leanback: Boolean) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                entries.add(r.activityInfo, leanback)
            }
        }
        add(AppEntry.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)
        add(CATEGORY_APPSTORE_LAUNCHER, false)
        for (component in UNLISTED_ENTRIES) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: continue
            entries.add(info, leanback = false, onlyWhenPackageMissing = true)
        }
        // The TV's own launcher, which some firmwares declare only as a Home.
        val stockHomes = HashSet<String>()
        for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            val info = r.activityInfo
            if (r.priority < 0) continue
            if (entries.add(info, leanback = false, onlyWhenPackageMissing = true)) stockHomes += info.packageName
        }
        entries.removePackage(context.packageName)
        // The full app is a Home too; it is never one of Lite's tiles.
        entries.removePackage("app.orchard.tv")
        val found = entries.all()

        val byCategory = HashMap<String, SystemTile>()
        for ((category, tile) in SYSTEM_CATEGORIES) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (!byCategory.containsKey(r.activityInfo.packageName)) byCategory[r.activityInfo.packageName] = tile
            }
        }
        pm.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.let {
            if (!byCategory.containsKey(it.activityInfo.packageName)) byCategory[it.activityInfo.packageName] = SystemTile.Settings
        }
        installed = found.mapTo(HashSet()) { it.info.packageName }

        val dir = File(context.cacheDir, "tiles").apply { mkdirs() }
        val index = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)
        val homeLabel = context.getString(R.string.tile_stock_home)
        // Two threads: these TVs have two to four slow cores, and Home's own thread needs one.
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = found.map { launcher ->
                pool.submit(Callable {
                    runCatching { entry(pm, launcher.info, launcher.leanback, byCategory, launcher.info.packageName in stockHomes, homeLabel, dir, index, quick, launcher.key) }
                        .onFailure { Log.w(TAG, "left ${launcher.info.packageName}/${launcher.info.name} out of Home", it) }
                        .getOrNull()
                })
            }
            val out = ArrayList<AppEntry>()
            val batch = ArrayList<AppEntry>()
            for (job in jobs) {
                val e = job.get() ?: continue
                out += e
                if (onBatch != null) {
                    batch += e
                    if (batch.size == 5) { onBatch(ArrayList(batch)); batch.clear() }
                }
            }
            if (onBatch != null && batch.isNotEmpty()) onBatch(batch)
            return out
        } finally {
            pool.shutdown()
        }
    }

    /** The face's size and outline: the tile at its 1.15× focus, 12-unit corners, a half-unit rim, as AppTile. */
    val faceW get() = Ui.pxi(HomeView.TILE_W * HomeView.FOCUS_SCALE)
    val faceH get() = Ui.pxi(HomeView.TILE_H * HomeView.FOCUS_SCALE)

    private fun entry(
        pm: PackageManager, info: ActivityInfo, leanback: Boolean, byCategory: Map<String, SystemTile>, stockHome: Boolean,
        homeLabel: String, dir: File, index: android.content.SharedPreferences, quick: Boolean = false, entryKey: String = info.packageName,
    ): AppEntry {
        val pkg = info.packageName
        // A picture chosen on the phone page changes the tile as much as an update does.
        val primary = entryKey == pkg
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L) +
            (if (primary) IconLibrary.stamp(pkg) else 0L) + CustomIcons.stamp(entryKey) + if (stockHome) 1 else 0
        val system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val component = ComponentName(pkg, info.name)
        val fw = faceW
        val fh = faceH
        val key = "v3|$stamp|$fw|$fh|${info.name}|$leanback"
        val file = tileFile(dir, entryKey)
        val cached = index.getString(entryKey, null)
        if (cached != null && cached.startsWith("$key|") && file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let {
                val label = if (stockHome) homeLabel else cached.substring(key.length + 1)
                return AppEntry(entryKey, label, component, leanback, !system, it)
            }
        }
        val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault("").ifEmpty { pkg }
        if (quick) {
            val icon = runCatching { info.loadIcon(pm) }.getOrNull() ?: pm.defaultActivityIcon
            val art = TileFactory.quick(icon)
            val face = Shapes.face(art, fw, fh, Ui.px(12f * HomeView.FOCUS_SCALE), Ui.px(0.5f * HomeView.FOCUS_SCALE))
            art.recycle()
            return AppEntry(entryKey, if (stockHome) homeLabel else label, component, leanback, !system, face, placeholder = true)
        }
        val banner = if (primary || (Build.VERSION.SDK_INT >= 21 && info.bannerResource != 0)) banner(pm, info, leanback) else null
        val own = if (stockHome) SystemTile.Home
            else if (system && primary) SYSTEM_TILES[pkg] ?: byCategory[pkg]?.takeIf { banner == null } else null
        // Aerial's own tiles for firmware apps keep their look; any other app's ready-made picture
        // comes before what Aerial would draw from its banner or icon.
        val custom = CustomIcons.load(entryKey)
        val library = if (primary && custom == null && (own == null || IconLibrary.pick(pkg) == IconLibrary.Pick.Library)) IconLibrary.load(pkg) else null
        val recipe = TileRecipes.of(entryKey)
        val art = runCatching {
            when {
                // The owner's own picture first, as in the full app.
                custom != null -> TileFactory.fromBanner(BitmapDrawable(android.content.res.Resources.getSystem(), custom))
                library != null -> TileFactory.fromBanner(BitmapDrawable(android.content.res.Resources.getSystem(), library))
                own != null -> TileFactory.forSystem(own)
                recipe?.glyph != null -> TileFactory.glyphTile(recipe.top ?: 0, recipe.bottom ?: 0, recipe.glyph)
                banner != null && recipe?.useIcon != true -> TileFactory.fromBanner(banner)
                else -> TileFactory.fromIcon(densest(pm, info, info.iconResource) ?: info.loadIcon(pm), recipe)
            }
        }.recoverCatching {
            Log.w(TAG, "could not draw the tile of $pkg; using its icon", it)
            TileFactory.fromIcon(info.loadIcon(pm), null)
        }.recoverCatching {
            TileFactory.fromIcon(pm.defaultActivityIcon, null)
        }.getOrThrow()
        val face = Shapes.face(art, fw, fh, Ui.px(12f * HomeView.FOCUS_SCALE), Ui.px(0.5f * HomeView.FOCUS_SCALE))
        art.recycle()
        runCatching {
            FileOutputStream(file).use { face.compress(Bitmap.CompressFormat.PNG, 100, it) }
            index.edit().putString(entryKey, "$key|$label").apply()
        }
        return AppEntry(entryKey, if (stockHome) homeLabel else label, component, leanback, !system, face)
    }

    /**
     * The densest bucket the app ships: loadIcon picks the screen's, 48 px on a 160 dpi TV, soft on
     * a tile. getDrawableForDensity came in Android 4.0.3.
     */
    private fun densest(pm: PackageManager, info: ActivityInfo, res: Int): Drawable? {
        if (res == 0 || Build.VERSION.SDK_INT < 15) return null
        return runCatching {
            @Suppress("DEPRECATION")
            pm.getResourcesForApplication(info.applicationInfo).getDrawableForDensity(res, 640)
        }.getOrNull()
    }

    /** The app's TV banner (Android 5.0+); a "banner" that is not wide is its icon declared again. */
    private fun banner(pm: PackageManager, info: ActivityInfo, leanback: Boolean): Drawable? {
        if (!leanback || Build.VERSION.SDK_INT < 21) return null
        val d = densest(pm, info, info.bannerResource) ?: runCatching { info.loadBanner(pm) }.getOrNull()
        return d?.takeIf { it.intrinsicWidth >= it.intrinsicHeight * 4 / 3 }
    }

    /**
     * The tile as the app's own art would draw it, without the owner's picture or the icon
     * library's: for the phone page to show beside them. Off the main thread.
     */
    fun ownArt(context: Context, app: AppEntry): Bitmap? = runCatching {
        val pm = context.packageManager
        val info = pm.getActivityInfo(app.component, 0)
        val primary = app.key == info.packageName
        val recipe = TileRecipes.of(app.key)
        val banner = if (primary || (Build.VERSION.SDK_INT >= 21 && info.bannerResource != 0)) banner(pm, info, app.leanback) else null
        val art = when {
            recipe?.glyph != null -> TileFactory.glyphTile(recipe.top ?: 0, recipe.bottom ?: 0, recipe.glyph)
            banner != null && recipe?.useIcon != true -> TileFactory.fromBanner(banner)
            else -> TileFactory.fromIcon(densest(pm, info, info.iconResource) ?: info.loadIcon(pm), recipe)
        }
        Shapes.face(art, faceW, faceH, Ui.px(12f * HomeView.FOCUS_SCALE), Ui.px(0.5f * HomeView.FOCUS_SCALE)).also { art.recycle() }
    }.getOrNull()

    /** Drops the cached tiles of apps no longer installed. */
    fun prune(context: Context, keep: Set<String>) {
        val index = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)
        val gone = index.all.keys.filter { it !in keep }
        if (gone.isEmpty()) return
        val e = index.edit()
        for (pkg in gone) {
            e.remove(pkg)
            tileFile(File(context.cacheDir, "tiles"), pkg).delete()
        }
        e.apply()
    }

    private fun tileFile(dir: File, entryKey: String): File {
        val name = if (CustomIcons.isValidKey(entryKey)) entryKey else
            "entry-" + java.security.MessageDigest.getInstance("SHA-256").digest(entryKey.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.png")
    }
}
