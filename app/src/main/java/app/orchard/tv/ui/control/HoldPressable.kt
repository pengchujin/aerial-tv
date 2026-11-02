package app.orchard.tv.ui.control

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.PressState
import app.orchard.tv.ui.components.isSelect
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long OK must be held before the press counts as long, as on Home's tiles (AppTile). */
private const val LONG_PRESS_MS = 550L

/**
 * [app.orchard.tv.ui.components.Pressable] with a long press, for Control Center buttons that
 * have a second action. Same look and the same rule: a short press fires on key-up, and only for a
 * press that began here. A long press fires once while OK is held (after [LONG_PRESS_MS], or at
 * the first key repeat of remotes that repeat a held key, as AppTile does), and the key-up that
 * ends it fires nothing. On a touch screen a tap is a press and a held finger a long press.
 * Pressable itself is left untouched, so none of its callers change.
 */
@Composable
internal fun HoldPressable(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusedScale: Float = 1.06f,
    content: @Composable BoxScope.(PressState) -> Unit,
) {
    val state = remember { PressState() }
    val hold = remember { Hold() }
    val ownRequester = remember { FocusRequester() }
    val requester = focusRequester ?: ownRequester
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            state.pressed -> focusedScale - 0.05f
            state.focused -> focusedScale
            else -> 1f
        },
        animationSpec = if (state.pressed) tween(100, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "hold-pressable-scale",
    )
    fun fireLong() {
        hold.job?.cancel()
        hold.fired = true
        state.pressed = false
        Sounds.select()
        long()
    }
    Box(
        modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !state.focused) Sounds.focusMoved()
                state.focused = it.isFocused
                if (!it.isFocused) {
                    state.pressed = false
                    hold.byKey = false
                    hold.job?.cancel()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!hold.byKey) state.pressed = true
                        tryAwaitRelease()
                        if (!hold.byKey) state.pressed = false
                    },
                    onTap = {
                        runCatching { requester.requestFocus() }
                        Sounds.select()
                        click()
                    },
                    onLongPress = {
                        runCatching { requester.requestFocus() }
                        state.pressed = false
                        Sounds.select()
                        long()
                    },
                )
            }
            .onKeyEvent { e ->
                if (!e.isSelect()) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (repeat == 0) {
                            state.pressed = true
                            hold.byKey = true
                            hold.fired = false
                            hold.job?.cancel()
                            hold.job = scope.launch {
                                delay(LONG_PRESS_MS)
                                if (state.pressed && hold.byKey && !hold.fired) fireLong()
                            }
                        } else if (state.pressed && hold.byKey && !hold.fired) fireLong()
                        true
                    }
                    KeyEventType.KeyUp -> {
                        hold.job?.cancel()
                        val short = state.pressed && hold.byKey && !hold.fired
                        state.pressed = false
                        hold.byKey = false
                        hold.fired = false
                        if (short) {
                            Sounds.select()
                            click()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) { content(state) }
}

/** The key press under way: from the remote, and whether its long press has fired. */
private class Hold {
    var byKey = false
    var fired = false
    var job: Job? = null
}
