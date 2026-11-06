package app.orchard.tv.watchnext

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.delay

internal data class WatchNextContent(val program: WatchNextProgram, val source: AppEntry)

/** One retained visual snapshot, never a second selection or a second focusable play control. */
internal class WatchNextContentTransition(initial: WatchNextContent) {
    var shown by mutableStateOf(initial)
    var moving by mutableStateOf(false)
    var entering by mutableStateOf(false)
    var direction by mutableIntStateOf(1)
    val visibility = Animatable(1f)
    val offsetDp: Float get() = direction * (1f - visibility.value) *
        if (entering) WatchNextDefaults.ContentEnterDp else -WatchNextDefaults.ContentExitDp

    fun canPlay(target: ProgramKey): Boolean = !moving && shown.program.key == target
}

@Composable
internal fun rememberWatchNextContent(
    target: WatchNextContent, records: List<WatchNextProgram>, direction: Int, animate: Boolean, ready: Boolean = true,
): WatchNextContentTransition {
    val state = remember { WatchNextContentTransition(target) }
    val reduced = LocalReducedMotion.current
    val latest = rememberUpdatedState(target)
    val allowed = records.any { it.key == state.shown.program.key }
    LaunchedEffect(target.program.key, reduced, animate, allowed, ready) {
        if (reduced || !animate || !allowed) {
            state.shown = latest.value
            state.moving = false
            state.visibility.snapTo(1f)
        } else if (state.shown.program.key != target.program.key || state.moving) {
            state.moving = true
            // Cached artwork still needs decoding/frost preparation. Start with its resolved
            // frame (including a null/local fallback), rather than changing the text first.
            if (!ready && state.shown.program.key != target.program.key) return@LaunchedEffect
            state.direction = if (direction < 0) -1 else 1
            if (state.shown.program.key != target.program.key) {
                state.entering = false
                // A rapid reversal continues fading the visible content, without flashing it back in.
                state.visibility.animateTo(0f, tween(WatchNextDefaults.ContentExitMs, easing = Motion.EaseOut))
                state.shown = latest.value
                delay(WatchNextDefaults.ContentGapMs)
            }
            state.entering = true
            state.visibility.animateTo(1f, tween(WatchNextDefaults.ContentEnterMs, easing = Motion.EaseOut))
            state.moving = false
        }
    }
    // Progress/title refreshes of the same record do not restart a navigation animation.
    SideEffect { if (state.shown.program.key == target.program.key) state.shown = target }
    return state
}
