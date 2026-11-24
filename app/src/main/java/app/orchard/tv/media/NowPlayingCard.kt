package app.orchard.tv.media

import android.content.Intent
import android.media.session.PlaybackState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

@Composable
internal fun NowPlayingCard(visible: Boolean, onControlsAvailable: (Boolean) -> Unit, onOpen: (Intent) -> Unit) {
    val context = LocalContext.current
    val media = remember(context) { SystemMedia(context) }
    DisposableEffect(media, visible) {
        if (visible) media.start()
        onDispose { media.stop() }
    }
    val accessSettings = remember(context) { SystemProbe.revivalSettingsIntent(context) }
    val track = media.track
    val uriArtwork by rememberArtwork(track?.artworkUri?.takeIf { track.artwork == null })
    val artwork = track?.artwork ?: uriArtwork
    val canOpenAccess = !media.available && accessSettings != null
    val toggleAction = if (track?.playing == true) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
    val canToggle = track?.supports(toggleAction or PlaybackState.ACTION_PLAY_PAUSE) == true
    SideEffect { if (visible) onControlsAvailable(canOpenAccess || media.available) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.09f)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Fill),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(remember(artwork) { artwork.asImageBitmap() }, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else {
                BasicText("♫", style = Type.Title)
            }
        }
        Column(Modifier.weight(1f)) {
            val title = track?.title?.takeIf(String::isNotBlank) ?: stringResource(
                when {
                    !media.available -> R.string.media_access_needed
                    track == null -> R.string.media_idle
                    else -> R.string.media_now_playing
                },
            )
            BasicText(title, style = if (track == null) Type.Callout else Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!track?.subtitle.isNullOrBlank()) {
                BasicText(track.subtitle, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            if (!media.available && accessSettings != null) {
                val label = stringResource(R.string.cc_setup_revival_where)
                MediaButton(label, true, Modifier.fillMaxWidth().height(32.dp), { onOpen(accessSettings) }) { color ->
                    BasicText("$label ›", style = Type.Callout.copy(color = color))
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val button = Modifier.weight(1f).height(32.dp)
                val playing = track?.playing == true
                MediaButton(stringResource(R.string.media_previous), track?.supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS) == true, button, media::previous) {
                    TransportIcon(Transport.Previous, it)
                }
                MediaButton(stringResource(if (playing) R.string.media_pause else R.string.media_play), canToggle, button, media::toggle) {
                    TransportIcon(if (playing) Transport.Pause else Transport.Play, it)
                }
                MediaButton(stringResource(R.string.media_next), track?.supports(PlaybackState.ACTION_SKIP_TO_NEXT) == true, button, media::next) {
                    TransportIcon(Transport.Next, it)
                }
            }
        }
    }
}

@Composable
private fun MediaButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Pressable(onClick = { if (enabled) onClick() }, modifier = modifier, label = label) { state ->
        Box(
            Modifier.matchParentSize().background(if (state.focused) Palette.Focus else Palette.Fill, shape),
            contentAlignment = Alignment.Center,
        ) {
            val foreground = when {
                state.focused -> if (enabled) Palette.OnFocus else Palette.OnFocus.copy(alpha = 0.45f)
                enabled -> Palette.Label
                else -> Palette.SecondaryLabel
            }
            content(foreground)
        }
    }
}


private enum class Transport { Previous, Play, Pause, Next }

@Composable
private fun TransportIcon(icon: Transport, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 20f
        if (icon == Transport.Pause) {
            for (x in listOf(4f, 12f)) {
                drawRoundRect(color, Offset(x * unit, 3 * unit), Size(4 * unit, 14 * unit), CornerRadius(unit))
            }
        } else {
            val left = icon == Transport.Previous
            val triangles = if (icon == Transport.Play) listOf(3f to 17f) else listOf(1f to 10f, 10f to 19f)
            for ((start, end) in triangles) {
                val base = if (left) end else start
                val tip = if (left) start else end
                drawPath(Path().apply {
                    moveTo(base * unit, 3 * unit)
                    lineTo(tip * unit, 10 * unit)
                    lineTo(base * unit, 17 * unit)
                    close()
                }, color)
            }
        }
    }
}
