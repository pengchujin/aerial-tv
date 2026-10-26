package app.orchard.tv.ui.home

import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * A TV input's picture behind Home (issue #267): the set-top box on HDMI 1, the way a TV's own home
 * screen shows it. Adapted from @chikwok's tv-preview branch, verified there on Xiaomi MiTV-MFTR0
 * (Android 14): a passthrough TvView tuned to the input.
 *
 * What a passthrough picture is shapes the rest: a hardware layer, so nothing can blur it or copy it
 * (the glass and the grid use a dark built-in picture instead, Wallpapers.SOURCE_PICTURE); silent,
 * as a third-party app gets no audio from it; and one at a time, so the view is let go whenever
 * Home is covered: by an app (the input opened full screen among them), or by the screensaver,
 * whose film fights it for the same video path on MediaTek TVs and wins.
 */
@Composable
fun SourceWallpaper(
    inputId: String,
    /** Home is in front with no screensaver over it. */
    show: Boolean,
    /** How far the picture shows through: HomeBackground draws the dark picture under it until it comes. */
    cover: Animatable<Float, *>,
) {
    var available by remember(inputId) { mutableStateOf(false) }
    // Bumped to tune from scratch: an input that had nothing to show is asked again.
    var generation by remember(inputId) { mutableIntStateOf(0) }
    var tries by remember(inputId) { mutableIntStateOf(0) }
    var weak by remember(inputId) { mutableStateOf(false) }

    LaunchedEffect(available, show) {
        if (show && available) cover.animateTo(1f, tween(900, easing = Motion.EaseOut))
        else if (!show) cover.snapTo(0f)
        else cover.animateTo(0f, tween(400, easing = Motion.EaseOut))
    }
    // A box woken by the same press takes tens of seconds to put a picture on HDMI, and an input
    // asked too early says WEAK_SIGNAL and is never asked again by itself. Ask again on a backoff
    // for a minute and a half, then leave it (a port with nothing on it).
    LaunchedEffect(weak, show, generation) {
        if (!weak || !show || tries >= RETRY_DELAYS_MS.size) return@LaunchedEffect
        delay(RETRY_DELAYS_MS[tries])
        tries++
        weak = false
        generation++
    }
    if (!show) {
        LaunchedEffect(Unit) { available = false; tries = 0; weak = false }
        return
    }
    AndroidView(
        factory = { context ->
            TvView(context).apply {
                // A TvView takes focus by default and would take the D-pad from the tiles.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setCallback(object : TvView.TvInputCallback() {
                    override fun onVideoAvailable(id: String) {
                        available = true
                        tries = 0
                    }

                    override fun onVideoUnavailable(id: String, reason: Int) {
                        DiagLog.i("input wallpaper: no picture from $id (reason $reason)")
                        available = false
                        weak = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
                    }

                    override fun onConnectionFailed(id: String) {
                        DiagLog.i("input wallpaper: could not connect to $id")
                        available = false
                    }

                    override fun onDisconnected(id: String) {
                        available = false
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        update = { view ->
            val key = "$inputId#$generation"
            if (view.tag != key) {
                view.tag = key
                view.reset()
                // Tuned before the view is attached, the picture stays black.
                view.postDelayed({
                    if (view.tag == key) view.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId))
                }, TUNE_DELAY_MS)
            }
        },
        onRelease = { view ->
            view.tag = null
            view.reset()
        },
    )
}

private const val TUNE_DELAY_MS = 800L
private val RETRY_DELAYS_MS = longArrayOf(3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L)
