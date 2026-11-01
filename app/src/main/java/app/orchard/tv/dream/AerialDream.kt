package app.orchard.tv.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.orchard.tv.MainActivity
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.MissingClipboard
import app.orchard.tv.ui.theme.canvasDensity
import app.orchard.tv.weather.Weather
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.orchard.tv.ui.home.LiveFilm
import app.orchard.tv.ui.home.Screensaver
import app.orchard.tv.ui.home.ScreensaverSkips
import app.orchard.tv.ui.home.rememberNow
import app.orchard.tv.wallpaper.Wallpapers

/**
 * Aerial's screensaver as the TV's system screensaver, so it starts from any app, as on Apple TV,
 * rather than only on Home. TVs that use Android's standard screensaver (TCL among them) get it
 * set by tools/install.sh in place of the maker's own, which on TCL advertises a paid membership.
 * Xiaomi runs its own screensaver outside this mechanism; there Aerial's Home timer covers it.
 */
class AerialDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val skips = ScreensaverSkips()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive so left / right can step through the films; every other key ends the dream
        // in dispatchKeyEvent, as a non-interactive dream would.
        isInteractive = true
        isFullscreen = true
        // Home's wallpaper film holds the decoder the dream's film needs (TCL MT5879).
        LiveFilm.onHome?.letDecoderGo()
        Wallpapers.init(this)
        Weather.init(this)
        val canvas = canvasDensity(resources.displayMetrics)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(canvas, LocalDensity.current.fontScale)) {
                        val now by rememberNow()
                        Screensaver(visible = true, apps = emptyList(), now = now, skips = skips)
                    }
                }
            },
        )
        // Compose finds its lifecycle on the window's root view.
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
    }

    /** Keys pressed while the dream is up and not lifted yet. */
    private val keysDown = HashSet<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (skips.onKey(event)) return true
        // A lift whose press never came here ends the dream too: Coocaa C10's hotkey service keeps
        // the press of some keys and lets only the lift through (issue #915; see MainActivity).
        if (event.action == KeyEvent.ACTION_DOWN) keysDown += event.keyCode
        else if (!event.isCanceled && !keysDown.remove(event.keyCode) && event.keyCode != MainActivity.dreamWokeKey) {
            DiagLog.i("dream: ${KeyEvent.keyCodeToString(event.keyCode)} lifted with no press; ending it")
            finish()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MainActivity.dreamWokeAt = event.eventTime
            MainActivity.dreamWokeKey = event.keyCode
            DiagLog.i("dream woken by ${KeyEvent.keyCodeToString(event.keyCode)} at ${event.eventTime}")
            finish()
        }
        // The lift, if it comes here while the dream is still up. Not the cancelled one Android sends
        // as the dream loses focus: the real lift then still goes to Home (TCL MT5879).
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.keyCode == MainActivity.dreamWokeKey) MainActivity.dreamWokeKey = -1
        return true
    }

    /**
     * Ends the dream as the screen goes off, so its film lets the decoder go before the TV
     * suspends, as Home's own screensaver does. TCL tc8000_ay30a2 (Amlogic, Android 11, firmware
     * 2026) could not be woken from standby whenever AerialDream was the system screensaver, even
     * with the guard out; with Home's in-app screensaver playing as the power key was pressed, it
     * woke every time (issue #371, the reporter's experiments).
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DiagLog.i("screen off; ending the dream")
            finish()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = this
        registry.currentState = Lifecycle.State.RESUMED
        runCatching { registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    override fun onDreamingStopped() {
        if (running === this) running = null
        runCatching { unregisterReceiver(screenOff) }
        // Since Android 11 the dream lives in an activity: a key press finishes it, the window is
        // detached first (DESTROYED), and only then is the dream stopped. Moving back to CREATED
        // from there throws and took Aerial down on every wake (TCL MT5879).
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        if (running === this) running = null
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    companion object {
        /** The dream on screen in this process, if any. Main thread only. */
        private var running: AerialDream? = null

        /**
         * Ends the dream for a Home press the guard took before the system saw it. The system never
         * got that press, so nothing else closed the dream: Home did nothing over it (TCL MT5879,
         * Android 11, where the guard filters keys). True when there was a dream to end.
         */
        fun endForHome(): Boolean {
            val dream = running ?: return false
            DiagLog.i("dream ended by Home")
            dream.finish()
            return true
        }
    }
}
