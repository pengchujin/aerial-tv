package app.orchard.tv

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.net.NetworkCapabilities
import android.net.Uri
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import app.orchard.tv.apps.AppDetails
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.ui.home.HomeScreen
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.canvasDensity
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.Diagnostics
import app.orchard.tv.system.MissingClipboard
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.settingsLaunchIntent
import app.orchard.tv.update.Updates
import app.orchard.tv.weather.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm by viewModels<HomeViewModel>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.reload()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshSystem()
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
            Updates.checkIfStale()
        }
        override fun onLost(network: Network) = runOnUiThread { vm.refreshNetwork() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = runOnUiThread { vm.refreshNetwork() }
    }

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagLog.catchCrashes()
        DiagLog.init(this)
        DiagLog.i("onCreate ${intent.action} restored=${savedInstanceState != null}; ${DiagLog.device()}")
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        TileArtFactory.useCanvasDensity(canvasDensity(resources.displayMetrics))
        Wallpapers.init(this)
        Weather.init(this)
        Updates.init(this)
        CustomIcons.init(this)
        SourceLooks.init(this)
        AerialSettings.init(this)
        Sounds.init(this)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        // The home screen paints every pixel itself; a window background would be one more
        // full-screen fill per frame for the TV's GPU. Only until Home's first frame, the backdrop
        // baked at the last start, so the window never shows black while Compose sets up.
        val baked = Wallpapers.backdrop
        window.setBackgroundDrawable(baked?.let { BitmapDrawable(resources, it.asAndroidBitmap()) })
        if (baked != null) {
            window.decorView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    // Not from inside the draw itself.
                    idleHandler.post {
                        window.setBackgroundDrawable(null)
                        window.decorView.viewTreeObserver.removeOnDrawListener(this)
                    }
                }
            })
        }
        if (DiagLog.modeSwitch(this)) preferSmoothestRefreshRate() else DiagLog.i("refresh rate left as it is (mode_switch off)")
        if (DiagLog.softLayer(this)) window.decorView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        logWindow()

        setContent {
            // TVs ship at different densities for the same 1080p panel (Xiaomi 320dpi, TCL 240dpi),
            // which would make the same layout bigger on one and smaller on the other. Aerial is
            // designed on a 960×540 canvas, so it scales that canvas to whatever the panel is.
            val canvas = Density(
                density = canvasDensity(resources.displayMetrics),
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalDensity provides canvas) {
                HomeScreen(
                    vm = vm,
                    onLaunch = ::launch,
                    onOpen = ::open,
                    onAppInfo = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onUninstall = { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    // Posted so the key-up of the Back press that started it does not end it at once.
                    onStartScreensaver = { idleHandler.postDelayed({ showScreensaver.run() }, 150) },
                )
            }
        }

        ContextCompat.registerReceiver(this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        if (savedInstanceState == null && firstStartSinceBoot()) openBootSource("boot")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
        updateAppInfoEntry()
    }

    /**
     * Firmware without Android's App Info screen (Xiaomi HyperOS on MiTV-MFTR0 has none) sends apps
     * that ask for it to nothing: File Manager+ showed "Error". Aerial answers those intents with
     * AppInfoActivity, through an alias that is on only when the firmware has no screen of its own,
     * so that other TVs do not get an "Open with" choice for it.
     */
    private fun updateAppInfoEntry() {
        val alias = ComponentName(this, "$packageName.AppInfoEntry")
        val state = if (AppDetails.hasSystemScreen(this)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (packageManager.getComponentEnabledSetting(alias) != state) {
            packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }
    }

    override fun onDestroy() {
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
    private fun openBootSource(reason: String) {
        val id = AerialSettings.bootSource
        val pkg = AerialSettings.bootApp
        if (id == null && pkg == null) return
        if (SystemClock.elapsedRealtime() - bootSourceOpenedAt < 15_000) return
        bootSourceOpenedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            // Inputs can take a while to list right after a wake (Xiaomi MiTV-MTEQ0, issue #111).
            val intent = withTimeoutOrNull(30_000) {
                snapshotFlow {
                    if (id != null) vm.sources.firstOrNull { it.id == id }?.let { it.label to it.intent }
                    else vm.entry(pkg!!)?.let { it.label to it.launchIntent() }
                }.filterNotNull().first()
            } ?: run {
                android.util.Log.w("Aerial", "$reason: ${id ?: pkg} not listed; not opening it")
                return@launch
            }
            android.util.Log.i("Aerial", "$reason: opening ${intent.first}, what the TV starts on")
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
            "display ${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi, canvas density ${canvasDensity(m)}; $modes; " +
                "hwui ${systemProperty("debug.hwui.renderer")}, soft_layer=${DiagLog.softLayer(this)}",
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
    }

    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        // `... MainActivity report`: what tools/report.sh pastes into an issue.
        if (args?.contains("report") == true) writer.print(Diagnostics.report(this)) else DiagLog.dump(writer)
    }

    // ---- Screensaver: Aerial's own, before the firmware's ad-carrying one can start ----

    private val idleHandler = Handler(Looper.getMainLooper())
    private val showScreensaver = Runnable {
        DiagLog.i("screensaver starts (${Wallpapers.screensaver})")
        vm.screensaverVisible = true
        // Holding the screen on keeps the firmware's screensaver from starting over ours. After a
        // long stretch, let go so the TV's own power policy can still turn the panel off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        idleHandler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private var swallowKeyUp = -1

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
        if (AerialSettings.screensaverMinutes != AerialSettings.NEVER) idleHandler.postDelayed(showScreensaver, idleBeforeScreensaver())
    }

    /**
     * Aerial's screensaver has to start before the firmware's (ad-carrying) one. Xiaomi keeps its
     * delay in system/screen_saver_timeout: 120000 on some MiTV-MFTR0 units, 480000 on others, -1
     * when off. Start 15 s ahead of it when it is shorter than the delay chosen in Personalize.
     */
    private fun idleBeforeScreensaver(): Long {
        val chosen = AerialSettings.screensaverMinutes * 60_000L
        val firmware = runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull()
        return if (firmware != null && firmware > 0) minOf(chosen, maxOf(30_000L, firmware - 15_000L))
        else chosen
    }

    private fun hideScreensaver() {
        if (vm.screensaverVisible) DiagLog.i("screensaver ends")
        vm.screensaverVisible = false
        idleHandler.removeCallbacks(releaseScreen)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keysLogged++ < 200) DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"}, view focused ${currentFocus != null}")
        restartIdleTimer()
        // The key that wakes the screensaver only wakes it, silently.
        if (!vm.screensaverVisible && event.keyCode != swallowKeyUp) Sounds.onKey(event)
        // Left / right step through the films or photos and keep the screensaver up.
        if (vm.screensaverVisible && vm.screensaverSkips.onKey(event)) return true
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
        DiagLog.i("onNewIntent ${intent.action}")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
        if (intent.action == ACTION_PREVIEW_SCREENSAVER) {
            idleHandler.post { showScreensaver.run() }
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            // Since 1.11 Home Guard answers Home from an app by bringing Aerial back with this same
            // intent, so the intent alone cannot tell Home-on-Home from coming back from an app.
            vm.onHomePressed(resetFocus = !openedAppSinceHome)
            openedAppSinceHome = false
        }
    }

    override fun onStart() {
        super.onStart()
        DiagLog.i("onStart")
        GuardSetup.ensureEnabled(this)
        idleHandler.postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
        vm.reloadIfPackagesChanged()
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
            else cm.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
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
        vm.resumed = true
        DiagLog.i("onResume; screensaver after ${idleBeforeScreensaver() / 1000} s")
        restartIdleTimer()
        GuardSetup.setHomeInFront(this, true)
        HomeGuardService.onAerialResumed()
        Updates.onHomeResumed()
        vm.refreshSystem()
        // Back in front by any route (Home was handled in onNewIntent, which runs first; Back from
        // the app lands here directly), so the next Home press is a Home-on-Home again.
        openedAppSinceHome = false
        LaunchFallback.next()?.let {
            DiagLog.i("the page just opened closed at once; opening ${it.component ?: it.action}")
            open(it)
        }
    }

    override fun onPause() {
        super.onPause()
        vm.resumed = false
        DiagLog.i("onPause")
        GuardSetup.setHomeInFront(this, false)
        idleHandler.removeCallbacks(showScreensaver)
        hideScreensaver()
    }

    override fun onStop() {
        super.onStop()
        DiagLog.i("onStop")
        unregisterReceiver(packageReceiver)
        unregisterReceiver(volumeReceiver)
        unregisterReceiver(dayReceiver)
        runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback) }
    }

    /** Opens an app, growing it out of its tile. */
    private fun launch(app: AppEntry, bounds: Rect?) {
        // The TV's settings app behind the parental PIN as well, not only Control Center's button.
        if (AerialSettings.locked && SystemProbe.isSettingsApp(this, app.packageName)) {
            vm.whenUnlocked { launch(app, bounds) }
            return
        }
        openedAppSinceHome = true
        // The stock launcher (Xiaomi's PatchWall, with the maker's own iQIYI / Mango content) opens
        // through the system Home action where it can; see HomeGuardService.openStockLauncher.
        if (HomeGuardService.isStockHome(app.packageName) &&
            HomeGuardService.openStockLauncher(app.packageName) { runCatching { startActivity(app.launchIntent()) }.isSuccess }
        ) return
        val options = bounds?.let {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView, it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(),
            ).toBundle()
        }
        try {
            startActivity(settingsLaunchIntent(this, app.launchIntent()), options)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(intent: Intent) {
        try {
            SystemProbe.startSettingsIntent(this, intent)
        } catch (e: Exception) {
            LaunchFallback.next()?.let { return open(it) }
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** How Home Guard and the boot receiver bring Aerial back: the same task the launcher entry uses. */
        fun bringBackIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L

        /** How often an open Control Center looks again at what it shows. */
        private const val PANEL_REFRESH_MS = 3_000L

        /** `adb shell am start -a app.orchard.tv.PREVIEW_SCREENSAVER -n app.orchard.tv/.MainActivity` */
        const val ACTION_PREVIEW_SCREENSAVER = "app.orchard.tv.PREVIEW_SCREENSAVER"

        /** Set by Home Guard when it brings Aerial back after standby or a boot. */
        const val EXTRA_WOKE = "app.orchard.tv.WOKE"

        /** How long after the key that woke a screensaver other keys are still taken as waking it. */
        const val WAKE_QUIET_MS = 400L

        /**
         * When the system's screensaver (AerialDream) last ended on a key, in uptime: a second press
         * the remote sent with it reaches Home, which takes it as waking too.
         */
        @Volatile
        var dreamWokeAt = 0L
    }
}
