package app.orchard.tv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.media.NowPlayingState
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/** Home's compact player. Session access stays in the repository. */
@Composable
fun NowPlayingCard(
    state: NowPlayingState,
    compact: Boolean,
    onFocused: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artSize = if (compact) 79.dp else 90.dp
    val status = when (state.isPlaying) {
        true -> stringResource(R.string.np_playing)
        false -> stringResource(R.string.np_paused)
        null -> null
    }
    Row(
        modifier
            .width(375.dp)
            .height(if (compact) 105.dp else 119.dp)
            .onFocusChanged { onFocused(it.hasFocus) }
            .focusGroup()
            .liquidGlass(24.dp)
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        val artwork = remember(state.artwork) { state.artwork?.asImageBitmap() }
        Box(Modifier.size(artSize).clip(RoundedCornerShape(14.dp)).background(Palette.FillStrong), contentAlignment = Alignment.Center) {
            if (artwork != null) Image(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else BasicText("♫", style = Type.LargeTitle.copy(color = Palette.SecondaryLabel))
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                if (status == null) state.appName else "${state.appName} · $status",
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(state.title, style = Type.Headline.copy(color = Palette.Label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.artist?.takeIf { it.isNotBlank() }?.let {
                BasicText(it, style = Type.Caption.copy(color = Palette.SecondaryLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.canSkipPrevious) MediaButton(MediaSymbol.Previous, R.string.np_previous, onPrevious)
                if (state.canPlayPause) MediaButton(
                    if (state.isPlaying == true) MediaSymbol.Pause else MediaSymbol.Play,
                    if (state.isPlaying == true) R.string.np_pause else R.string.np_play,
                    onPlayPause,
                    primary = true,
                )
                if (state.canSkipNext) MediaButton(MediaSymbol.Next, R.string.np_next, onNext)
            }
        }
    }
}

private enum class MediaSymbol { Previous, Play, Pause, Next }

@Composable
private fun MediaButton(symbol: MediaSymbol, label: Int, onClick: () -> Unit, primary: Boolean = false) {
    val description = stringResource(label)
    Pressable(onClick = onClick, focusedScale = 1.12f,
        modifier = Modifier.size(if (primary) 40.dp else 36.dp, 30.dp).semantics { contentDescription = description },
    ) { press ->
        val color by animateColorAsState(
            if (press.focused) Palette.Focus else if (primary) Palette.FillStrong else Palette.Fill,
            tween(140, easing = Motion.EaseOut), label = "media-button-color",
        )
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
            MediaSymbolIcon(symbol, if (press.focused) Palette.OnFocus else Palette.Label)
        }
    }
}

@Composable
private fun MediaSymbolIcon(symbol: MediaSymbol, color: Color) {
    Canvas(Modifier.size(17.dp)) {
        val w = size.width
        val h = size.height
        fun triangle(left: Float, right: Float, reverse: Boolean = false) {
            val path = Path().apply {
                if (reverse) {
                    moveTo(left, h * .5f); lineTo(right, h * .13f); lineTo(right, h * .87f)
                } else {
                    moveTo(right, h * .5f); lineTo(left, h * .13f); lineTo(left, h * .87f)
                }
                close()
            }
            drawPath(path, color, style = Fill)
        }
        when (symbol) {
            MediaSymbol.Play -> triangle(w * .15f, w * .86f)
            MediaSymbol.Pause -> {
                drawRect(color, topLeft = Offset(w * .2f, h * .12f), size = androidx.compose.ui.geometry.Size(w * .23f, h * .76f))
                drawRect(color, topLeft = Offset(w * .57f, h * .12f), size = androidx.compose.ui.geometry.Size(w * .23f, h * .76f))
            }
            MediaSymbol.Previous -> {
                drawLine(color, Offset(w * .1f, h * .12f), Offset(w * .1f, h * .88f), strokeWidth = w * .12f)
                triangle(w * .17f, w * .52f, reverse = true)
                triangle(w * .52f, w * .88f, reverse = true)
            }
            MediaSymbol.Next -> {
                triangle(w * .12f, w * .48f)
                triangle(w * .48f, w * .83f)
                drawLine(color, Offset(w * .9f, h * .12f), Offset(w * .9f, h * .88f), strokeWidth = w * .12f)
            }
        }
    }
}
