package app.orchard.tv.lite

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Aerial Lite's only Activity: Home. Holds Home, the panels and the screensaver, and routes every
 * key itself; loading runs on one background thread so the main thread only places views. Home is
 * arranged as the full app's HomeViewModel arranges it: the shared LayoutStore (order, folders,
 * hidden apps, the first run's order) over the apps with the names typed on the phone page.
 */
class HomeActivity : Activity(), HomeView.Host {
    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private lateinit var layout: LayoutStore
    /** The panels open, the top one taking keys: a folder under an app's menu, say. */
    private val panels = ArrayList<Sheet>()
    private var saver: SaverView? = null
    private var backdrop: Drawable? = null
    /** The wallpaper as Home shows it, for the clock screensaver's light. */
    private var wallpaperPicture: Bitmap? = null
    /** The grid's background: the frosted wallpaper as the plate's milky glass, not darkened, stretched (the full app's, issue #727). */
    private var gridBackdrop: Drawable? = null
    private var inGrid = false
    private var all: List<AppEntry> = emptyList()
    private var folderApps: Map<String, List<AppEntry>> = emptyMap()
    private var hiddenApps: List<AppEntry> = emptyList()
    private val folderTiles = HashMap<String, Pair<String, Bitmap>>()
    private val work = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val proxy by lazy { FilmProxy(applicationContext) }
    private var resumed = false
    /** The key whose press woke the screensaver: its repeats and release are not for Home. */
    private var swallow = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        TileFactory.useScreen()
        LiteSettings.init(this)
        Wallpapers.init(this)
        AppNames.init(this)
        Weather.init(this)
        Sounds.init(this)
        layout = LayoutStore(this)
        migrateOldLayout()
        root = FrameLayout(this)
        home = HomeView(this, this)
        root.addView(home)
        setContentView(root)
        hideBars()
        loadBackdrop()
        loadApps()
        refreshDaily()
        PhoneServer.listen(this, phoneOps)
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        @Suppress("DEPRECATION")
        registerReceiver(connectivity, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        if (savedInstanceState == null && SystemClock.elapsedRealtime() < 3 * 60 * 1000L) openBootApp()
    }

    /** The first builds kept Home's order as a JSON array in "lite"; LayoutStore keeps it its own way. */
    private fun migrateOldLayout() {
        val old = getSharedPreferences("lite", MODE_PRIVATE)
        if (!old.contains("order") && !old.contains("hidden")) return
        old.edit().clear().apply()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        unregisterReceiver(connectivity)
        hideSaver()
        proxy.stop()
        PhoneServer.stop()
        work.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    /**
     * TVs have no navigation bar; Android-x86 and a few boxes do. HIDE_NAVIGATION alone, without
     * 4.4's immersive flags: those show a "swipe down to exit full screen" dialog the first time,
     * and a remote has no way to swipe.
     */
    @Suppress("DEPRECATION")
    private fun hideBars() {
        val id = Resources.getSystem().getIdentifier("config_showNavigationBar", "bool", "android")
        val navBar = id != 0 && Resources.getSystem().getBoolean(id)
        root.systemUiVisibility = if (navBar) android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.refreshClock()
        showNetwork()
        showWeather()
        refreshDaily()
        Weather.refreshIfStale({ work.execute(it) }) { handler.post { showWeather() } }
        resetIdle()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(idle)
        hideSaver()
        // Back on Home the PIN is asked again (the full app relocks when an app opens).
        LiteSettings.relock()
        super.onPause()
    }

    private fun showWeather() {
        home.clock.weather = if (LiteSettings.showWeather) Weather.line(this) else null
    }

    /** The Home key while Home is in front: back to the first tile, as the full app does. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            hideSaver()
            closeAll()
            home.reset()
        }
    }

    /** Keys whose press came here and which have not been lifted yet. */
    private val keysDown = HashSet<Int>()

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        resetIdle()
        // A lift whose press never reached us still wakes the screensaver: some remotes' keys are
        // pressed for a vendor hotkey service and only lifted for the app (Coocaa C10, issue #915).
        val loneLift = e.action == KeyEvent.ACTION_UP && !e.isCanceled && !keysDown.contains(e.keyCode)
        if (e.action == KeyEvent.ACTION_DOWN) keysDown.add(e.keyCode) else keysDown.remove(e.keyCode)
        if (saver != null) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                swallow = e.keyCode
                hideSaver()
            } else if (loneLift) {
                hideSaver()
            }
            return true
        }
        if (e.keyCode == swallow) {
            if (e.action == KeyEvent.ACTION_UP) swallow = -1
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) Sounds.key(e.keyCode)
        panels.lastOrNull()?.let { if (it.onKey(e)) return true; return super.dispatchKeyEvent(e) }
        if (home.onKey(e)) return true
        return super.dispatchKeyEvent(e)
    }

    // ---- loading ----

    private fun loadBackdrop() {
        work.execute {
            val b = runCatching { Wallpapers.load() }
                .onFailure { Log.w(TAG, "wallpaper ${Wallpapers.selectedId}", it) }.getOrNull() ?: return@execute
            val grid = Images.tinted(b.frost, PLATE_TINT)
            handler.post {
                if (isFinishing) return@post
                backdrop = BitmapDrawable(resources, b.home)
                wallpaperPicture = b.home
                Glass.frost = b.frost
                gridBackdrop = BitmapDrawable(resources, grid).apply { setFilterBitmap(true) }
                home.setGlass(b.frost)
                if (saver == null) window.setBackgroundDrawable(if (inGrid) gridBackdrop else backdrop)
            }
        }
    }

    private var dailyCheckedAt = 0L

    /** Bing's photo of the day: looked at again when Home is back, at most every three hours. */
    private fun refreshDaily() {
        if (SystemClock.elapsedRealtime() - dailyCheckedAt < 3 * 60 * 60 * 1000L && dailyCheckedAt != 0L) return
        dailyCheckedAt = SystemClock.elapsedRealtime()
        work.execute {
            if (!Wallpapers.refreshDaily()) return@execute
            handler.post {
                Wallpapers.rebuildList()
                if (Wallpapers.selectedId.startsWith("daily:") || Wallpapers.resolve()?.kind == WallpaperKind.Daily) loadBackdrop()
                (panels.lastOrNull() as? Personalize)?.wallpapersChanged()
            }
        }
    }

    /** The face size the tiles were last loaded at (AppCatalog.faceW). */
    private var loadedFaceW = 0

    /** Another number of apps a row: the faces again at the new size, as the full app's artwork. */
    private fun refaceIfResized() {
        if (loadedFaceW == 0 || loadedFaceW == AppCatalog.faceW) return
        folderTiles.clear()
        loadApps()
    }

    private fun loadApps() {
        loadedFaceW = AppCatalog.faceW
        work.execute {
            val started = System.currentTimeMillis()
            // Home first, with stand-ins for tiles not made yet; then the real ones as they come.
            val first = AppCatalog.load(this, quick = true)
            Log.i(TAG, "${first.size} apps in ${System.currentTimeMillis() - started} ms")
            handler.post { if (!isFinishing) { all = first; arrange() } }
            if (first.any { it.placeholder }) {
                val list = AppCatalog.load(this) { batch -> handler.post { if (!isFinishing) replace(batch) } }
                Log.i(TAG, "${list.size} tiles made in ${System.currentTimeMillis() - started} ms")
            }
            AppCatalog.prune(this, first.mapTo(HashSet()) { it.key })
            // The icon library's pictures, once Home is up; new ones redraw those tiles.
            if (runCatching { IconLibrary.refresh(AppCatalog.installed) }.getOrDefault(false)) handler.post(reload)
        }
    }

    /** Tiles that are ready take the place of their stand-ins. */
    private fun replace(batch: List<AppEntry>) {
        val byKey = HashMap<String, AppEntry>()
        for (e in batch) byKey[e.key] = e
        all = all.map { byKey[it.key] ?: it }
        arrange()
    }

    private val reload = Runnable { loadApps() }

    /**
     * A network or VPN coming or going (a VPN's tunnel sends CONNECTIVITY_ACTION too). The pill
     * showed Wi-Fi whatever the network until it took the VPN badge (issue #809).
     */
    private val connectivity = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showNetwork()
    }

    private fun showNetwork() {
        work.execute {
            val net = ControlCenter.network(this)
            handler.post {
                home.pill.network = net.glyph
                home.pill.level = net.level
                home.pill.vpn = net.vpn
            }
        }
    }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 500)
        }
    }

    /** Home's tiles, as the full app's HomeViewModel.arrange: names from the phone page first. */
    private fun arrange() {
        val named = all.map { e -> AppNames.of(e.key)?.let(e::named) ?: e }
        val arranged = layout.arrange(named) { folder, inside -> folderEntry(folder, folderTile(folder, inside)) }
        folderApps = arranged.folders
        hiddenApps = arranged.hidden
        home.setApps(arranged.home)
        (panels.lastOrNull() as? FolderView)?.let { open ->
            val inside = folderApps[open.folderId]
            if (inside.isNullOrEmpty()) closePanel() else open.setApps(inside)
        }
    }

    /** A folder's picture, made again only when what it shows changes. */
    private fun folderTile(folder: Folder, inside: List<AppEntry>): Bitmap {
        val shown = inside.take(9)
        val key = shown.joinToString(",") { it.key + if (it.placeholder) "~" else "" }
        folderTiles[folder.id]?.takeIf { it.first == key }?.let { return it.second }
        val art = TileFactory.forFolder(shown.map { it.tile })
        val face = Shapes.face(art, AppCatalog.faceW, AppCatalog.faceH, Ui.px(12f * HomeView.FOCUS_SCALE), Ui.px(0.5f * HomeView.FOCUS_SCALE))
        art.recycle()
        folderTiles[folder.id] = key to face
        return face
    }

    /** Any app by its key, on Home, in a folder or hidden. */
    private fun entry(key: String): AppEntry? =
        home.apps.firstOrNull { it.key == key } ?: folderApps.values.flatten().firstOrNull { it.key == key } ?: hiddenApps.firstOrNull { it.key == key }

    // ---- HomeView.Host ----

    override fun launch(app: AppEntry) {
        // The TV's settings sit behind the parental PIN, as in the full app.
        if (LiteSettings.locked && isSettingsApp(app.component.packageName)) { whenUnlocked { launch(app) }; return }
        try {
            startActivity(app.launchIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "could not open ${app.key}", t)
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isSettingsApp(pkg: String) = pkg in SETTINGS_APPS ||
        runCatching { packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName == pkg }.getOrDefault(false)

    override fun openFolder(folder: AppEntry) {
        val f = folder.folder ?: return
        val inside = folderApps[f.id] ?: return
        showPanel(FolderView(this, ::closePanel, f.id, f.name, inside, launch = ::launch, menu = { app, tile -> openMenu(app, tile) }), keepBelow = false)
    }

    override fun openMenu(app: AppEntry, tile: android.view.View) = showMenu(app, tileRect(tile), MenuPage.Main)

    private enum class MenuPage { Main, Folders, Rename }

    /**
     * A tile's menu, as the full app's AppMenu: an app's (move or take out of its folder, into a
     * folder, hide, the phone page's picture, info, what the TV starts on, uninstall), or a
     * folder's (move, rename, ungroup). Sub-pages come back to the main page with Back.
     */
    private fun showMenu(app: AppEntry, anchor: android.graphics.RectF, page: MenuPage) {
        val folder = app.folder
        val holder = layout.folderHolding(app.key)
        val rows = ArrayList<MenuRow>()
        fun add(glyph: Glyph, text: Int, trailing: String? = null, destructive: Boolean = false, action: () -> Unit) {
            rows += MenuRow(this, glyph, getString(text), trailing, destructive).apply { this.action = action }
        }
        fun addText(glyph: Glyph, text: String, action: () -> Unit) {
            rows += MenuRow(this, glyph, text).apply { this.action = action }
        }
        var title = app.label
        when (page) {
            MenuPage.Main -> if (folder != null) {
                add(Glyph.Move, R.string.menu_move) { closeMenu(); home.startMove(app) }
                add(Glyph.Pencil, R.string.menu_rename_folder, "›") { showMenu(app, anchor, MenuPage.Rename) }
                add(Glyph.Folder, R.string.menu_ungroup) { closeMenu(); layout.ungroup(folder.id); folderTiles.remove(folder.id); arrange() }
            } else {
                if (holder != null) add(Glyph.Folder, R.string.menu_out_of_folder) { closeMenu(); layout.takeOutOfFolder(app.key); arrange() }
                else add(Glyph.Move, R.string.menu_move) { closeMenu(); home.startMove(app) }
                add(Glyph.Folder, R.string.menu_to_folder, "›") { showMenu(app, anchor, MenuPage.Folders) }
                add(Glyph.EyeSlash, R.string.menu_hide) { closeMenu(); layout.hide(app.key); arrange() }
                // A picture chosen on the phone page; changing it is done there.
                if (CustomIcons.has(app.key)) add(Glyph.Photo, R.string.menu_reset_icon) {
                    closeMenu()
                    CustomIcons.clear(app.key)
                    reload.run()
                }
                add(Glyph.Info, R.string.menu_info) {
                    closeAll()
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.component.packageName)))
                }
                // What the TV starts on, for homes that switch it on to watch something.
                val boots = LiteSettings.bootApp == app.key
                add(if (boots) Glyph.Check else Glyph.Power, if (boots) R.string.menu_boot_off else R.string.menu_boot_on) {
                    closeMenu()
                    LiteSettings.chooseBootApp(if (boots) null else app.key)
                }
                if (app.removable) add(Glyph.Trash, R.string.menu_uninstall, destructive = true) {
                    closeAll()
                    open(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName)))
                }
            }
            MenuPage.Folders -> {
                title = getString(R.string.menu_to_folder)
                add(Glyph.Folder, R.string.menu_new_folder) { closeMenu(); putInFolder(app, null) }
                // The folder the app is already in is not offered.
                for (f in layout.folders().filter { it.id != holder?.id }) addText(Glyph.Folder, f.name) { closeMenu(); putInFolder(app, f.id) }
            }
            MenuPage.Rename -> {
                title = getString(R.string.menu_rename_folder)
                val current = folder?.name
                // The folder's own name first, ticked, so OK on it changes nothing.
                val names = resources.getStringArray(R.array.folder_names).toList()
                for (name in listOfNotNull(current) + names.filter { it != current }) {
                    addText(if (name == current) Glyph.Check else Glyph.Pencil, name) {
                        closeMenu()
                        folder?.let { layout.renameFolder(it.id, name); arrange() }
                    }
                }
            }
        }
        // A sub-page replaces the main one; Back goes to it again.
        if (panels.lastOrNull() is TileMenu) panels.removeAt(panels.size - 1).also { root.removeView(it) }
        val back: () -> Unit = if (page == MenuPage.Main) ::closeMenu else { { showMenu(app, anchor, MenuPage.Main) } }
        showPanel(TileMenu(this, back, title, anchor, rows), keepBelow = true)
    }

    private fun closeMenu() {
        if (panels.lastOrNull() is TileMenu) closePanel()
    }

    /** As the full app's putInFolder: a new folder is named after what the app is, numbered when taken. */
    private fun putInFolder(app: AppEntry, id: String?) {
        val names = resources.getStringArray(R.array.folder_names)
        // Firmware apps are what most folders on a TV are for.
        val base = if (!app.removable) names[0] else getString(R.string.folder_default)
        val taken = layout.folders().map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        layout.putInFolder(app.key, id, name)
        arrange()
    }

    /** Where [tile]'s picture is on screen, at its focused size. */
    private fun tileRect(tile: android.view.View): android.graphics.RectF {
        val loc = IntArray(2)
        tile.getLocationInWindow(loc)
        val ext = Ui.px(HomeView.TILE_EXT) * tile.scaleX
        // getLocationInWindow gives the scaled view's top left.
        return android.graphics.RectF(loc[0] + ext, loc[1] + ext, loc[0] + tile.width * tile.scaleX - ext, loc[1] + tile.height * tile.scaleY - ext)
    }

    override fun openControlCenter() = showControlCenter(CcPage.Main)

    private fun showControlCenter(page: CcPage) {
        closeAll()
        showPanel(ControlCenter(
            this, ::closePanel, page,
            open = { closeAll(); open(it) },
            locked = { whenUnlocked(it) },
            personalize = ::openPersonalize,
            goTo = ::showControlCenter,
            bootLabel = LiteSettings.bootApp?.let { pkg -> entry(pkg)?.label ?: pkg },
            clearBoot = { LiteSettings.chooseBootApp(null); showControlCenter(CcPage.Main) },
            noInputs = { Toast.makeText(this, getString(R.string.cc_no_inputs), Toast.LENGTH_SHORT).show() },
        ))
    }

    private fun openPersonalize() {
        closeAll()
        showPanel(Personalize(
            this, { closeAll(); showControlCenter(CcPage.Main) },
            hiddenCount = hiddenApps.size,
            onWallpaper = {
                // Baking takes a moment on these CPUs: only the one the owner stops on.
                handler.removeCallbacks(bake)
                handler.postDelayed(bake, 500)
            },
            onLayout = { home.applySettings(); showWeather(); refaceIfResized() },
            onHidden = { whenUnlocked(::openHidden) },
            onSaverNow = { closeAll(); showSaver() },
            onPin = { on ->
                if (on) showPanel(PinPad(this, ::closePanel, PinPad.Mode.Create) { closePanel(); refreshPersonalize() }, keepBelow = true)
                else whenUnlocked { LiteSettings.choosePin(null); refreshPersonalize() }
            },
        ))
    }

    private fun refreshPersonalize() { (panels.lastOrNull() as? Personalize)?.refreshChips() }

    private val bake = Runnable { loadBackdrop() }

    private fun openHidden() {
        closeAll()
        showPanel(HiddenApps(this, { closeAll(); openPersonalize() }, hiddenApps) { app ->
            layout.unhide(app.key)
            arrange()
            openHidden()
        })
    }

    /** Runs [action] at once, or after the parental PIN when one is set (issue #10). */
    private fun whenUnlocked(action: () -> Unit) {
        if (!LiteSettings.locked) { action(); return }
        showPanel(PinPad(this, ::closePanel, PinPad.Mode.Unlock) { closePanel(); action() }, keepBelow = true)
    }

    override fun startSaver() = showSaver()

    /**
     * Down in the grid the background is the plate's glass over the whole screen, as in the full app.
     * The swap fades over the window background (two full-screen draws only while it fades), then
     * the window background itself becomes the new picture. Without hardware acceleration it jumps.
     */
    override fun inGrid(grid: Boolean) {
        if (inGrid == grid) return
        inGrid = grid
        val to = (if (grid) gridBackdrop else backdrop) ?: return
        if (saver != null) return
        if (!LiteSettings.full(root.isHardwareAccelerated)) { window.setBackgroundDrawable(to); return }
        val from = (if (grid) backdrop else gridBackdrop) ?: run { window.setBackgroundDrawable(to); return }
        val fade = android.graphics.drawable.TransitionDrawable(arrayOf(from, to)).apply { isCrossFadeEnabled = true }
        window.setBackgroundDrawable(fade)
        fade.startTransition(220)
        handler.postDelayed({ if (inGrid == grid && saver == null) window.setBackgroundDrawable(to) }, 260)
    }

    override fun moved(key: String, target: String) {
        layout.move(key, target)
        arrange()
    }

    // ---- panels and the screensaver ----

    /** Opens [p] over Home; [keepBelow] leaves the panel under it (a folder under its app's menu). */
    private fun showPanel(p: Sheet, keepBelow: Boolean = false) {
        if (!keepBelow) closeAll()
        panels += p
        root.addView(p)
        home.coveredBelow = 0
        // Once Personalize has slid in (320 ms), Home draws only above it.
        if (p is Personalize) handler.postDelayed({ if (panels.lastOrNull() === p) home.coveredBelow = p.covers }, 400)
        // Panels at the top right take the status pill's place, as in the full app.
        home.topRight.alpha = if (panels.any { it is ControlCenter || it is HiddenApps }) 0f else 1f
        redrawAllInSoftware()
    }

    private fun closePanel() {
        panels.removeLastOrNull()?.let { root.removeView(it) }
        home.coveredBelow = 0
        home.topRight.alpha = if (panels.any { it is ControlCenter || it is HiddenApps }) 0f else 1f
        redrawAllInSoftware()
    }

    private fun closeAll() {
        while (panels.isNotEmpty()) closePanel()
    }

    /**
     * Drawn by the CPU, a frame only repaints its dirty region and keeps the rest from the buffer
     * before. Right after the first start (Android-x86 4.4, every fresh install) that buffer can
     * still be black, and opening a panel left Home black around it but for a strip at the top.
     * A panel opens or closes rarely, so there the whole window is drawn again.
     */
    private fun redrawAllInSoftware() {
        if (!root.isHardwareAccelerated) root.invalidate()
    }

    private fun showSaver() {
        if (saver != null) return
        LiteSettings.relock()
        closeAll()
        val s = SaverView(this, Wallpapers.screensaver, proxy, wallpaperPicture)
        saver = s
        root.addView(s)
        // Home underneath would be drawn for nothing: hide it and the wallpaper.
        home.visibility = android.view.View.INVISIBLE
        window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
        // Holding the screen on keeps the firmware's screensaver from starting over ours; after a
        // long stretch it lets go so the TV's own power policy can still turn the panel off.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }

    private val releaseScreen = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun hideSaver() {
        val s = saver ?: return
        saver = null
        s.release()
        root.removeView(s)
        handler.removeCallbacks(releaseScreen)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        home.visibility = android.view.View.VISIBLE
        (if (inGrid) gridBackdrop else backdrop)?.let { window.setBackgroundDrawable(it) }
        home.refreshClock()
        resetIdle()
    }

    private val idle = Runnable { if (resumed && panels.none { it is PinPad }) showSaver() }

    private fun resetIdle() {
        handler.removeCallbacks(idle)
        if (resumed && saver == null && LiteSettings.screensaverMinutes > 0) {
            handler.postDelayed(idle, idleBeforeSaver())
        }
    }

    /**
     * The full app's rule: Aerial's screensaver has to start before the firmware's (ad-carrying)
     * one. Xiaomi keeps that delay in system/screen_saver_timeout; start 15 s ahead of it when it
     * is shorter than the chosen delay, and never sooner than 30 s.
     */
    private fun idleBeforeSaver(): Long {
        val chosen = LiteSettings.screensaverMinutes * 60_000L
        val firmware = runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull()?.takeIf { it > 0 }
        return if (firmware != null) minOf(chosen, maxOf(30_000L, firmware - 15_000L)) else chosen
    }

    /**
     * What the TV starts on, as the full app: once, within a few minutes of power-on, the app the
     * owner chose from its tile's menu, after the network for up to 20 s (a live TV app opened
     * before it shows an error and stays on it, the full app's issue #553).
     */
    private fun openBootApp() {
        val key = LiteSettings.bootApp ?: return
        // Once per power-on, kept on disk: a firmware that stops Lite in the background made each
        // cold start back in it open the app again (the full app's issue #766, Coocaa).
        val prefs = getSharedPreferences("boot_open", MODE_PRIVATE)
        val bootStamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val openedAt = prefs.getLong("elapsed", -1L)
        if (openedAt in 0..SystemClock.elapsedRealtime() && Math.abs(prefs.getLong("boot", 0L) - bootStamp) < 2 * 60 * 1000L) {
            Log.i(TAG, "already opened $key since power-on")
            return
        }
        val launch = entry(key)?.launchIntent() ?: runCatching {
            (if (android.os.Build.VERSION.SDK_INT >= 21) packageManager.getLeanbackLaunchIntentForPackage(key) else null)
                ?: packageManager.getLaunchIntentForPackage(key)
        }.getOrNull() ?: return
        val since = SystemClock.elapsedRealtime()
        val tryOpen = object : Runnable {
            override fun run() {
                if (isFinishing) return
                val online = ControlCenter.network(this@HomeActivity).glyph != Glyph.Offline
                if (!online && SystemClock.elapsedRealtime() - since < 20_000) { handler.postDelayed(this, 500); return }
                Log.i(TAG, "opening $key, what the TV starts on")
                prefs.edit().putLong("elapsed", SystemClock.elapsedRealtime()).putLong("boot", bootStamp).apply()
                open(launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
        }
        handler.postDelayed(tryOpen, 1500)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "could not open $intent", t)
        }
    }

    // ---- the phone page ----

    /** What the phone page changes, called on the main thread; Home and Personalize follow. */
    private val phoneOps = object : PhoneServer.Ops {
        override fun home(): List<AppEntry> = home.apps
        override fun folderApps(): Map<String, List<AppEntry>> = folderApps
        override fun hidden(): List<AppEntry> = hiddenApps
        override fun ownArt(app: AppEntry): Bitmap? = AppCatalog.ownArt(this@HomeActivity, app)
        override fun move(key: String, target: String) { layout.move(key, target); arrange() }
        override fun moveInFolder(key: String, target: String) { layout.moveInFolder(key, target); arrange() }
        override fun hide(key: String) { layout.hide(key); arrange() }
        override fun unhide(key: String) { layout.unhide(key); arrange() }
        override fun toFolder(key: String, id: String?) { entry(key)?.let { putInFolder(it, id) } }
        override fun outOfFolder(key: String) { layout.takeOutOfFolder(key); arrange() }
        override fun ungroup(id: String) { layout.ungroup(id); folderTiles.remove(id); arrange() }
        override fun renameFolder(id: String, name: String) { layout.renameFolder(id, name); arrange() }
        override fun rename(key: String, name: String?) { AppNames.rename(key, name); arrange() }
        override fun iconsChanged() = reload.run()
        override fun wallpaperChanged(rebake: Boolean) {
            Wallpapers.rebuildList()
            if (rebake) loadBackdrop()
            (panels.lastOrNull() as? Personalize)?.wallpapersChanged()
        }
        override fun settingsChanged() {
            home.applySettings()
            showWeather()
            refaceIfResized()
            (panels.lastOrNull() as? Personalize)?.refreshChips()
        }
        override fun weatherChanged() {
            showWeather()
            Weather.refreshIfStale({ work.execute(it) }, force = true) { handler.post { showWeather(); (panels.lastOrNull() as? Personalize)?.refreshChips() } }
        }
    }

    private companion object {
        const val TAG = "AerialLite"
        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L
        /** The TVs' settings apps, kept behind the PIN (the full app's SystemProbe.isSettingsApp). */
        val SETTINGS_APPS = setOf("com.android.settings", "com.android.tv.settings", "com.xiaomi.mitv.settings", "com.tcl.settings", "com.haier.tv.menusetting", "com.pptv.setting")
    }
}
