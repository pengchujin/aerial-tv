package app.orchard.tv

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.apps.AppNames
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.StandIn
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.ui.home.HomeScreen
import app.orchard.tv.ui.home.AerialStart
import app.orchard.tv.ui.home.LiveFilm
import app.orchard.tv.ui.home.AppLaunchTransitions
import app.orchard.tv.ui.home.HomeLaunchOrigin
import app.orchard.tv.ui.home.LocalAppLaunchTransitions
import app.orchard.tv.ui.home.LaunchPhase
import app.orchard.tv.ui.home.LaunchEntryKind
import app.orchard.tv.ui.home.LaunchSource
import app.orchard.tv.ui.home.AppLaunchZoom
import app.orchard.tv.ui.home.LaunchSessionMachine
import app.orchard.tv.ui.home.ZOOM_DISPATCH_MS
import app.orchard.tv.ui.home.HeldWork
import app.orchard.tv.ui.home.RETURN_QUIET_MAX_MS
import app.orchard.tv.ui.home.ZOOM_LEFTOVER_MS
import app.orchard.tv.ui.home.AWAY_RELEASE_AFTER_MS
import app.orchard.tv.ui.home.LaunchZoomHome
import app.orchard.tv.ui.home.trimLetsGo
import app.orchard.tv.settings.Effects
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.home.TileFaces
import app.orchard.tv.ui.theme.FontWarmup
import app.orchard.tv.ui.theme.canvasDensity
import app.orchard.tv.ui.components.UhdCanvas
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.system.BootOpen
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.FrameStats
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.system.SystemScreensaver
import app.orchard.tv.system.Diagnostics
import app.orchard.tv.system.MissingClipboard
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.system.HisenseSources
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.settingsLaunchIntent
import app.orchard.tv.update.SelfCompile
import app.orchard.tv.update.Updates
import app.orchard.tv.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm by viewModels<HomeViewModel>()
    private val appTransitions by viewModels<AppLaunchTransitions>()
    private var reduceAppMotion = false
    private var launchTimeout: Runnable? = null
    // An options construction failure disables it here; never retry a sent Activity intent.
    private var scaleUpAvailable = true
    // The same for the no-transition options under Aerial's own zoom.
    private var zoomOptionsAvailable = true
    /** The zoom's pending Activity intent (ZOOM_DISPATCH_MS after the click); at most one. */
    private var zoomDispatch: Runnable? = null
    /** Paused for a zoom session without being stopped yet; see LaunchSessionMachine.coverTimeout. */
    private var zoomCoverCheck: Runnable? = null

    private fun clearZoomCallbacks() {
        zoomDispatch?.let { idleHandler.removeCallbacks(it) }
        zoomDispatch = null
        zoomCoverCheck?.let { idleHandler.removeCallbacks(it) }
        zoomCoverCheck = null
    }

    // Back in front and focused: by now any return has shrunk into its tile (the spring settles in
    // well under a second), so an overlay still up is left over and must not hide Home.
    private val zoomLeftoverCheck = Runnable {
        if (vm.resumed && window.decorView.hasWindowFocus()) appTransitions.dropIfStale("still up after the return")
    }

    private fun scheduleZoomLeftoverCheck() {
        idleHandler.removeCallbacks(zoomLeftoverCheck)
        idleHandler.postDelayed(zoomLeftoverCheck, ZOOM_LEFTOVER_MS)
    }

    /**
     * The work of coming to the front that waits while the zoom shrinks back (startWorkHeavy, the
     * system refresh of resumeWork): on MiTV-AZFP0 it ran under the shrink and every frame of it
     * took 30-130 ms. Run once the return's quiet time ends (AppLaunchTransitions.returnQuiet), at
     * the latest RETURN_QUIET_MAX_MS after it began; each part checks the state Aerial is in by
     * then, and what no longer applies is left to the next start or resume.
     */
    private val held = HeldWork()
    private val releaseHeld = Runnable { held.release() }
    private val quietTimeout = Runnable { appTransitions.endQuiet("timeout") }

    /** True if the work of this start or resume waits for the return's shrink. */
    private fun holdForReturn(): Boolean {
        val already = appTransitions.returnQuiet
        if (!appTransitions.holdForReturn()) return false
        if (!already) {
            idleHandler.removeCallbacks(quietTimeout)
            idleHandler.postDelayed(quietTimeout, RETURN_QUIET_MAX_MS)
        }
        return true
    }

    /** Held while the return is quiet; at once if its quiet time has already ended. */
    private fun holdOrRun(key: String, work: () -> Unit) {
        if (appTransitions.returnQuiet) held.hold(key, work) else work()
    }

    /**
     * While an app is in front (stopped for AWAY_RELEASE_AFTER_MS): lets go of what is big and made
     * again cheaply, once per stop. The return does not wait for any of it, it shows the picture of
     * Home. Not the wallpaper's film (LiveWallpaper lets its decoder go as Aerial stops), the
     * wallpaper's own pictures (a decode on return, with the window showing black meanwhile), nor
     * the tile faces at rest (the row's first frame).
     */
    private val releaseWhileAway = Runnable {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@Runnable
        // The thumbnails of the wallpaper picker (about 330 KB each) and the focused tile faces
        // (about 250 KB each), except the launched tile's, which the return lands on.
        Wallpapers.trimMemory()
        TileFaces.trimMemory(keepFocusedOf = appTransitions.zoom?.art?.back)
        DiagLog.i("away: picker thumbnails and focused tile faces let go; picture of Home held: ${appTransitions.snapshotBytes() / 1024} KB")
    }

    private fun onReturnQuietEnd() {
        idleHandler.removeCallbacks(quietTimeout)
        idleHandler.removeCallbacks(releaseHeld)
        // Posted: never inside the lifecycle call (or the frame) that ended the quiet time.
        if (!held.isEmpty) idleHandler.post(releaseHeld)
    }

    /** The zoom's state for lifecycle log lines, while there is a zoom session. */
    private fun zoomNote(): String =
        if (appTransitions.session?.zoom == true || appTransitions.zoom != null) "; app zoom: ${appTransitions.describe()}" else ""

    /** Whether a settings app's entry opens a full-screen window (SystemProbe.opensOpaqueActivity), per component. */
    private val opaqueSettings = HashMap<String, Boolean>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            opaqueSettings.clear()
            vm.reload()
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshVolume()
    }

    /** Midnight, or the clock set at boot: a new daily photo, and the next of the rotating ones. */
    private val dayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Wallpapers.refreshDailyIfStale()
            Wallpapers.rotateIfDue()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread {
            vm.refreshNetwork()
            Wallpapers.refreshDailyIfStale(networkCameUp = true)
            Weather.refreshIfStale(networkCameUp = true)
            Updates.checkIfStale(networkCameUp = true)
        }
        override fun onLost(network: Network) = runOnUiThread { vm.refreshNetwork() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = runOnUiThread { vm.refreshNetwork() }
    }

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    /**
     * Started at power-on before the user is unlocked. Home is direct-boot aware so that it, not the
     * firmware's launcher, is what shows then (TCL MT5879 showed its own launcher for a few
     * seconds, PR #334). Until unlock nothing in credential storage can be read (settings, the
     * PIN, photos, the app list), so it shows only the picture Wallpapers keeps for this and
     * starts for real when the user is unlocked.
     */
    private var locked = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = startReal(null)
    }

    private fun showLocked() {
        locked = true
        DiagLog.i("onCreate before the user is unlocked; showing the kept backdrop")
        val picture = Wallpapers.lockedBootBackdrop(this)?.takeIf { it.exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        window.setBackgroundDrawable(picture?.let { BitmapDrawable(resources, it) } ?: ColorDrawable(Color.BLACK))
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_EXPORTED)
        // Unlocked between the check and the registration.
        if (GuardSetup.isUserUnlocked(this)) startReal(null)
    }

    private var realStarted = false

    /**
     * Where the firmware ships its own FallbackHome (not one of AOSP's), leaves the locked boot to
     * it: Aerial only comes back once the user is unlocked, by the guard or the boot receiver.
     * Redmi Projector 4 and 5 Pro (ASU mt5873, Android 13, com.asu.settings.FallbackHome) stayed
     * on the boot animation for good, with no network and no ADB, from 1.47 on, when Home Guard
     * stopped crashing the process before the unlock and the locked start below got to stay in
     * front; 1.36, before Home was direct-boot aware, booted fine (issues #684 #688). Not seen on
     * that TV: we have none; the hand-over itself was walked on the Android 13 Google TV emulator.
     * Once per process, so a FallbackHome that steps aside before the unlock cannot start a loop;
     * and only when it starts, or Home would be restarted to Aerial again.
     */
    private fun handOverToFirmwareFallbackHome(): Boolean {
        if (handedOverAtBoot) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val fallback = runCatching { packageManager.queryIntentActivities(home, PackageManager.MATCH_SYSTEM_ONLY) }
            .getOrDefault(emptyList())
            .map { it.activityInfo }
            .firstOrNull { it.packageName != packageName && it.name.endsWith(".FallbackHome") && it.name !in AOSP_FALLBACK_HOMES }
            ?: return false
        handedOverAtBoot = true
        val started = runCatching {
            startActivity(home.setClassName(fallback.packageName, fallback.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        DiagLog.i("onCreate before the user is unlocked; ${if (started) "left to" else "could not start"} ${fallback.packageName}/${fallback.name}")
        if (started) finish()
        return started
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagLog.catchCrashes()
        if (!GuardSetup.isUserUnlocked(this)) {
            super.onCreate(null)
            if (handOverToFirmwareFallbackHome()) return
            hideSystemBars()
            showLocked()
            return
        }
        super.onCreate(savedInstanceState)
        startReal(savedInstanceState)
    }

    /**
     * The real start, in the window the locked instance already shows. Recreating the activity at
     * the unlock left the screen black for the second or two the new window took: unlock 20.4 s,
     * new window 21.9 s, first frame 22.5 s on an OPPO K9 (ColorOS TV 2.4, 2026-10-24).
     */
    private fun startReal(savedInstanceState: Bundle?) {
        if (realStarted) return
        realStarted = true
        val fromLocked = locked
        if (locked) {
            locked = false
            runCatching { unregisterReceiver(unlocked) }
        }
        DiagLog.init(this)
        DiagLog.i("onCreate ${intent.action} restored=${savedInstanceState != null}; ${DiagLog.device()}")
        hideSystemBars()
        // Unlocked at power-on: what the system kept. Unlocked under the locked window: nothing to
        // restore, and this is the boot's first real start.
        val fresh = savedInstanceState == null
        FontWarmup.start(this)
        val uhd = UhdCanvas.scale(this)
        Wallpapers.init(this)
        Weather.init(this)
        Updates.init(this)
        lifecycleScope.launch(Dispatchers.IO) { SelfCompile.init(this@MainActivity) }
        CustomIcons.init(this)
        IconLibrary.init(this)
        SourceLooks.init(this)
        AppNames.init(this)
        AerialSettings.init(this)
        // After the settings: the artwork's size follows the number of apps a row.
        TileArtFactory.useCanvasDensity(canvasDensity(resources.displayMetrics) * uhd)
        Sounds.init(this)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        reduceAppMotion = reducedMotion
        appTransitions.onQuietEnd = { onReturnQuietEnd() }

        // The home screen paints every pixel itself; a window background would be one more
        // full-screen fill per frame for the TV's GPU. Only until Home's first frame, the backdrop
        // baked at the last start, so the window never shows black while Compose sets up. Without
        // one, keep what the window already shows (the kept backdrop of a locked start).
        val baked = Wallpapers.backdrop
        if (baked != null) window.setBackgroundDrawable(BitmapDrawable(resources, baked.asAndroidBitmap()))
        // Clear whatever the window showed until the first frame (the baked backdrop, or the kept
        // one of a locked start): from then on Home paints every pixel itself.
        window.decorView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                // Not from inside the draw itself.
                idleHandler.post {
                    window.setBackgroundDrawable(null)
                    window.decorView.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        })
        if (DiagLog.modeSwitch(this)) preferSmoothestRefreshRate() else DiagLog.i("refresh rate left as it is (mode_switch off)")
        if (DiagLog.softLayer(this) && uhd == 1f) window.decorView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        FrameStats.attach(window)
        logWindow()

        val home: @Composable () -> Unit = {
            // TVs ship at different densities for the same 1080p panel (Xiaomi 320dpi, TCL 240dpi),
            // which would make the same layout bigger on one and smaller on the other. Aerial is
            // designed on a 960×540 canvas, so it scales that canvas to whatever the panel is.
            val canvas = Density(
                density = canvasDensity(resources.displayMetrics),
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(
                LocalReducedMotion provides reducedMotion,
                LocalDensity provides canvas,
                LocalAppLaunchTransitions provides appTransitions,
            ) {
                // Aerial's app-opening zoom over the whole of Home (folders and panels included),
                // which is not drawn under it once it covers the screen.
                Box(Modifier.fillMaxSize()) {
                    // Recorded in a layer of its own, which the zoom's picture of Home is taken from.
                    LaunchZoomHome(appTransitions) {
                        HomeScreen(
                            vm = vm,
                            onLaunch = ::launch,
                            onOpen = ::open,
                            onAppInfo = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            onUninstall = { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            // Posted so the key-up of the Back press that started it does not end it at once.
                            onStartScreensaver = { idleHandler.postDelayed({ startScreensaver(asked = true) }, 150) },
                        )
                    }
                    AppLaunchZoom(appTransitions)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && uhd > 1f) {
            setContentView(UhdCanvas.host(this, ComposeView(this).apply { setContent(home) }, uhd))
        } else {
            setContent(content = home)
        }

        ContextCompat.registerReceiver(
            this, screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Control Center shows things that change while it is open (Home Guard binds a few seconds
        // after Aerial starts): look again every few seconds, only while it is open and Aerial is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { vm.overlay is Overlay.ControlCenter }.collectLatest { open ->
                    while (open) {
                        delay(PANEL_REFRESH_MS)
                        vm.refreshPanel()
                    }
                }
            }
        }
        if (fresh && firstStartSinceBoot()) openBootSource("boot")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) {
            appTransitions.cancel()
            openBootSource("brought back on wake")
        }
        if (intent.getBooleanExtra(EXTRA_REOPEN_BOOT, false)) openBootSource("covered by the stock launcher's boot screen", again = true)
        if (fromLocked) {
            // This window already went through onStart and onResume while locked (both returned
            // early) and the system will not call them again unless it stops and starts the
            // activity: do their work for the state it is in now. Doing it unconditionally would
            // register receivers while stopped, or mark Aerial resumed with the screen off.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startWork()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeWork()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * The system is short of memory. On a 1-2 GB TV Aerial is killed in the background while a
     * video plays, and Home then starts cold at the next press of Home (issue #294); what it can
     * make again cheaply goes first, so that it is less likely to be the process picked.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory running low, or Aerial in the background on the system's list to kill. Not merely
        // Home out of sight (UI_HIDDEN, every time an app opens): coming back, the first moves along
        // the row would make the focused faces again on the main thread.
        if (!trimLetsGo(level)) return
        DiagLog.i("trim memory, level $level")
        Wallpapers.trimMemory()
        TileFaces.trimMemory()
        // The picture of Home a zoom keeps for its return is made again by the next click.
        appTransitions.releaseSnapshot("memory, level $level")
    }

    override fun onDestroy() {
        launchTimeout?.let { idleHandler.removeCallbacks(it) }
        clearZoomCallbacks()
        idleHandler.removeCallbacks(zoomLeftoverCheck)
        idleHandler.removeCallbacks(releaseWhileAway)
        // The transitions outlive this Activity (configuration change): nothing of it is called later.
        appTransitions.onQuietEnd = null
        appTransitions.endQuiet("destroyed")
        appTransitions.releasePause("destroyed")
        held.drop()
        idleHandler.removeCallbacks(quietTimeout)
        idleHandler.removeCallbacks(releaseHeld)
        if (locked) runCatching { unregisterReceiver(unlocked) }
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
    }

    // ---- The input the TV starts on (issue #25) ----

    private var bootSourceOpenedAt = Long.MIN_VALUE / 2

    /**
     * Woken with Aerial still in front (TCL keeps it through standby). Where the firmware killed
     * Aerial instead (Xiaomi), Home Guard brings it back with [EXTRA_WOKE].
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                appTransitions.cancel()
                launchTimeout?.let { idleHandler.removeCallbacks(it) }
                clearZoomCallbacks()
                BootOpen.noteScreenOff(context)
                return
            }
            idleHandler.postDelayed({
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) openBootSource("screen on")
            }, 1500)
        }
    }

    /** Once per boot (Settings.Global.BOOT_COUNT, Android 7+), within a few minutes of it. */
    private fun firstStartSinceBoot(): Boolean {
        if (SystemClock.elapsedRealtime() > 3 * 60 * 1000L) return false
        if (Build.VERSION.SDK_INT < 24) return true
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = getSharedPreferences("system", MODE_PRIVATE)
        if (boot < 0 || prefs.getInt("boot_source_boot", -2) == boot) return false
        prefs.edit().putInt("boot_source_boot", boot).apply()
        return true
    }

    /**
     * Opens the input or app the owner wants the TV to start on, if any. Both list with the apps,
     * so wait for them a moment. Several routes may fire for one wake; the first wins.
     */
    /** [again]: the target was opened and then covered at once (Home Guard's EXTRA_REOPEN_BOOT). */
    private fun openBootSource(reason: String, again: Boolean = false) {
        val id = AerialSettings.bootSource
        val pkg = AerialSettings.bootApp
        if (id == null && pkg == null) return
        if (!again && SystemClock.elapsedRealtime() - bootSourceOpenedAt < 15_000) return
        if (!again && !BootOpen.due(this)) {
            android.util.Log.i("Aerial", "$reason: already opened ${id ?: pkg} since the TV came on")
            return
        }
        bootSourceOpenedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            // An app opens from its own entry at once: listing every app took 6.5 s at power-on on
            // Xiaomi MiTV-MFTR0 once Aerial came up earlier in the boot, all of it on PatchWall's
            // heels. Inputs and Aerial's own tiles (AppCatalog.shortcuts) only Home lists.
            val direct = pkg?.let { p ->
                runCatching {
                    val launch = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)
                    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        ?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() to it }
                }.getOrNull()
            }
            // Inputs can take a while to list right after a wake (Xiaomi MiTV-MTEQ0, issue #111).
            val intent = direct ?: withTimeoutOrNull(30_000) {
                snapshotFlow {
                    if (id != null) vm.sources.firstOrNull { it.id == id }?.let { it.label to it.intent }
                    else vm.entry(pkg!!)?.let { it.label to it.launchIntent() }
                }.filterNotNull().first()
            } ?: run {
                android.util.Log.w("Aerial", "$reason: ${id ?: pkg} not listed; not opening it")
                return@launch
            }
            // A live TV app opened before the network is up shows an error and stays on it: a box
            // that boots fast had no network yet (issue #553). Inputs need none.
            if (id == null && SystemProbe.network(this@MainActivity).kind == NetKind.Offline) {
                withTimeoutOrNull(BOOT_APP_NETWORK_WAIT_MS) {
                    while (SystemProbe.network(this@MainActivity).kind == NetKind.Offline) delay(500)
                }
            }
            android.util.Log.i("Aerial", "$reason: opening ${intent.first}, what the TV starts on")
            BootOpen.noteOpened(this@MainActivity)
            openedAppSinceHome = true
            open(intent.second)
        }
    }

    /**
     * Runs Home at the fastest refresh rate the panel can switch to without going black, so focus
     * and scrolling move as smoothly as the TV allows (a 120 Hz TV that idles its UI at 60 Hz).
     * Only seamless switches, which Android 12 can name: on a TV a mode change is otherwise a second
     * of black screen, every time Home comes back. Xiaomi MiTV-MFTR0 and MFFU1 have a single 60 Hz
     * mode; TCL MT5879 (Android 11) lists 50 to 120 Hz but cannot say which switches are seamless,
     * so both stay as they are.
     */
    private fun preferSmoothestRefreshRate() {
        if (Build.VERSION.SDK_INT < 31) return
        // Twice the frames is twice the GPU's work: Lite stays at the rate the TV chose.
        if (AerialSettings.lite) return DiagLog.i("refresh rate left as it is (lite effects)")
        val display = display ?: return
        val current = display.mode
        val seamless = current.alternativeRefreshRates
        val best = display.supportedModes
            .filter { m ->
                m.physicalWidth == current.physicalWidth && m.physicalHeight == current.physicalHeight &&
                    m.refreshRate <= 120.5f && seamless.any { kotlin.math.abs(it - m.refreshRate) < 0.5f }
            }
            .maxByOrNull { it.refreshRate }
            ?.takeIf { it.refreshRate > current.refreshRate + 0.5f } ?: return
        DiagLog.i("refresh rate ${current.refreshRate} -> ${best.refreshRate} Hz (mode ${best.modeId})")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    // ---- What Home's window does, for a TV whose logcat shows nothing (see DiagLog) ----

    private var draws = 0
    private var keysLogged = 0

    private fun logWindow() {
        val m = resources.displayMetrics
        val modes = if (Build.VERSION.SDK_INT >= 23) display()?.let { d ->
            val alt = if (Build.VERSION.SDK_INT >= 31) d.mode.alternativeRefreshRates.joinToString() else "?"
            "mode ${d.mode.modeId} ${d.mode.physicalWidth}x${d.mode.physicalHeight}@${d.mode.refreshRate} (seamless: $alt) of " +
                d.supportedModes.joinToString { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }
        } else null
        DiagLog.i(
            "display ${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi, canvas density ${canvasDensity(m)}, uhd ${UhdCanvas.scale(this)} (${UhdCanvas.why}); $modes; " +
                "hwui ${systemProperty("debug.hwui.renderer")}, soft_layer=${DiagLog.softLayer(this)}, saver_fade_offscreen=${DiagLog.saverFadeOffscreen(this)}",
        )
        window.decorView.viewTreeObserver.addOnDrawListener {
            draws++
            if (draws <= 3) DiagLog.i("draw $draws: ${window.decorView.width}x${window.decorView.height}, hw=${window.decorView.isHardwareAccelerated}")
        }
        // A heartbeat for the first two minutes: whether frames come at all, keys arrive and Home is in.
        for (s in 2..120 step 6) idleHandler.postDelayed({
            val asked = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback {
                DiagLog.i(
                    "${s}s: frame after ${SystemClock.uptimeMillis() - asked} ms, draws $draws, " +
                        "focus ${window.decorView.hasWindowFocus()}, shown ${window.decorView.isShown}, " +
                        "state ${lifecycle.currentState}, apps ${vm.apps.size} loaded ${vm.loaded}, " +
                        "screensaver ${vm.screensaverVisible}, overlay ${vm.overlay}",
                )
            }
        }, s * 1000L)
    }

    private fun display(): android.view.Display? =
        if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay

    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        DiagLog.i("window focus $hasFocus")
        // A window of the TV's over Aerial's screensaver, still resumed beneath it: a key of the
        // firmware's own was pressed. Coocaa C10's hotkey popup sometimes took focus before the
        // key's lift reached Aerial, and the screensaver went on under it (issue #915, the
        // reporter's WindowManager log).
        if (!hasFocus && vm.resumed && vm.screensaverVisible) {
            DiagLog.i("a window came over the screensaver; ending it")
            hideScreensaver()
        }
        if (hasFocus && vm.resumed) scheduleZoomLeftoverCheck()
    }

    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        if (locked) return
        // `... MainActivity report`: what tools/report.sh pastes into an issue.
        if (args?.contains("report") == true) writer.print(Diagnostics.report(this)) else DiagLog.dump(writer)
    }

    // ---- Screensaver: Aerial's own, before the firmware's ad-carrying one can start ----

    private val idleHandler = Handler(Looper.getMainLooper())
    private val showScreensaver = Runnable { if (vm.resumed) startScreensaver(asked = false) }

    /** The Dolby Vision screensaver's start, fetched to disk while Home is still up (AerialStart.prepareAhead). */
    private val prepareScreensaver = Runnable {
        if (vm.resumed && !vm.screensaverVisible) lifecycleScope.launch { AerialStart.prepareAhead(this@MainActivity, SAVER_AHEAD_MS) }
    }

    /** Home's bounce did not bring Aerial back in time: the film lets its decoder go as usual. */
    private val endHomeBounce = Runnable {
        if (vm.homeBounce) DiagLog.i("not back from Home's bounce; the film lets go")
        vm.homeBounce = false
    }

    /**
     * [asked]: Back, or "Start now", rather than the idle timer.
     *
     * Where Aerial's dream is the TV's screensaver (TCL), that dream is the one shown either way:
     * Home's own screensaver was a second one, which the TV's timer then replaced with the dream
     * (in its own quality), and which never held it off (TCL MT5879). With "System" chosen, Aerial
     * starts nothing on the timer, and Back starts the firmware's.
     */
    private fun startScreensaver(asked: Boolean) {
        appTransitions.cancel()
        AerialSettings.relock()
        if (Wallpapers.screensaver == ScreensaverMode.System) {
            if (asked && !SystemScreensaver.startFirmware(this)) DiagLog.i("no system screensaver to start")
            return
        }
        // Aerials over the aerial wallpaper: Home carries the wallpaper's film on, the same player in
        // the same format, so there is no black between them; the dream would be a new player in a
        // new window, and showed black even in 4K (TCL MT5879). Not with Dolby Vision chosen by hand,
        // which the wallpaper does not play (AerialFormats.screensaverCarriesWallpaper).
        val carry = Wallpapers.selectedId == Wallpapers.LIVE && Wallpapers.screensaver == ScreensaverMode.Aerial &&
            AerialFormats.screensaverCarriesWallpaper(this)
        if (!carry && !dreamFails(this) && SystemScreensaver.isAerials(this)) {
            // The dream's film needs the decoder the wallpaper's holds: TCL MT5879 failed Dolby
            // Vision beside it and the dream fell back to 4K HEVC.
            LiveFilm.onHome?.letDecoderGo()
            if (SystemScreensaver.startDream(this)) {
                DiagLog.i("screensaver starts as the system's dream")
                dreamAskedAt = SystemClock.uptimeMillis()
                return
            }
            LiveFilm.onHome?.goOn()
        }
        DiagLog.i("screensaver starts (${Wallpapers.screensaver})")
        vm.screensaverVisible = true
        // Holding the screen on keeps the firmware's screensaver from starting over ours. After a
        // long stretch, let go so the TV's own power policy can still turn the panel off. Not
        // where the screen never goes off: letting go only handed it to the firmware's
        // screensaver, Xiaomi's a minute later, 4 h into Aerial's (MiTV-MFTR0, 2026-10-27 03:35).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!screenNeverOff()) idleHandler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    /**
     * When Aerial last asked the system for its dream. The dream's starter closes at once and Home
     * resumes; a dream that shows pauses it again within a moment. Hisense VIDAA (MT9655, Android
     * 14, issue #525; T963D4, Android 11, issue #533) never shows it, so Home stayed in front, its
     * wallpaper's film let go for nothing.
     */
    private var dreamAskedAt = Long.MIN_VALUE / 2
    private var dreamChecking = false
    private val dreamCheck = Runnable {
        dreamChecking = false
        // Up to Android 10 the dream is a window over Home, which stays resumed: the focus says.
        if (!vm.resumed || !hasWindowFocus()) {
            noteDreamShown(this@MainActivity)
            return@Runnable
        }
        noteDreamFails(this)
        DiagLog.i("the system's dream did not show; Aerial's own screensaver instead")
        LiveFilm.onHome?.goOn()
        startScreensaver(asked = false)
    }
    private var swallowKeyUp = -1

    /** Keys whose press reached Aerial and which have not been lifted yet; see dispatchKeyEvent. */
    private val keysDown = HashSet<Int>()

    /**
     * Until when every key only finishes waking the screensaver. An IR remote can send one press as
     * two (TCL MT5879: a Down that woke it, then another that moved focus into the grid); Xiaomi's
     * Bluetooth remote never did.
     */
    private var wakeQuietUntil = 0L
    private var swallowTouch = false

    /** An app (or the stock launcher) was opened from the grid and Home has not been pressed since. */
    private var openedAppSinceHome = false

    private fun restartIdleTimer() {
        vm.lastInputAt = SystemClock.uptimeMillis()
        idleHandler.removeCallbacks(showScreensaver)
        idleHandler.removeCallbacks(prepareScreensaver)
        // Not once paused: keys still reach Aerial between onPause and the opened app's window
        // taking focus (TCL MT5879: a press 18 ms after onPause, focus gone 400 ms later), and a
        // timer set then would start the screensaver over the app's video (issue #974).
        if (!vm.resumed) return
        if (AerialSettings.screensaverMinutes != AerialSettings.NEVER) {
            val idle = idleBeforeScreensaver()
            idleHandler.postDelayed(showScreensaver, idle)
            if (idle > SAVER_AHEAD_MS) idleHandler.postDelayed(prepareScreensaver, idle - SAVER_AHEAD_MS)
            if (holdsOffTheDream() && window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // Not with "System": the firmware's screensaver is wanted then, and holding the screen on
        // held it off (Xiaomi's starts on its own timer, screen_saver_timeout).
        else if (Wallpapers.screensaver != ScreensaverMode.System &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0 && screenNeverOff()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Aerial's screensaver has to start before the firmware's (ad-carrying) one. Xiaomi keeps its
     * delay in system/screen_saver_timeout: 120000 on some MiTV-MFTR0 units, 480000 on others, -1
     * when off. Start 15 s ahead of it when it is shorter than the delay chosen in Personalize.
     */
    /**
     * "Never" for the screensaver, on a TV whose screen itself never goes off: Home keeps the
     * screen on, which also holds off the firmware's own screensaver. Xiaomi's HyperOS one is not
     * the system's (screen_saver_timeout, 8 minutes on MiTV-MFTR0) and came up over Home anyway,
     * read as the screensaver "jumping back" to Xiaomi's. Where the screen does go off, that stays.
     */
    private fun screenNeverOff(): Boolean =
        runCatching { Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(0L) >= 24 * 60 * 60 * 1000L

    private fun idleBeforeScreensaver(): Long {
        val chosen = AerialSettings.screensaverMinutes * 60_000L
        // Where Aerial's dream is the TV's screensaver, the TV starts it on its own after
        // screen_off_timeout (TCL MT5879: 2 minutes); Home starts its own first, so that the
        // aerial wallpaper's film can carry on (startScreensaver).
        val firmware = firmwareScreensaverDelay() ?: dreamDelay()?.takeUnless { holdsOffTheDream() }
        return if (firmware != null && firmware > 0) minOf(chosen, maxOf(30_000L, firmware - 15_000L))
        else chosen
    }

    private fun firmwareScreensaverDelay(): Long? =
        runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull()?.takeIf { it > 0 }

    private fun dreamDelay(): Long? =
        if (SystemScreensaver.isAerials(this)) runCatching { Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.takeIf { it > 0 } else null

    /**
     * Where the TV's own delay for Aerial's dream is shorter than the one chosen in Personalize,
     * Home holds the screen on until its own screensaver starts, rather than starting 15 s ahead
     * of the TV: Coocaa C10 keeps screen_off_timeout at 60 s and its settings do not change it, so
     * every choice came out as 45 s (#732). Only Aerial's dream is held off this way; a firmware
     * screensaver of its own (screen_saver_timeout) is still beaten to it. The flag is the Home
     * window's, so it lets go as the dream covers Home and the TV's power policy goes on from there.
     */
    private fun holdsOffTheDream(): Boolean {
        if (firmwareScreensaverDelay() != null) return false
        val dream = dreamDelay() ?: return false
        return AerialSettings.screensaverMinutes * 60_000L > dream - 15_000L
    }

    private fun hideScreensaver() {
        if (vm.screensaverVisible) DiagLog.i("screensaver ends")
        vm.screensaverVisible = false
        idleHandler.removeCallbacks(releaseScreen)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The key whose press ended the zoom's hold (AppLaunchTransitions.endHoldOnKey), until its lift; -1: none. */
    private var holdEndedKey = -1

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (locked) return super.dispatchKeyEvent(event)
        if (event.keyCode == dreamWokeKey) {
            DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} #${event.repeatCount} is the rest of the press that woke the dream")
            if (event.action == KeyEvent.ACTION_UP) { dreamWokeKey = -1; return true }
            if (event.repeatCount > 0) return true
            dreamWokeKey = -1 // a new press of the same key
        }
        // The press (and its repeats and lift) that ended a held zoom ends it and does nothing else.
        if (event.keyCode == holdEndedKey) {
            if (event.action == KeyEvent.ACTION_UP) holdEndedKey = -1
            return true
        }
        // A key while the grown zoom waits for the app to cover Aerial: back into the tile, so a key
        // never meets an overlay it cannot reach (the app may be slow, or never come).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && vm.resumed && appTransitions.endHoldOnKey()) {
            holdEndedKey = event.keyCode
            launchTimeout?.let { idleHandler.removeCallbacks(it) }
            zoomDispatch?.let { idleHandler.removeCallbacks(it) }
            zoomDispatch = null
            openedAppSinceHome = false
            return true
        }
        // A key while Aerial is in front: the user's navigation wins over any return still drawn.
        if (event.action == KeyEvent.ACTION_DOWN && vm.resumed) appTransitions.dropIfStale("key ${KeyEvent.keyCodeToString(event.keyCode)}")
        if (keysLogged++ < 200) DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} #${event.repeatCount} at ${event.eventTime}, view focused ${currentFocus != null}")
        restartIdleTimer()
        // A lift whose press never reached Aerial: Coocaa C10's hotkey service takes the press of
        // its focus and settings keys and lets only the lift through (issue #915, the reporter's
        // WindowManager log). In the screensaver that lift ends it as a press would.
        val loneLift = event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.keyCode !in keysDown
        if (event.action == KeyEvent.ACTION_DOWN) keysDown += event.keyCode else keysDown -= event.keyCode
        // The key that wakes the screensaver only wakes it, silently.
        if (!vm.screensaverVisible && event.keyCode != swallowKeyUp) Sounds.onKey(event)
        // Left / right step through the films or photos and keep the screensaver up.
        if (vm.screensaverVisible && vm.screensaverSkips.onKey(event)) return true
        if (vm.screensaverVisible && loneLift) {
            DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} lifted with no press; ending the screensaver")
            hideScreensaver()
            wakeQuietUntil = SystemClock.uptimeMillis() + WAKE_QUIET_MS
            return true
        }
        if (vm.screensaverVisible && event.action == KeyEvent.ACTION_DOWN) {
            // The key that wakes the screen only wakes it; it does not also move focus or open an app.
            hideScreensaver()
            swallowKeyUp = event.keyCode
            wakeQuietUntil = SystemClock.uptimeMillis() + WAKE_QUIET_MS
            return true
        }
        // The waking key, repeats included, through to its lift; and anything else just after.
        if (event.keyCode == swallowKeyUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowKeyUp = -1
            return true
        }
        if (event.eventTime < maxOf(wakeQuietUntil, dreamWokeAt + WAKE_QUIET_MS)) {
            DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} right after waking, ignored")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (locked) return super.dispatchTouchEvent(event)
        restartIdleTimer()
        // As with keys: the touch that ends the screensaver only ends it, through to the lift.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowTouch = vm.screensaverVisible
            if (swallowTouch) hideScreensaver()
        }
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (locked) return
        DiagLog.i("onNewIntent ${intent.action}")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) {
            appTransitions.cancel()
            openBootSource("brought back on wake")
        }
        if (intent.getBooleanExtra(EXTRA_REOPEN_BOOT, false)) openBootSource("covered by the stock launcher's boot screen", again = true)
        if (intent.action == ACTION_PREVIEW_SCREENSAVER) {
            idleHandler.post { startScreensaver(asked = true) }
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            // Since 1.11 Home Guard answers Home from an app by bringing Aerial back with this same
            // intent, so the intent alone cannot tell Home-on-Home from coming back from an app.
            val keepSource = openedAppSinceHome || appTransitions.session?.phase == LaunchPhase.Background
            if (!keepSource) appTransitions.cancel()
            vm.onHomePressed(resetFocus = !keepSource)
            // Keep Home's overlay cleanup, then reopen a still-valid folder source on return.
            val folder = appTransitions.session?.source?.folderId
            if (keepSource && folder != null && !vm.folderApps[folder].isNullOrEmpty()) {
                vm.overlay = Overlay.Folder(folder)
            }
            openedAppSinceHome = false
        }
    }

    override fun onStart() {
        super.onStart()
        if (locked) return
        startWork()
    }

    /** What onStart does once the user is unlocked; also run by a start that was locked before. */
    private fun startWork() {
        idleHandler.removeCallbacks(releaseWhileAway)
        DiagLog.i("onStart${zoomNote()}")
        SelfCompile.onHomeStarted()
        // Back from the app a tile zoomed into: the rest waits until the zoom has shrunk back.
        if (holdForReturn()) {
            holdOrRun("start") {
                // Stopped again before the shrink was over: the next start does it.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startWorkHeavy()
            }
            return
        }
        startWorkHeavy()
    }

    /** Receivers and network callbacks of startWorkHeavy are registered (onStop takes them off). */
    private var receiversOn = false

    private fun startWorkHeavy() {
        // The phone and computer page, at any time the TV is on (only once unlocked: its token is
        // in credential-protected storage).
        lifecycleScope.launch(Dispatchers.IO) { PhoneServer.listen(this@MainActivity) }
        GuardSetup.ensureEnabled(this)
        idleHandler.postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
        vm.reloadIfPackagesChanged()
        receiversOn = true
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, dayReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        getSystemService<ConnectivityManager>()?.let { cm ->
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkCallback)
            // A request leaves VPNs out unless told otherwise; the status pill shows one coming up (issue #809).
            else cm.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        }
        // Apps can change while Aerial is in the background without it hearing about it.
        vm.reload()
        Wallpapers.refreshDailyIfStale()
        Wallpapers.rotateIfDue()
        Weather.refreshIfStale()
        Updates.checkIfStale()
    }

    override fun onResume() {
        super.onResume()
        if (locked) return
        resumeWork()
    }

    /** What onResume does once the user is unlocked; also run by a start that was locked before. */
    private fun resumeWork() {
        zoomCoverCheck?.let { idleHandler.removeCallbacks(it) }
        zoomCoverCheck = null
        // Before resumed(): paused but never stopped (the app never covered Aerial in time, or
        // Home came straight back), there was no onStart to begin the quiet time.
        val quiet = holdForReturn()
        // Back from the app this tile opened: the zoom shrinks from full screen into it (AppLaunchZoom).
        appTransitions.resumed()
        vm.resumed = true
        scheduleZoomLeftoverCheck()
        idleHandler.removeCallbacks(endHomeBounce)
        vm.homeBounce = false
        DiagLog.i("onResume; screensaver after ${idleBeforeScreensaver() / 1000} s${zoomNote()}")
        if (SystemClock.uptimeMillis() - dreamAskedAt < 1_000) {
            dreamChecking = true
            idleHandler.postDelayed(dreamCheck, 1_500)
        }
        // The screensaver timer and Home Guard's state stay at once: they are cheap and what they
        // protect cannot wait.
        restartIdleTimer()
        GuardSetup.setHomeInFront(this, true)
        HomeGuardService.onAerialResumed()
        Updates.onHomeResumed()
        // Network, volume, inputs, brightness: read off the main thread, but each answer recomposes
        // the status pill and Control Center's rows, so it waits for the shrink.
        if (quiet) holdOrRun("resume") {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.refreshSystem()
        } else vm.refreshSystem()
        if (!resumedInProcess) {
            resumedInProcess = true
            val age = if (Build.VERSION.SDK_INT >= 24) SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() else -1L
            if (BootOpen.dueAfterColdWake(this, age)) openBootSource("first in front after a cold wake")
        }
        // Back in front by any route (Home was handled in onNewIntent, which runs first; Back from
        // the app lands here directly), so the next Home press is a Home-on-Home again.
        openedAppSinceHome = false
        LaunchFallback.next()?.let { page ->
            // Only where the page truly closed: Hisense's network page (85E5N, MT9653, Android 11)
            // is a panel over Aerial, which resumes beneath it without window focus, and the next
            // page then opened beside the panel (issues #948 #976, the reporters' logs).
            idleHandler.postDelayed({
                if (vm.resumed && hasWindowFocus()) {
                    DiagLog.i("the page just opened closed at once; opening ${page.component ?: page.action}")
                    open(page)
                } else {
                    DiagLog.i("back in front under a window of the page just opened; not opening ${page.component ?: page.action}")
                }
            }, FALLBACK_FOCUS_MS)
        }
    }

    override fun onPause() {
        val pausedAt = SystemClock.uptimeMillis()
        super.onPause()
        if (locked) return
        // Paused before the zoom asked for its app (Home's intent, a system panel, the screen
        // going off): the app is not opened behind the user's back and the zoom is dropped.
        appTransitions.session?.takeIf { it.phase == LaunchPhase.Opening }?.let {
            zoomDispatch?.let { r -> idleHandler.removeCallbacks(r) }
            zoomDispatch = null
            appTransitions.cancel()
            openedAppSinceHome = false
            DiagLog.i("paused before ${it.source.key} was asked for; not opened")
        }
        appTransitions.paused()
        launchTimeout?.let { idleHandler.removeCallbacks(it) }
        appTransitions.session?.takeIf { it.zoom && it.phase == LaunchPhase.Background }?.let { s ->
            zoomCoverCheck?.let { idleHandler.removeCallbacks(it) }
            val check = Runnable { appTransitions.coverTimeout(s.id) }
            zoomCoverCheck = check
            idleHandler.postDelayed(check, s.coverTimeoutMs)
        }
        // Paused while the zoom is still growing into the app: Home does not see the pause until the
        // grow has ended (the film stands on its frame meanwhile, nothing recomposes), see AppLaunchTransitions.pauseHeld.
        // Before resumed = false, so that HomeScreen never reads the one without the other.
        val pauseHeld = appTransitions.holdPauseForGrow()
        vm.resumed = false
        Updates.onHomePaused()
        // For a zoom, how long after the click Aerial is paused (the app's window comes next).
        val zoomClick = appTransitions.session?.takeIf { it.zoom }?.source?.requestedAt
        DiagLog.i("onPause${zoomClick?.let { " ${pausedAt - it} ms after the click" } ?: ""}${zoomNote()}")
        val bodyAt = SystemClock.uptimeMillis()
        GuardSetup.setHomeInFront(this, false)
        if (HomeGuardService.onAerialPaused()) {
            // Only Home's bounce: the film plays on, unless Aerial is not back soon after all
            // (Home pressed again and again, the recent apps opened).
            vm.homeBounce = true
            idleHandler.removeCallbacks(endHomeBounce)
            idleHandler.postDelayed(endHomeBounce, HOME_BOUNCE_MS)
        }
        val guardMs = SystemClock.uptimeMillis() - bodyAt
        idleHandler.removeCallbacks(showScreensaver)
        idleHandler.removeCallbacks(dreamCheck)
        // From Android 11 the dream is an activity: paused while waiting for it, it showed.
        if (dreamChecking) noteDreamShown(this)
        dreamChecking = false
        hideScreensaver()
        val tookMs = SystemClock.uptimeMillis() - pausedAt
        // Only a pause that is slow, under a zoom: the guard's part (a package manager call on
        // some versions) and the whole of it, since the pause comes in the grow's last frames.
        if (zoomClick != null && tookMs >= 8) {
            DiagLog.i("onPause took $tookMs ms (Home Guard and the home-in-front flag $guardMs ms), pause held $pauseHeld")
        }
    }

    override fun onStop() {
        super.onStop()
        if (locked) return
        // The app covers Aerial: the zoom's next frame, on return, is full screen again.
        zoomCoverCheck?.let { idleHandler.removeCallbacks(it) }
        zoomCoverCheck = null
        appTransitions.stopped()
        DiagLog.i("onStop${zoomNote()}")
        SelfCompile.onHomeStopped()
        idleHandler.removeCallbacks(releaseWhileAway)
        idleHandler.postDelayed(releaseWhileAway, AWAY_RELEASE_AFTER_MS)
        // Out of sight after all: no bounce to wait out (homeBounce).
        idleHandler.removeCallbacks(endHomeBounce)
        vm.homeBounce = false
        // An app, the screensaver or standby in front: the PIN is asked again, as in Lite (#718).
        AerialSettings.relock()
        // Stopped before a held start ran (startWork): nothing of it was registered.
        if (receiversOn) {
            receiversOn = false
            unregisterReceiver(packageReceiver)
            unregisterReceiver(volumeReceiver)
            unregisterReceiver(dayReceiver)
            runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback) }
        }
    }

    /**
     * Request a launch from the current card. An ordinary app's tile zooms in Aerial's own window
     * (AppLaunchZoom) and the intent goes out ZOOM_DISPATCH_MS after the click, on a timer rather
     * than on the animation's frames, so a slow frame never delays the app. Everything else (stock
     * launcher, inputs, Aerial's shortcuts, a settings panel, Lite, removed animations) starts at
     * once as before, with the system's scale-up where it applies.
     */
    private fun launch(app: AppEntry, bounds: Rect?, origin: HomeLaunchOrigin) {
        // Taken first, so that no later launch can pick up this click's tile.
        val zoomFrom = appTransitions.takeZoomSource(app.key)
        if (!vm.resumed || !window.decorView.hasWindowFocus()) return
        // The TV's settings app behind the parental PIN as well, not only Control Center's button.
        // The PIN overlay and its delay invalidate the old card geometry.
        if (AerialSettings.locked && SystemProbe.isSettingsApp(this, app.packageName)) {
            vm.whenUnlocked { launch(app, null, origin) }
            return
        }
        val decor = window.decorView
        val source = LaunchSource(
            key = app.key,
            component = app.component.flattenToShortString(),
            kind = when {
                app.source != null -> LaunchEntryKind.Source
                HomeGuardService.isStockHome(app.packageName) -> LaunchEntryKind.StockHome
                app.shortcut != null -> LaunchEntryKind.Shortcut
                else -> LaunchEntryKind.App
            },
            boundsInWindow = bounds,
            folderId = origin.folderId,
            homeIndex = origin.homeIndex,
            scrollDp = origin.scrollDp,
            touchScrolled = origin.touchScrolled,
            windowWidth = decor.width,
            windowHeight = decor.height,
            requestedAt = SystemClock.uptimeMillis(),
        )
        // Only an app's own full-screen Activity: inputs, the stock launcher and shortcuts keep their
        // routes. The TV's settings zoom too when their entry's theme is an ordinary full-screen
        // window; a panel (translucent or floating theme) opens plainly, as before. A settings
        // page made translucent in code is caught by its shorter cover timeout: the zoom goes back
        // into the tile under it. Lite chosen in Personalize or animations removed: the plain launch.
        val settings = SystemProbe.isSettingsApp(this, app.packageName)
        val zoom = zoomFrom?.takeIf {
            bounds != null && source.kind == LaunchEntryKind.App && zoomOptionsAvailable &&
                !reduceAppMotion && AerialSettings.effects != Effects.Lite &&
                (!settings || settingsOpenFullScreen(app))
        }
        val session = appTransitions.begin(
            source, zoom,
            coverTimeoutMs = if (settings) LaunchSessionMachine.SETTINGS_COVER_TIMEOUT_MS else LaunchSessionMachine.ZOOM_COVER_TIMEOUT_MS,
        ) ?: return
        clearZoomCallbacks()
        // Worked out once, here: resolving it is a binder call, and the dispatch 240 ms on would
        // make it again on the main thread in the middle of the grow.
        val zoomIntent = if (session.zoom) settingsLaunchIntent(this, app.launchIntent()) else null
        launchTimeout?.let { idleHandler.removeCallbacks(it) }
        val timeout = Runnable {
            // Reconcile a request that never left Home; do not call it a successful first frame.
            appTransitions.restore(session.id)
            if (appTransitions.session?.id == session.id) openedAppSinceHome = false
        }
        launchTimeout = timeout
        idleHandler.postDelayed(timeout, appTransitions.remainingRequestMs())
        openedAppSinceHome = true
        if (session.zoom) {
            val dispatch = Runnable {
                zoomDispatch = null
                // Superseded, cancelled (screen off, screensaver) or paused first: nothing is sent.
                if (!appTransitions.dispatched(session.id)) return@Runnable
                val options = zoomOptions()
                val intent = zoomIntent ?: settingsLaunchIntent(this, app.launchIntent())
                // Sent from a worker thread: startActivity is a binder call that waits for the
                // system (27-96 ms on MiTV-AZFP0, with the app's process starting), and on the main
                // thread it fell on the grow's frames. Exactly one attempt, never repeated here.
                launchOffMain(intent, options, source.requestedAt, app.key) { e ->
                    zoomLaunchFailed(e, options != null, session.id, app)
                }
            }
            zoomDispatch = dispatch
            idleHandler.postDelayed(dispatch, ZOOM_DISPATCH_MS)
            DiagLog.i("opening ${app.key} with Aerial's zoom; intent in $ZOOM_DISPATCH_MS ms")
            return
        }
        var usedScaleUp = false
        try {
            // The stock launcher (Xiaomi's PatchWall, with the maker's own iQIYI / Mango content) opens
            // through the system Home action where it can; see HomeGuardService.openStockLauncher.
            if (HomeGuardService.isStockHome(app.packageName) &&
                HomeGuardService.openStockLauncher(app.packageName) { startStockLauncher(app) }
            ) return
            if (HisenseSources.open(this, app.launchIntent())) return
            val options = launchOptions(bounds)
            usedScaleUp = options != null
            startActivity(settingsLaunchIntent(this, app.launchIntent()), options)
        } catch (e: Exception) {
            // An options-specific rejection affects the next explicit attempt. Never resend a
            // request from a broad RuntimeException catch: the OEM might already have launched it.
            val detail = e.message.orEmpty().lowercase()
            if (usedScaleUp && (e is BadParcelableException ||
                    e is IllegalArgumentException && ("animation" in detail || "activityoptions" in detail))
            ) scaleUpAvailable = false
            // XGIMI (GMUI): nothing answers the passthrough intent a source tile carries, so the tile
            // opens the source page of its settings service instead (issue #241).
            if (app.source != null && runCatching { SystemProbe.openInputPicker(this) }.getOrDefault(false)) return
            launchTimeout?.let { idleHandler.removeCallbacks(it) }
            appTransitions.restore(session.id)
            if (appTransitions.session?.id == session.id) openedAppSinceHome = false
            DiagLog.i("launch failed for ${app.key}: ${e.javaClass.simpleName}")
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    /** The thread the zoom's start request is sent from; idle (and no cost) between launches. */
    private val launchThread by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "aerial-launch").apply { isDaemon = true } }
    }

    /**
     * Sends [intent] with [options] from [launchThread] with this Activity as the caller (the same
     * request the main thread made: same token, same options), once. A failure comes back to the
     * main thread as [failed]; nothing is ever sent again. Logs when the request left and how long the
     * call took, from the click ([requestedAt]).
     */
    private fun launchOffMain(intent: Intent, options: Bundle?, requestedAt: Long, key: String, failed: (Exception) -> Unit) {
        launchThread.execute {
            val sentAt = SystemClock.uptimeMillis()
            var error: Exception? = null
            try {
                startActivity(intent, options)
            } catch (e: Exception) {
                error = e
            }
            DiagLog.i("app zoom: intent for $key sent ${sentAt - requestedAt} ms after the click, startActivity took ${SystemClock.uptimeMillis() - sentAt} ms (off the main thread)")
            error?.let { e -> idleHandler.post { failed(e) } }
        }
    }

    /** The zoom's request failed: the zoom turns back into its tile, and the owner is told. Main thread. */
    private fun zoomLaunchFailed(e: Exception, hadOptions: Boolean, sessionId: Long, app: AppEntry) {
        val detail = e.message.orEmpty().lowercase()
        if (hadOptions && (e is BadParcelableException ||
                e is IllegalArgumentException && ("animation" in detail || "activityoptions" in detail))
        ) zoomOptionsAvailable = false
        // The timeout is this session's unless a newer launch has replaced it.
        if (appTransitions.session?.id == sessionId) launchTimeout?.let { idleHandler.removeCallbacks(it) }
        // The zoom turns back into the tile from wherever it is (AppLaunchZoom, Shrink).
        appTransitions.restore(sessionId)
        if (appTransitions.session?.id == sessionId) openedAppSinceHome = false
        DiagLog.i("launch failed for ${app.key}: ${e.javaClass.simpleName}")
        Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
    }

    /** The settings tile's entry, as it is started (settingsLaunchIntent), opens a full-screen window. */
    private fun settingsOpenFullScreen(app: AppEntry): Boolean =
        opaqueSettings.getOrPut(app.component.flattenToShortString()) {
            SystemProbe.opensOpaqueActivity(this, settingsLaunchIntent(this, app.launchIntent())).also {
                DiagLog.i("settings ${app.component.flattenToShortString()}: ${if (it) "full-screen theme, zooms" else "panel or unknown theme, plain launch"}")
            }
        }

    /**
     * Under Aerial's zoom: no window animation, so the app appears straight out of the full-screen
     * frame instead of the system scaling its window from the tile a second time. A zero resource
     * loads no animation in AOSP 9's AppTransition (loadAnimationRes returns null for id 0). Whether
     * Xiaomi's window manager honours it is checked on the TV (dumpsys window: mNextAppTransitionType).
     */
    private fun zoomOptions(): Bundle? = try {
        ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle().apply {
            // TCL 65C6 (MStar m7221, Android 8), #590: never identify the target as Aerial.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) remove("android:activity.packageName")
        }
    } catch (e: RuntimeException) {
        zoomOptionsAvailable = false
        DiagLog.i("no-transition options unavailable: ${e.javaClass.simpleName}")
        null
    }

    /** Compose supplies window pixels; ScaleUp expects offsets relative to its source View. */
    private fun launchOptions(bounds: Rect?): Bundle? {
        if (bounds == null || reduceAppMotion || !scaleUpAvailable) return null
        val decor = window.decorView
        val origin = IntArray(2)
        decor.getLocationInWindow(origin)
        val left = (bounds.left - origin[0]).coerceAtLeast(0f)
        val top = (bounds.top - origin[1]).coerceAtLeast(0f)
        val right = (bounds.right - origin[0]).coerceAtMost(decor.width.toFloat())
        val bottom = (bounds.bottom - origin[1]).coerceAtMost(decor.height.toFloat())
        if (!listOf(left, top, right, bottom).all { it.isFinite() } || right - left < 1f || bottom - top < 1f) return null
        return try {
            ActivityOptions.makeScaleUpAnimation(
                decor, left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt(),
            ).toBundle().apply {
                // The options name Aerial's package, and on TCL 65C6 (MStar m7221, Android 8) the
                // app opened with them ran as Aerial's: 云视听极光 failed its settings read for a
                // package not its own and 凤凰秀's packer refused to load; both opened from adb
                // (issue #590). A scale-up needs no package; not yet tried on that TV.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) remove("android:activity.packageName")
            }
        } catch (e: RuntimeException) {
            // No request was sent yet: plain launch here cannot open an Activity twice.
            scaleUpAvailable = false
            DiagLog.i("scale-up options unavailable: ${e.javaClass.simpleName}; using system transition")
            null
        }
    }

    /** The stock launcher's own entry, or one of the same page Aerial may start (StandIn, issue #971). */
    private fun startStockLauncher(app: AppEntry): Boolean {
        val intent = app.launchIntent()
        return try {
            startActivity(intent)
            true
        } catch (e: SecurityException) {
            val standIn = StandIn.find(this, app.component)
            DiagLog.i("${app.component.className} needs a permission; opening ${standIn?.className ?: "nothing"} instead")
            standIn != null && runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).setComponent(standIn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    private fun open(intent: Intent) {
        // A settings/details/uninstall route is not the pending card's return.
        appTransitions.cancel()
        openedAppSinceHome = false
        try {
            SystemProbe.startSettingsIntent(this, intent)
        } catch (e: Exception) {
            // XGIMI (GMUI): its inputs carry a passthrough intent that nothing answers, so switching
            // goes through the source page of its settings service (issue #241).
            if (intent.data?.host == "android.media.tv" && SystemProbe.openInputPicker(this)) return
            LaunchFallback.next()?.let { return open(it) }
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** How long the film waits for Aerial to come back from Home's bounce (MFTR0: 120-290 ms). */
        private const val HOME_BOUNCE_MS = 500L

        /**
         * How long after coming back from a page that closed at once Aerial waits for its window's
         * focus before opening the next page (LaunchFallback). Focus followed onResume within
         * 50 ms on Xiaomi MiTV-MFFU0 (issue #984's log).
         */
        private const val FALLBACK_FOCUS_MS = 400L

        /**
         * How long before the screensaver its start is fetched: the moment's 6 MB took 1-2 s from
         * Apple on Xiaomi MiTV-MFTR0, after the index.
         */
        private const val SAVER_AHEAD_MS = 8_000L

        /**
         * The system's dream did not show when asked (see dreamCheck): for the rest of the process,
         * and from the second time on this firmware build also for later ones. Known only for the
         * process, every first screensaver after Aerial restarted waited on a dream that never came
         * and showed black meanwhile (Coocaa C10, Android 9, issue #915). Twice, so that one slow
         * start does not give up a dream that works; a dream that shows, or a firmware update,
         * starts the count again.
         */
        private var dreamFailsNow = false

        private fun dreamFails(context: Context): Boolean = dreamFailsNow || dreamFailCount(context) >= 2

        private fun dreamFailCount(context: Context): Int {
            val prefs = context.getSharedPreferences("system", MODE_PRIVATE)
            return if (prefs.getString(DREAM_FAILS_ON, null) == Build.FINGERPRINT) prefs.getInt(DREAM_FAILS, 0) else 0
        }

        private fun noteDreamFails(context: Context) {
            dreamFailsNow = true
            context.getSharedPreferences("system", MODE_PRIVATE).edit()
                .putString(DREAM_FAILS_ON, Build.FINGERPRINT).putInt(DREAM_FAILS, dreamFailCount(context) + 1).apply()
        }

        private fun noteDreamShown(context: Context) {
            if (dreamFailCount(context) > 0) context.getSharedPreferences("system", MODE_PRIVATE).edit().remove(DREAM_FAILS).apply()
        }

        private const val DREAM_FAILS_ON = "dream_fails_on"
        private const val DREAM_FAILS = "dream_fails"

        /** Whether Home has been resumed in this process yet (BootOpen.dueAfterColdWake). */
        private var resumedInProcess = false

        /** See handOverToFirmwareFallbackHome; for this process. */
        private var handedOverAtBoot = false
        private val AOSP_FALLBACK_HOMES = setOf("com.android.settings.FallbackHome", "com.android.tv.settings.system.FallbackHome", "com.android.tv.settings.ui.activity.FallbackHome")

        /** How Home Guard and the boot receiver bring Aerial back: the same task the launcher entry uses. */
        fun bringBackIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L

        /** How often an open Control Center looks again at what it shows. */
        private const val PANEL_REFRESH_MS = 3_000L

        /** How long the app the TV starts on waits for a network at most. */
        private const val BOOT_APP_NETWORK_WAIT_MS = 20_000L

        /** `adb shell am start -a app.orchard.tv.PREVIEW_SCREENSAVER -n app.orchard.tv/.MainActivity` */
        const val ACTION_PREVIEW_SCREENSAVER = "app.orchard.tv.PREVIEW_SCREENSAVER"

        /** Set by Home Guard when it brings Aerial back after standby or a boot. */
        const val EXTRA_WOKE = "app.orchard.tv.WOKE"

        /** Set by Home Guard when the stock launcher's boot screen covered what the TV starts on. */
        const val EXTRA_REOPEN_BOOT = "app.orchard.tv.REOPEN_BOOT"

        /** How long after the key that woke a screensaver other keys are still taken as waking it. */
        const val WAKE_QUIET_MS = 400L

        /**
         * When the system's screensaver (AerialDream) last ended on a key, in uptime: a second press
         * the remote sent with it reaches Home, which takes it as waking too.
         */
        @Volatile
        var dreamWokeAt = 0L

        /**
         * The key that woke AerialDream, until its lift. The rest of that press (its repeats and its
         * lift) comes to Home once the dream is gone; on TCL MT5879 the dream's teardown can hold
         * the lift back long enough for Android to repeat the key, and one Down moved focus several
         * rows. Set by AerialDream.
         */
        @Volatile var dreamWokeKey = -1
    }
}
