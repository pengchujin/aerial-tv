package app.orchard.tv

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.FOLDER_KEY_PREFIX
import app.orchard.tv.apps.SOURCE_KEY_PREFIX
import app.orchard.tv.apps.LayoutStore
import app.orchard.tv.apps.TileArt
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.apps.folderEntry
import app.orchard.tv.apps.sourceEntry
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Brightness
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.system.TvSource
import app.orchard.tv.system.VolumeStatus
import app.orchard.tv.ui.home.PinMode
import app.orchard.tv.ui.home.ScreensaverSkips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Overlay {
    data object None : Overlay
    data class ControlCenter(val page: CcPage = CcPage.Main) : Overlay
    /** [inFolder]: the menu was opened inside that folder, and closes back to it. */
    data class AppMenu(val key: String, val anchor: Rect, val inFolder: String? = null) : Overlay
    data class Folder(val id: String) : Overlay
    data object HiddenApps : Overlay
    /** App Info drawn by Aerial, on firmware that has no screen for it. */
    data class AppInfo(val packageName: String) : Overlay
    data object Wallpaper : Overlay
    data class Pin(val mode: PinMode, val onDone: () -> Unit) : Overlay
}

enum class CcPage { Main, Inputs, InputSettings, Update, Setup }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val store = LayoutStore(app)
    private var all: List<AppEntry> = emptyList()

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var hiddenApps by mutableStateOf<List<AppEntry>>(emptyList()); private set

    /** Each folder's apps, by folder id, in the folder's order. */
    var folderApps by mutableStateOf<Map<String, List<AppEntry>>>(emptyMap()); private set

    /** A folder's tile picture with the apps it was made from, so it is redrawn only when they change. */
    private val folderArt = HashMap<String, Pair<List<TileArt>, TileArt>>()
    var loaded by mutableStateOf(false); private set

    var overlay by mutableStateOf<Overlay>(Overlay.None)
    var movingKey by mutableStateOf<String?>(null); private set

    var network by mutableStateOf(NetworkStatus()); private set
    var volume by mutableStateOf(VolumeStatus()); private set
    var sources by mutableStateOf<List<TvSource>>(emptyList()); private set
    var homeStatus by mutableStateOf(HomeStatus.NotDefault); private set

    /** Panel brightness 0..1, or null when Aerial cannot change it on this TV. */
    var brightness by mutableStateOf<Float?>(null); private set

    /**
     * Set when Home is pressed: go back to the top row. Done once the window has focus again,
     * since a focus request made while another app is in front is lost.
     */
    var homeResetPending by mutableStateOf(false)

    /** When a key, a touch or coming back to the front last reset the idle timer (uptime ms). */
    var lastInputAt by mutableLongStateOf(0L)

    /** Home is the resumed activity: the aerial wallpaper only moves while it is. */
    var resumed by mutableStateOf(false)

    /** Aerial's screensaver is up (see MainActivity's idle timer). */
    var screensaverVisible by mutableStateOf(false)
    val screensaverSkips = ScreensaverSkips()

    private var loadJob: Job? = null

    init {
        reload()
        // A tile picture set or removed from the phone page.
        viewModelScope.launch { snapshotFlow { CustomIcons.version }.drop(1).collect { reload() } }
        offerSetupOnce()
        // An input's device or name, chosen from its tile's menu or the phone page.
        viewModelScope.launch { snapshotFlow { SourceLooks.all }.drop(1).collect { if (loaded) arrange() } }
    }

    /**
     * Installed from the APK alone (issue #213): nothing granted Aerial what install.sh does, and
     * Home is still the stock launcher. Control Center's setup page says how to finish, once, a
     * few seconds in, when Home Guard would have bound if it were on.
     */
    private fun offerSetupOnce() {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("setup_offered", false)) return
        viewModelScope.launch {
            delay(4_000)
            val granted = context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val status = SystemProbe.homeStatus(context)
            if (status == HomeStatus.NotDefault && !granted && (overlay != Overlay.None || screensaverVisible)) return@launch
            prefs.edit().putBoolean("setup_offered", true).apply()
            if (status == HomeStatus.NotDefault && !granted) overlay = Overlay.ControlCenter(CcPage.Setup)
        }
    }

    /** PackageManager's change counter when the list was last loaded (Android 8+). */
    private var packagesSeen = 0

    /**
     * Apps installed or removed while Aerial was in the background (from an app store in front)
     * send no broadcast Aerial hears: it listens only while started. Catch up when Home comes back
     * (issue #55). Before Android 8 there is no change counter; the reload is cheap, tiles are cached.
     */
    fun reloadIfPackagesChanged() {
        if (!loaded) return
        if (Build.VERSION.SDK_INT >= 26 && context.packageManager.getChangedPackages(packagesSeen) == null) return
        reload()
    }

    fun reload() {
        if (Build.VERSION.SDK_INT >= 26) {
            packagesSeen = context.packageManager.getChangedPackages(0)?.sequenceNumber ?: packagesSeen
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ctx = context
            val started = SystemClock.uptimeMillis()
            all = AppCatalog.load(ctx)
            DiagLog.i("${all.size} apps in ${SystemClock.uptimeMillis() - started} ms")
            sources = withContext(Dispatchers.Default) { SystemProbe.sources(ctx) }
            arrange()
            if (!loaded) DiagLog.i("Home loaded: ${apps.size} tiles, ${sources.size} inputs, ${SystemClock.uptimeMillis() - started} ms")
            loaded = true
        }
    }

    private fun arrange() {
        // Pinned TV inputs share the grid, order and hiding with the apps.
        val pinned = sources.filter { it.id in AerialSettings.pinnedSources }.map { sourceEntry(context, it) }
        AppCatalog.pinnedSources = pinned
        val arrangement = store.arrange(all + pinned) { folder, inside ->
            val shown = inside.take(9).map { it.art }
            val art = folderArt[folder.id]?.takeIf { (made, _) -> made.size == shown.size && made.indices.all { made[it] === shown[it] } }?.second
                ?: TileArtFactory.forFolder(shown).also { folderArt[folder.id] = shown to it }
            folderEntry(folder, art)
        }
        apps = arrangement.home
        folderApps = arrangement.folders
        hiddenApps = arrangement.hidden
    }

    /** An app or folder on Home, or an app inside a folder. */
    fun entry(key: String): AppEntry? = apps.firstOrNull { it.key == key } ?: folderApps.values.flatten().firstOrNull { it.key == key }

    /** The folders on Home, in order, for "Move to Folder". */
    fun folders() = store.folders()

    /**
     * Puts [key] into folder [id], or into a new folder named after what the app is, which takes
     * its place on Home. Returns the folder's key.
     */
    fun putInFolder(key: String, id: String?): String {
        val names = context.resources.getStringArray(R.array.folder_names)
        val app = entry(key)
        // Firmware apps are what most folders on a TV are for.
        val base = if (app != null && !app.removable && app.source == null) names[0] else context.getString(R.string.folder_default)
        val taken = store.folders().map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        val folder = store.putInFolder(key, id, name)
        arrange()
        return FOLDER_KEY_PREFIX + folder
    }

    fun takeOutOfFolder(key: String) {
        store.takeOutOfFolder(key)
        arrange()
    }

    fun renameFolder(id: String, name: String) {
        store.renameFolder(id, name)
        arrange()
    }

    fun ungroup(id: String) {
        store.ungroup(id)
        folderArt.remove(id)
        arrange()
    }

    /** Drops the picture chosen on the phone page; the tile goes back to the app's own artwork. */
    fun resetIcon(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CustomIcons.clear(key) }
            CustomIcons.changed()
        }
    }

    /** A line at the top of Home that goes away by itself, confirming a choice made out of sight. */
    var notice by mutableStateOf<String?>(null); private set
    private var noticeJob: Job? = null

    fun notify(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4000)
            notice = null
        }
    }

    /** What the TV opens when switched on, named as Home names it; null for nothing. */
    fun bootTargetLabel(): String? {
        AerialSettings.bootApp?.let { pkg -> return entry(pkg)?.label ?: hiddenApps.firstOrNull { it.key == pkg }?.label ?: pkg }
        val id = AerialSettings.bootSource ?: return null
        return entry(SOURCE_KEY_PREFIX + id)?.label ?: sources.firstOrNull { it.id == id }?.label
    }

    /**
     * Opens [pkg] or the input [sourceId] when the TV is switched on, or nothing with both null,
     * and says so: the choice is made in a menu, and what it replaced is otherwise gone unseen.
     */
    fun chooseBootTarget(pkg: String? = null, sourceId: String? = null) {
        val before = bootTargetLabel()
        if (pkg != null) AerialSettings.chooseBootApp(pkg) else AerialSettings.chooseBootSource(sourceId)
        val after = bootTargetLabel()
        when {
            after == null -> before?.let { notify(context.getString(R.string.notice_boot_off, it)) }
            before != null && before != after -> notify(context.getString(R.string.notice_boot_switch, after, before))
            else -> notify(context.getString(R.string.notice_boot_on, after))
        }
    }

    /** Pins a TV input to Home (as the first tile, where it is easiest to reach) or takes it off. */
    fun setSourcePinned(source: TvSource, pinned: Boolean) {
        AerialSettings.setPinned(source.id, pinned)
        arrange()
        if (pinned) {
            val key = apps.firstOrNull { it.source?.id == source.id }?.key
            val first = apps.firstOrNull()?.key
            if (key != null && first != null && key != first) {
                store.move(key, first)
                arrange()
            }
        }
    }

    fun refreshSystem() {
        viewModelScope.launch {
            val ctx = context
            val (n, v, s) = withContext(Dispatchers.Default) {
                Triple(SystemProbe.network(ctx), SystemProbe.volume(ctx), SystemProbe.sources(ctx))
            }
            network = n; volume = v
            // TvSource holds an Intent, which has no value equality; compare what a tile shows.
            fun shown(list: List<TvSource>) = list.map { Triple(it.id, it.label, it.deviceName) }
            if (shown(s) != shown(sources)) { sources = s; arrange() } else sources = s
            homeStatus = SystemProbe.homeStatus(ctx)
            brightness = Brightness.level(ctx)
        }
    }

    /**
     * What Control Center shows that changes while it is open with nothing to tell us: whether Home
     * Guard is bound (it binds a few seconds after Aerial starts, and the panel kept showing "not the
     * default home screen" until it was opened again) and the brightness, if something else sets it.
     * The volume and the network have broadcasts of their own (refreshSystem, refreshNetwork), and the
     * input list is not rebuilt here. Assigning equal values redraws nothing.
     */
    fun refreshPanel() {
        viewModelScope.launch {
            val ctx = context
            val (home, level) = withContext(Dispatchers.Default) { SystemProbe.homeStatus(ctx) to Brightness.level(ctx) }
            homeStatus = home
            brightness = level
        }
    }

    fun refreshNetwork() { network = SystemProbe.network(context) }

    fun adjustVolume(direction: Int) {
        SystemProbe.adjustVolume(context, direction)
        volume = SystemProbe.volume(context)
    }

    fun adjustBrightness(direction: Int) {
        brightness = Brightness.adjust(context, direction)
    }

    fun toggleMute() {
        SystemProbe.toggleMute(context)
        volume = SystemProbe.volume(context)
    }

    /** Runs [action] at once, or after the parental PIN when one is set (issue #10). */
    fun whenUnlocked(action: () -> Unit) {
        if (!AerialSettings.locked) action()
        else overlay = Overlay.Pin(PinMode.Unlock) { overlay = Overlay.None; action() }
    }

    fun hide(key: String) {
        store.hide(key)
        arrange()
    }

    fun unhide(key: String) {
        store.unhide(key)
        arrange()
    }

    fun startMove(key: String) {
        overlay = Overlay.None
        movingKey = key
    }

    /** Moves the app being moved by [delta] slots in the visible grid. */
    fun moveBy(delta: Int) {
        val key = movingKey ?: return
        val from = apps.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, apps.lastIndex)
        if (to == from) return
        store.move(key, apps[to].key)
        arrange()
    }

    fun endMove() { movingKey = null }

    /**
     * Home was pressed. On Home itself focus goes back to the top row, as on tvOS; coming back
     * from an app opened from the grid, focus stays on that app's tile.
     */
    fun onHomePressed(resetFocus: Boolean = true) {
        screensaverVisible = false
        AerialSettings.relock()
        overlay = Overlay.None
        movingKey = null
        if (resetFocus) homeResetPending = true
    }
}
