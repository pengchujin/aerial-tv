package app.orchard.tv.ui.sleep

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.HomeViewModel
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import java.util.Locale

@Composable
fun SleepTimerSheet(
    visible: Boolean,
    vm: HomeViewModel,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)

    val remaining = vm.sleepTimerMinutes
    val choices = remember {
        listOf(
            TimerOption(null, R.string.sleep_timer_off, "Выкл"),
            TimerOption(15, R.string.sleep_timer_15, "15 мин"),
            TimerOption(30, R.string.sleep_timer_30, "30 мин"),
            TimerOption(60, R.string.sleep_timer_60, "1 час"),
            TimerOption(90, R.string.sleep_timer_90, "1.5 часа"),
            TimerOption(120, R.string.sleep_timer_120, "2 часа"),
        )
    }

    val requesters = remember { choices.map { FocusRequester() } }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        val activeIdx = choices.indexOfFirst { opt ->
            when (opt.minutes) {
                null -> remaining == null
                15 -> remaining in 1..15
                30 -> remaining in 16..30
                60 -> remaining in 31..60
                90 -> remaining in 61..90
                120 -> remaining in 91..120
                else -> false
            }
        }.takeIf { it >= 0 } ?: 0
        runCatching { requesters[activeIdx].requestFocus() }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)),
        exit = fadeOut(tween(180, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .dismissOnTap(onDismiss)
                .background(Color.Black.copy(alpha = 0.52f))
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)) + scaleIn(tween(280, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(160, easing = Motion.EaseOut)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 880.dp)
                    .consumeTaps()
                    .liquidGlass(24.dp)
                    .padding(horizontal = 28.dp, vertical = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        GlyphIcon(Glyph.Moon, if (remaining != null) Palette.Orange else Color.White, 36.dp)
                        Column {
                            BasicText(
                                stringResource(R.string.sleep_timer_title),
                                style = Type.LargeTitle.copy(
                                    color = Palette.Label,
                                    fontWeight = FontWeight.W700,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = if (remaining != null) {
                                stringResource(R.string.sleep_timer_remaining, remaining)
                            } else {
                                if (Locale.getDefault().language == "ru") "Автоматическое отключение и переход в режим сна" else "Automatic standby after time expires"
                            }
                            BasicText(
                                subtitle,
                                style = Type.Callout.copy(color = if (remaining != null) Palette.Orange else Palette.SecondaryLabel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }

                    Pressable(
                        onClick = onDismiss,
                        focusedScale = 1.06f,
                    ) { state ->
                        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140), label = "close-bg")
                        val fg = if (state.focused) Palette.OnFocus else Palette.Label
                        Row(
                            Modifier.smoothBackground(bg, 12.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            GlyphIcon(Glyph.ChevronLeft, fg, 18.dp)
                            BasicText(
                                if (Locale.getDefault().language == "ru") "Закрыть" else "Close",
                                style = Type.Callout.copy(color = fg, fontWeight = FontWeight.W600),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                // Options Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    choices.forEachIndexed { idx, opt ->
                        val isSelected = when (opt.minutes) {
                            null -> remaining == null
                            15 -> remaining in 1..15
                            30 -> remaining in 16..30
                            60 -> remaining in 31..60
                            90 -> remaining in 61..90
                            120 -> remaining in 91..120
                            else -> false
                        }
                        TimerCard(
                            option = opt,
                            selected = isSelected,
                            focusRequester = requesters[idx],
                            modifier = Modifier.weight(1f),
                            onClick = {
                                vm.setSleepTimer(opt.minutes)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class TimerOption(
    val minutes: Int?,
    val resId: Int,
    val shortLabel: String,
)

@Composable
private fun TimerCard(
    option: TimerOption,
    selected: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fullText = stringResource(option.resId)
    val text = if (Locale.getDefault().language == "ru") option.shortLabel else fullText

    Pressable(
        onClick = onClick,
        focusRequester = focusRequester,
        focusedScale = 1.07f,
        modifier = modifier,
    ) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else if (selected) Palette.Fill.copy(alpha = 0.5f) else Palette.Fill,
            tween(140, easing = Motion.EaseOut),
            label = "timer-card-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else if (selected) Palette.Orange else Palette.Label
        val iconColor = if (state.focused) Palette.OnFocus else if (selected) Palette.Orange else Color.White

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .smoothBackground(bg, 14.dp)
                .padding(vertical = 16.dp, horizontal = 4.dp),
        ) {
            GlyphIcon(
                if (option.minutes == null) Glyph.Power else Glyph.Moon,
                iconColor,
                26.dp,
            )
            BasicText(
                text,
                style = Type.Body.copy(
                    color = fg,
                    fontWeight = if (selected || state.focused) FontWeight.W700 else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            if (selected) {
                GlyphIcon(Glyph.Check, if (state.focused) Palette.OnFocus else Palette.Orange, 16.dp)
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
