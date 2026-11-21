package app.orchard.tv.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.orchard.tv.guard.MediaPlaying
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.MissingClipboard
import app.orchard.tv.ui.home.LiveFilm
import app.orchard.tv.ui.home.Screensaver
import app.orchard.tv.ui.home.ScreensaverSkips
import app.orchard.tv.ui.home.rememberNow
import app.orchard.tv.ui.theme.canvasDensity
import app.orchard.tv.weather.Weather
import app.orchard.tv.wallpaper.Wallpapers

/**
 * Aerial's screensaver over another app, for firmwares that never start a dream outside their own
 * launcher (MiTV-AZFP0, Android 9: SystemUI starts Somnambulator but no dream ever renders, so
 * SystemScreensaver.startDream is a no-op there). The Home Guard starts it on its idle check (see
 * HomeGuardService.idleDreamCheck); it hosts the same content as [AerialDream], so what Personalize
 * chose (aerial films, photos, the clock) shows over whatever was on screen. The app underneath
 * stays behind it; a key ends it and the app comes back, as ending the dream would.
 */
class IdleScreensaverActivity : ComponentActivity() {
    private val skips = ScreensaverSkips()
    private val handler = Handler(Looper.getMainLooper())

    /** Lets the firmware's power policy back in after Home's own long hold (MainActivity.SCREENSAVER_HOLD_MS). */
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Holding the screen on keeps the firmware's idle standby out of the way while the
        // screensaver plays, as Home does for its own (see MainActivity.startScreensaver). Like
        // Home's, the hold ends after a long stretch, so the TV's power policy can still turn the
        // panel off — the screen going off ends this activity too (screenOff). Not where the
        // screen never goes off on its own: there Home never lets go either.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!screenNeverOff()) handler.postDelayed(releaseScreen, HOLD_MS)
        // Home's wallpaper film holds the decoder the film here needs (TCL MT5879), as in AerialDream.
        LiveFilm.onHome?.letDecoderGo()
        Wallpapers.init(this)
        Wallpapers.fitScreen(this)
        Weather.init(this)
        val canvas = canvasDensity(resources.displayMetrics)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(canvas, LocalDensity.current.fontScale)) {
                val now by rememberNow()
                Screensaver(visible = true, apps = emptyList(), now = now, skips = skips)
            }
        }
    }

    /**
     * Any key ends it, and the app underneath comes back. Left and right step through the films
     * first (skips.onKey), as in the dream; a lift whose press never came here ends it too, as
     * AerialDream does (Coocaa C10's hotkey service, issue #915).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            event.keyCode != KeyEvent.KEYCODE_BACK && skips.onKey(event)
        ) return true
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            DiagLog.i("idle screensaver ended by BACK")
            finish()
        } else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DiagLog.i("idle screensaver ended by ${KeyEvent.keyCodeToString(event.keyCode)}")
            finish()
        }
        return true
    }

    /** The screen going off ends it, so the film lets the decoder go before the TV suspends. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    /**
     * Something playing ends it too. The guard looked before showing it, but a film can start on
     * the app underneath right after (a player whose next episode begins on its own, a cast
     * starting), and the screensaver must not sit over it: its sound plays through the aerial
     * film, which is what "watching the screensaver with the film's sound" looks like.
     */
    private val playingCheck = object : Runnable {
        override fun run() {
            if (!watchingForPlaying) return
            if (MediaPlaying.playing(this@IdleScreensaverActivity)) {
                DiagLog.i("idle screensaver ended: something is playing")
                finish()
                return
            }
            handler.postDelayed(this, PLAYING_POLL_MS)
        }
    }

    private var watchingForPlaying = false

    override fun onStart() {
        super.onStart()
        running = this
        runCatching { registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        watchingForPlaying = true
        handler.postDelayed(playingCheck, PLAYING_POLL_MS)
    }

    override fun onStop() {
        if (running === this) running = null
        watchingForPlaying = false
        handler.removeCallbacks(playingCheck)
        handler.removeCallbacks(releaseScreen)
        runCatching { unregisterReceiver(screenOff) }
        super.onStop()
    }

    /** Where the screen never goes off on its own (a screen_off_timeout of a day or more), keep holding. */
    private fun screenNeverOff(): Boolean =
        runCatching { Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }
            .getOrDefault(0L) >= 24 * 60 * 60 * 1000L

    companion object {
        /** How often [playingCheck] asks; a film's first sound is caught within this. */
        private const val PLAYING_POLL_MS = 2_000L

        /** How long the screen is held for, as Home holds it (MainActivity.SCREENSAVER_HOLD_MS). */
        private const val HOLD_MS = 4 * 60 * 60 * 1000L

        /** The idle screensaver on screen in this process, if any. Main thread only. */
        private var running: IdleScreensaverActivity? = null

        /** Starts it over whatever is on screen, unless it is already up. */
        fun show(context: Context) {
            if (running != null) return
            context.startActivity(
                Intent(context, IdleScreensaverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
