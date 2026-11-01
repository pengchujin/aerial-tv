package app.orchard.tv.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.delay

/**
 * Mi TV 4 (MiTV4-ANSM0, Amlogic, Android 6.0.1) restarted when Home's film opened its decoder
 * while Xiaomi's player was still taking down the HDMI input just left: Aerial resumed and started
 * the film, the player stopped the input 0.8 s later, and the TV went down with a burst of noise
 * (issues #1072 #1079, both reporters' logs; with a still wallpaper it never happened). There the
 * film waits a moment after an input, on the picture it was left on. Only where Xiaomi's player
 * is and up to Android 6: nothing tells the crash apart before it happens, and no newer Xiaomi
 * has shown it (MiTV-MFTR0, Android 11 and 14, back from HDMI many times a day).
 */
object FilmAfterInput {
    @Volatile
    private var inputOpened = false

    /** Aerial opens [intent]: an input of Xiaomi's player, or of Android's TV framework. */
    fun noteOpened(context: Context, intent: Intent) {
        if (!applies(context)) return
        inputOpened = intent.component?.packageName == XIAOMI_PLAYER || intent.data?.host == "android.media.tv"
    }

    /** As Home's film is about to open its decoder: the wait, once, if Aerial is back from an input. */
    suspend fun await() {
        if (!inputOpened) return
        inputOpened = false
        DiagLog.i("wallpaper film waits ${WAIT_MS} ms for the input just left to close")
        delay(WAIT_MS)
    }

    private fun applies(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.M &&
            runCatching { context.packageManager.getPackageInfo(XIAOMI_PLAYER, 0) }.isSuccess

    private const val XIAOMI_PLAYER = "com.xiaomi.mitv.tvplayer"

    /** The player stopped the input 0.8-0.9 s after Aerial resumed (#1072). */
    private const val WAIT_MS = 2_500L
}
