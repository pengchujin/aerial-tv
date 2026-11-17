package app.orchard.tv.ui.control

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.settings.QuickAction
import app.orchard.tv.settings.QuickTarget
import app.orchard.tv.settings.QuickTarget.Vpn
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

internal data class QuickTile(
    val id: String,
    val title: String,
    val icon: String,
    val checked: Boolean = false,
    val target: QuickTarget? = null,
)

internal fun QuickAction.tile(checked: Boolean = false) = QuickTile(id, title, target.icon, checked, target)

/** Small control-center tiles: focus is white, mute's active state is blue, selection uses a tick. */
@Composable
internal fun QuickTileGrid(tiles: List<QuickTile>, muted: Boolean = false, onClick: (QuickTile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row -> QuickTileRow(row, muted, onClick) }
    }
}

@Composable
internal fun QuickTileRow(row: List<QuickTile>, muted: Boolean = false, onClick: (QuickTile) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { tile ->
            key(tile.id) {
                val active = tile.target == QuickTarget.BuiltIn.Mute && muted
                Pressable(onClick = { onClick(tile) }, enabled = !tile.checked,
                    focusedScale = 1.04f, modifier = Modifier.weight(1f)) { press ->
                    val bg by animateColorAsState(when {
                        press.focused -> Palette.Focus
                        active -> Color(0xFFE8F4FF)
                        else -> Palette.Fill
                    }, label = "quick-tile")
                    val fg = if (press.focused || active) Color(0xFF1688D5) else Palette.Label
                    Column(Modifier.fillMaxWidth().smoothBackground(bg, 22.dp).height(78.dp).padding(7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                            if (tile.checked) GlyphIcon(Glyph.Check, fg, 24.dp) else QuickSymbol(tile, fg)
                        }
                        Spacer(Modifier.height(5.dp))
                        BasicText(tile.title, style = Type.Caption.copy(color = if (press.focused || active) Palette.OnFocus else Palette.Label,
                            fontSize = 10.sp, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val vpn = tile.target as? Vpn
                        if (vpn != null) {
                            val label = when (vpn.command) {
                                Vpn.Command.Toggle -> R.string.qc_vpn_toggle
                                Vpn.Command.Connect -> R.string.qc_vpn_connect
                                Vpn.Command.Disconnect -> R.string.qc_vpn_disconnect
                            }
                            BasicText(stringResource(label), style = Type.Caption.copy(
                                color = if (press.focused) Palette.OnFocus.copy(alpha = 0.65f) else Palette.SecondaryLabel,
                                fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun QuickSymbol(tile: QuickTile, color: Color) {
    when {
        tile.icon == "add" -> BasicText("+", style = Type.LargeTitle.copy(color = color, fontSize = 29.sp))
        tile.icon == "bluetooth" -> Canvas(Modifier.size(25.dp)) {
            val points = listOf(Offset(.5f,.05f), Offset(.5f,.95f), Offset(.85f,.7f), Offset(.15f,.25f), Offset(.85f,.3f), Offset(.5f,.05f))
            points.zipWithNext().forEach { (a, b) -> drawLine(color, Offset(a.x * size.width, a.y * size.height), Offset(b.x * size.width, b.y * size.height), 2.dp.toPx()) }
        }
        else -> GlyphIcon(when (tile.icon) {
            "vpn" -> Glyph.Shield
            "network" -> Glyph.Wifi
            "mute" -> Glyph.SpeakerMuted
            "inputs" -> Glyph.Input
            "settings" -> Glyph.Gear
            else -> Glyph.Switches
        }, color, 25.dp)
    }
}
